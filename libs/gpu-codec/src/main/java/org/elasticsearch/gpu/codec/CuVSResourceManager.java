/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gpu.codec;

import com.nvidia.cuvs.CagraIndexParams;
import com.nvidia.cuvs.CuVSMatrix;
import com.nvidia.cuvs.CuVSResources;
import com.nvidia.cuvs.spi.CuVSProvider;

import org.elasticsearch.core.Strings;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A manager of {@link com.nvidia.cuvs.CuVSResources}. There is one manager per GPU.
 *
 * <p>All access to GPU resources is mediated through a manager. A manager helps coordinate usage threads to:
 * <ul>
 *     <li>ensure single-threaded access to any particular resource at a time</li>
 *     <li>Control the total number of concurrent operations that may be performed on a GPU</li>
 *     <li>Pool resources, to avoid frequent creation and destruction, which are expensive operations. </li>
 * </ul>
 *
 * <p> Fundamentally, a resource is used in compute and memory bound operations. The former occurs prior to the latter, e.g.
 * index build (compute), followed by a copy/process of the newly built index (memory). The manager allows the resource
 * user to indicate that compute is complete before releasing the resources. This can help improve parallelism of compute
 * on the GPU - allowing the next compute operation to proceed before releasing the resources.
 *
 */
public interface CuVSResourceManager {
    /**
     * Safety factor applied to NN-DESCENT peak memory estimates to account for RMM pool
     * fragmentation, CUDA context overhead, and estimation imprecision. Only applied to NN-DESCENT
     * because that algorithm requires the full dataset and intermediate structures to be resident on
     * the GPU simultaneously. IVF-PQ is out-of-core (streams data in batches), so its ledger
     * reservation uses the raw index size estimate without this factor.
     */
    double GPU_COMPUTATION_MEMORY_FACTOR = 2.0;

    /**
     * Maximum GPU memory reserved for CUDA context, driver allocations, and other non-build
     * overhead that is always present on the device. The usable memory for index builds is
     * {@code totalDeviceMemory - min(totalDeviceMemory * 0.1, GPU_OVERHEAD_MAX_BYTES)}.
     * This cap prevents over-reserving on large GPUs where 10% would be many gigabytes.
     */
    long GPU_OVERHEAD_MAX_BYTES = 1024L * 1024 * 1024; // 1 GB

    /** Returns the usable GPU memory for index builds, accounting for fixed overhead. */
    static long usableMemory(long totalDeviceMemory) {
        long overhead = Math.min((long) (totalDeviceMemory * 0.1), GPU_OVERHEAD_MAX_BYTES);
        return totalDeviceMemory - overhead;
    }

    /**
     * Acquires a resource from the manager.
     *
     * <p>A manager can use the given parameters, numVectors and dims, to estimate the potential
     * effect on GPU memory and compute usage to determine whether to give out
     * another resource or wait for a resources to be returned before giving out another.
     */
    ManagedCuVSResources acquire(int numVectors, int dims, CuVSMatrix.DataType dataType, CagraIndexParams cagraIndexParams, String reason)
        throws InterruptedException, IOException;

    /**
     * Tries to acquire a resource from the manager.
     *
     * <p> Non-blocking variant of {@link #acquire}. Returns a locked resource immediately if
     * one is available and there is sufficient GPU memory, or {@code null} if the GPU is busy.
     */
    ManagedCuVSResources tryAcquire(
        int numVectors,
        int dims,
        CuVSMatrix.DataType dataType,
        CagraIndexParams cagraIndexParams,
        String reason
    ) throws IOException;

    /** Marks the resources as finished with regard to compute. */
    void finishedComputation(ManagedCuVSResources resources);

    /** Returns the given resource to the manager. */
    void release(ManagedCuVSResources resources);

    /** Shuts down the manager, releasing all open resources. */
    void shutdown();

    /** Returns the system-wide pooling manager. */
    static CuVSResourceManager pooling() {
        return PoolingCuVSResourceManager.Holder.INSTANCE;
    }

    /**
     * A manager that maintains a pool of resources.
     */
    class PoolingCuVSResourceManager implements CuVSResourceManager {

        static final Logger logger = LogManager.getLogger(CuVSResourceManager.class);
        static final int MAX_RESOURCES = 4;

        static class Holder {
            static final PoolingCuVSResourceManager INSTANCE = new PoolingCuVSResourceManager(
                MAX_RESOURCES,
                new RealGPUMemoryService(CuVSProvider.provider().gpuInfoProvider())
            );
        }

        private final ManagedCuVSResources[] pool;
        private final int capacity;
        private final GPUMemoryService gpuMemoryService;
        private int createdCount;

        ReentrantLock lock = new ReentrantLock();
        Condition enoughResourcesCondition = lock.newCondition();

        PoolingCuVSResourceManager(int capacity, GPUMemoryService gpuMemoryService) {
            if (capacity < 1 || capacity > MAX_RESOURCES) {
                throw new IllegalArgumentException("Resource count must be between 1 and " + MAX_RESOURCES);
            }
            this.capacity = capacity;
            this.gpuMemoryService = gpuMemoryService;
            this.pool = new ManagedCuVSResources[MAX_RESOURCES];
        }

        private ManagedCuVSResources getResourceFromPool() {
            for (int i = 0; i < createdCount; ++i) {
                var res = pool[i];
                if (res.isLocked() == false) {
                    return res;
                }
            }
            if (createdCount < capacity) {
                var delegate = createNew();
                if (delegate == null) {
                    logger.warn("Failed to create new GPU resource, will wait for an existing one");
                    return null;
                }
                var res = new ManagedCuVSResources(delegate);
                pool[createdCount++] = res;
                return res;
            }
            return null;
        }

        private int numLockedResources() {
            int lockedResources = 0;
            for (int i = 0; i < createdCount; ++i) {
                var res = pool[i];
                if (res.isLocked()) {
                    lockedResources++;
                }
            }
            return lockedResources;
        }

        @Override
        public ManagedCuVSResources acquire(
            int numVectors,
            int dims,
            CuVSMatrix.DataType dataType,
            CagraIndexParams cagraIndexParams,
            String reason
        ) throws InterruptedException, IOException {
            return doAcquire(numVectors, dims, dataType, cagraIndexParams, false, reason);
        }

        @Override
        public ManagedCuVSResources tryAcquire(
            int numVectors,
            int dims,
            CuVSMatrix.DataType dataType,
            CagraIndexParams cagraIndexParams,
            String reason
        ) throws IOException {
            try {
                return doAcquire(numVectors, dims, dataType, cagraIndexParams, true, reason);
            } catch (InterruptedException e) {
                throw new AssertionError("non-blocking acquire should never block", e);
            }
        }

        /**
         * Shared implementation for {@link #acquire} and {@link #tryAcquire}.
         *
         * @param nonBlocking if {@code true}, returns {@code null} instead of waiting when no
         *                    resource or memory is available (tryAcquire semantics); if {@code false},
         *                    blocks until a resource becomes available (acquire semantics).
         */
        private ManagedCuVSResources doAcquire(
            int numVectors,
            int dims,
            CuVSMatrix.DataType dataType,
            CagraIndexParams cagraIndexParams,
            boolean nonBlocking,
            String reason
        ) throws InterruptedException, IOException {
            var started = System.nanoTime();
            lock.lock();
            try {
                long requiredMemoryInBytes = estimateRequiredMemory(numVectors, dims, dataType, cagraIndexParams);
                logAcquireAttempt(reason, nonBlocking, numVectors, dims, dataType, requiredMemoryInBytes);

                boolean allConditionsMet = false;
                ManagedCuVSResources res = null;

                while (allConditionsMet == false) {
                    res = getResourceFromPool();

                    final boolean enoughMemory;
                    if (res != null) {
                        long totalMemoryInBytes = gpuMemoryService.totalMemoryInBytes(res);
                        long availableMemoryInBytes = gpuMemoryService.availableMemoryInBytes(res);
                        enoughMemory = requiredMemoryInBytes <= availableMemoryInBytes;
                        logMemoryCheck(availableMemoryInBytes, totalMemoryInBytes, requiredMemoryInBytes, enoughMemory);

                        if (requiredMemoryInBytes > totalMemoryInBytes) {
                            throw memoryExceededError(numVectors, dims, totalMemoryInBytes);
                        }

                        if (enoughMemory == false && numLockedResources() == 0) {
                            throw memoryExceededError(numVectors, dims, availableMemoryInBytes);
                        }
                    } else {
                        if (nonBlocking == false && createdCount == 0) {
                            throw new IOException("No GPU resources available and unable to create new ones");
                        }
                        logger.debug("No resources available in pool");
                        enoughMemory = false;
                    }

                    allConditionsMet = enoughMemory;
                    if (allConditionsMet == false) {
                        if (nonBlocking) {
                            return null;
                        }
                        logger.debug("Waiting for GPU resources for [{}]", reason);
                        enoughResourcesCondition.await();
                    }
                }
                logAcquired(reason, started, requiredMemoryInBytes);
                gpuMemoryService.reserveMemory(requiredMemoryInBytes);
                res.lock(() -> gpuMemoryService.releaseMemory(requiredMemoryInBytes));
                return res;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Estimates the GPU memory to reserve in the software ledger for the given build operation.
         *
         * <p>The ledger prevents concurrent GPU operations from over-committing device memory. Each
         * call to {@code doAcquire} reserves the value returned here, and releases it on
         * {@code release}. The reservation strategy differs by algorithm:
         *
         * <ul>
         *   <li><b>NN-DESCENT</b> — requires the full dataset plus intermediate structures resident on
         *       device simultaneously. We have an accurate peak formula, so we apply
         *       {@link #GPU_COMPUTATION_MEMORY_FACTOR} (2×) to cover fragmentation and CUDA overhead.
         *   <li><b>IVF-PQ</b> — streams data in batches (out-of-core) and never holds the full dataset
         *       on device at once. We use the raw index size estimate as a proportional proxy for the
         *       device footprint during build, without the safety factor.
         * </ul>
         */
        private long estimateRequiredMemory(int numVectors, int dims, CuVSMatrix.DataType dataType, CagraIndexParams cagraIndexParams) {
            if (cagraIndexParams.getCagraGraphBuildAlgo() == CagraIndexParams.CagraGraphBuildAlgo.IVF_PQ) {
                return estimateIPVPQMemory(numVectors, dims, dataType, cagraIndexParams);
            }
            long peakMemory = estimateNNDescentMemory(numVectors, dims, dataType, (int) cagraIndexParams.getIntermediateGraphDegree());
            return (long) (GPU_COMPUTATION_MEMORY_FACTOR * peakMemory);
        }

        // visible for testing
        /** Returns a resources if supported, otherwise null. */
        protected CuVSResources createNew() {
            try {
                return CuVSResources.create();
            } catch (UnsupportedOperationException uoe) {
                String msg = "";
                if (uoe.getMessage() == null) {
                    msg = "Runtime Java version: " + Runtime.version().feature();
                } else {
                    msg = ": " + uoe.getMessage();
                }
                logger.warn("GPU based vector indexing is not supported on this platform or java version; " + msg);
            } catch (Throwable t) {
                if (t instanceof ExceptionInInitializerError ex) {
                    t = ex.getCause();
                }
                logger.warn("Exception occurred during creation of cuvs resources", t);
            }
            return null;
        }

        @Override
        public void finishedComputation(ManagedCuVSResources resources) {
            logger.debug("Computation finished");
            // currently does nothing, but could allow acquire to return possibly blocked resources
            // enoughResourcesCondition.signalAll()
        }

        @Override
        public void release(ManagedCuVSResources resources) {
            try {
                lock.lock();
                assert resources.isLocked();
                resources.unlock();
                int lockedAfter = numLockedResources();
                logger.debug("Released resource to pool, locked after release [{}]", lockedAfter);
                enoughResourcesCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void shutdown() {
            for (int i = 0; i < createdCount; ++i) {
                var res = pool[i];
                assert res != null;
                res.delegate.close();
            }
        }

        private IllegalArgumentException memoryExceededError(int numVectors, int dims, long availableMemoryInBytes) {
            String message = Strings.format(
                "Requested GPU memory for [%d] vectors, [%d] dims exceeds available GPU memory [%d B]",
                numVectors,
                dims,
                availableMemoryInBytes
            );
            logger.error(message);
            return new IllegalArgumentException(message);
        }

        private void logAcquireAttempt(
            String reason,
            boolean nonBlocking,
            int numVectors,
            int dims,
            CuVSMatrix.DataType dataType,
            long requiredMemoryInBytes
        ) {
            logger.debug(
                "[{}] Try acquire(nonBlocking={}): [{}] vectors, dims={} type={}, estimated={}B, state:([{}] created, [{}] locked)",
                reason,
                nonBlocking,
                numVectors,
                dims,
                dataType.name(),
                requiredMemoryInBytes,
                createdCount,
                numLockedResources()
            );
        }

        private void logMemoryCheck(long available, long total, long required, boolean enough) {
            logger.debug("Memory check: available={}B, total={}B, required={}B, enough={}", available, total, required, enough);
        }

        private void logAcquired(String reason, long startedNanos, long reservedBytes) {
            logger.debug(
                "[{}] acquired in {}ms, reserving {}B, state:([{}] created, [{}] locked)",
                reason,
                (System.nanoTime() - startedNanos) / 1_000_000.0,
                reservedBytes,
                createdCount,
                numLockedResources() + 1
            );
        }
    }

    /** A managed resource. Cannot be closed. */
    final class ManagedCuVSResources implements CuVSResources {

        private final CuVSResources delegate;
        private static final Runnable NOT_LOCKED = () -> {};
        private Runnable unlockAction = NOT_LOCKED;

        ManagedCuVSResources(CuVSResources resources) {
            this.delegate = resources;
        }

        @Override
        public ScopedAccess access() {
            return delegate.access();
        }

        @Override
        public int deviceId() {
            return delegate.deviceId();
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("this resource is managed, cannot be closed by clients");
        }

        @Override
        public Path tempDirectory() {
            return null;
        }

        @Override
        public String toString() {
            return "ManagedCuVSResources[delegate=" + delegate + "]";
        }

        void lock(Runnable unlockAction) {
            this.unlockAction = unlockAction;
        }

        void unlock() {
            unlockAction.run();
            unlockAction = NOT_LOCKED;
        }

        boolean isLocked() {
            return unlockAction != NOT_LOCKED;
        }
    }

    /**
     * Estimates the peak GPU device memory for building a CAGRA index using NN_DESCENT.
     *
     * <p>Derived from the cuVS
     * <a href="https://docs.rapids.ai/api/cuvs/nightly/neighbors/cagra/#build-peak-memory-usage">
     * CAGRA Build Peak Memory Usage</a> documentation:
     * <ul>
     *   <li>{@code dataset_size = numVectors × dims × elementTypeBytes}
     *       — input vectors copied to device memory</li>
     *   <li>{@code nnd_device_peak = numVectors × (dims × 2 + 276 + 4)}
     *       — fp16 copy of vectors (dims × 2), working graph/locks/counters (276 fixed),
     *       and L2 norms (4, always present as Elasticsearch uses L2 distance internally)</li>
     *   <li>{@code optimize_peak = numVectors × (4 + (sizeof(IdxT) + 1) × intermediateGraphDegree)}
     *       — graph pruning/reordering phase, where {@code sizeof(IdxT) = 4}</li>
     *   <li>{@code build_peak = dataset_size + max(nnd_device_peak, optimize_peak)}</li>
     * </ul>
     *
     * <p>A safety factor ({@link #GPU_COMPUTATION_MEMORY_FACTOR}) is applied by the caller to account
     * for RMM pool fragmentation, CUDA context overhead, and estimation imprecision.
     *
     * @param numVectors the number of vectors
     * @param dims the dimensionality of vectors
     * @param dataType the data type of the vectors
     * @param intermediateGraphDegree the degree of the intermediate kNN graph
     * @return the estimated peak device memory in bytes (before safety factor)
     */
    static long estimateNNDescentMemory(int numVectors, int dims, CuVSMatrix.DataType dataType, int intermediateGraphDegree) {
        long datasetSize = (long) numVectors * dims * elementTypeBytes(dataType);
        long nndDevicePeak = (long) numVectors * ((long) dims * 2 + 280);
        long optimizePeak = (long) numVectors * (4 + 5L * intermediateGraphDegree);
        return datasetSize + Math.max(nndDevicePeak, optimizePeak);
    }

    /**
     * Estimates the IVF-PQ index size on the GPU device during CAGRA build.
     *
     * <p>Derived from the cuVS
     * <a href="https://docs.rapids.ai/api/cuvs/nightly/neighbors/ivfpq/#index-device-memory">
     * IVF-PQ Index Device Memory</a> documentation:
     * <ul>
     *   <li>{@code encoded_data = numVectors × pqDim × pqBits / 8} — PQ-encoded vectors</li>
     *   <li>{@code indices = numVectors × sizeof(IdxT)} — per-vector index (4 bytes)</li>
     *   <li>{@code list_pointers = numClusters × sizeof(uint32_t)} — IVF list pointers</li>
     *   <li>{@code index_size ≈ encoded_data + indices + list_pointers}</li>
     * </ul>
     *
     * <p>No safety factor is applied because IVF-PQ streams data in batches (out-of-core) and does
     * not require the full dataset to be resident on device simultaneously. The returned value is
     * used directly as the ledger reservation.
     *
     * @param numVectors the number of vectors
     * @param dims the dimensionality of vectors (unused but kept for API consistency)
     * @param dataType the data type of the vectors (unused but kept for API consistency)
     * @param cagraIndexParams the CAGRA index parameters containing IVF-PQ configuration
     * @return the estimated IVF-PQ index device memory in bytes
     */
    static long estimateIPVPQMemory(int numVectors, int dims, CuVSMatrix.DataType dataType, CagraIndexParams cagraIndexParams) {
        assert assertCuVSIvfPqParams(cagraIndexParams);
        var pqDim = cagraIndexParams.getCuVSIvfPqParams().getIndexParams().getPqDim();
        var pqBits = cagraIndexParams.getCuVSIvfPqParams().getIndexParams().getPqBits();
        var numClusters = cagraIndexParams.getCuVSIvfPqParams().getIndexParams().getnLists();
        long encodedData = (long) (numVectors * pqDim * (pqBits / 8.0));
        long indices = (long) numVectors * Integer.BYTES;
        long listPointers = (long) numClusters * Integer.BYTES;
        return encodedData + indices + listPointers;
    }

    static int elementTypeBytes(CuVSMatrix.DataType dataType) {
        return switch (dataType) {
            case FLOAT -> Float.BYTES;
            case INT, UINT -> Integer.BYTES;
            case BYTE -> Byte.BYTES;
        };
    }

    static boolean assertCuVSIvfPqParams(CagraIndexParams cagraIndexParams) {
        assert cagraIndexParams.getCagraGraphBuildAlgo() == CagraIndexParams.CagraGraphBuildAlgo.IVF_PQ
            && cagraIndexParams.getCuVSIvfPqParams() != null
            && cagraIndexParams.getCuVSIvfPqParams().getIndexParams() != null
            && cagraIndexParams.getCuVSIvfPqParams().getIndexParams().getPqDim() != 0;
        return true;
    }
}
