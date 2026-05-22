/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Async vector reader using Linux io_uring for O_DIRECT reads.
 * Submits batches of 4KB read requests and reaps completions for
 * score-as-available processing. Uses Panama FFI exclusively.
 *
 * This is experimental benchmark code -- not for production use.
 */
public final class IoUringVectorReader implements Closeable {

    // --- io_uring syscall numbers (aarch64 / x86_64 unified from 5.1+) ---
    private static final int __NR_io_uring_setup = 425;
    private static final int __NR_io_uring_enter = 426;
    private static final int __NR_io_uring_register = 427;

    // --- io_uring constants ---
    private static final byte IORING_OP_READ_FIXED = 4;
    private static final byte IOSQE_FIXED_FILE = 1;
    private static final int IORING_ENTER_GETEVENTS = 1;
    private static final int IORING_SETUP_SINGLE_ISSUER = (1 << 12);
    private static final int IORING_REGISTER_BUFFERS = 0;
    private static final int IORING_REGISTER_FILES = 2;

    // --- open(2) flags ---
    private static final int O_RDONLY = 0;
    private static final int O_DIRECT = 0x4000; // aarch64 and x86_64

    // --- posix_fadvise ---
    private static final int POSIX_FADV_RANDOM = 1;

    // --- mmap constants ---
    private static final int PROT_READ = 0x1;
    private static final int PROT_WRITE = 0x2;
    private static final int MAP_SHARED = 0x01;
    private static final int MAP_POPULATE = 0x08000;

    // --- io_uring mmap offsets ---
    private static final long IORING_OFF_SQ_RING = 0L;
    private static final long IORING_OFF_CQ_RING = 0x8000000L;
    private static final long IORING_OFF_SQES = 0x10000000L;

    // --- SQE layout (64 bytes) ---
    // Fields at fixed byte offsets within each 64-byte SQE
    private static final int SQE_SIZE = 64;
    private static final int SQE_OPCODE_OFF = 0;
    private static final int SQE_FLAGS_OFF = 1;
    private static final int SQE_IOPRIO_OFF = 2;
    private static final int SQE_FD_OFF = 4;
    private static final int SQE_OFF_OFF = 8;
    private static final int SQE_ADDR_OFF = 16;
    private static final int SQE_LEN_OFF = 24;
    private static final int SQE_RW_FLAGS_OFF = 28;
    private static final int SQE_USER_DATA_OFF = 32;
    private static final int SQE_BUF_INDEX_OFF = 40;

    // --- CQE layout (16 bytes) ---
    private static final int CQE_SIZE = 16;
    private static final int CQE_USER_DATA_OFF = 0;
    private static final int CQE_RES_OFF = 8;
    private static final int CQE_FLAGS_OFF = 12;

    // --- io_uring_params struct offsets ---
    // struct io_uring_params is 120 bytes
    private static final int PARAMS_SIZE = 120;
    private static final int PARAMS_SQ_ENTRIES_OFF = 0;
    private static final int PARAMS_CQ_ENTRIES_OFF = 4;
    private static final int PARAMS_FLAGS_OFF = 8;
    // sq_off starts at offset 40
    private static final int PARAMS_SQ_OFF_HEAD = 40;
    private static final int PARAMS_SQ_OFF_TAIL = 44;
    private static final int PARAMS_SQ_OFF_RING_MASK = 48;
    private static final int PARAMS_SQ_OFF_RING_ENTRIES = 52;
    private static final int PARAMS_SQ_OFF_FLAGS = 56;
    private static final int PARAMS_SQ_OFF_ARRAY = 68;
    // cq_off starts at offset 80
    private static final int PARAMS_CQ_OFF_HEAD = 80;
    private static final int PARAMS_CQ_OFF_TAIL = 84;
    private static final int PARAMS_CQ_OFF_RING_MASK = 88;
    private static final int PARAMS_CQ_OFF_RING_ENTRIES = 92;
    private static final int PARAMS_CQ_OFF_CQES = 100;

    // --- iovec struct for IORING_REGISTER_BUFFERS ---
    private static final int IOVEC_SIZE = 16; // { void *iov_base; size_t iov_len; }

    // --- FFI method handles ---
    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle syscall$mh;
    private static final MethodHandle open$mh;
    private static final MethodHandle close$mh;
    private static final MethodHandle mmap$mh;
    private static final MethodHandle munmap$mh;
    private static final MethodHandle posix_fadvise$mh;
    private static final MethodHandle iouring_enter_syscall$mh;
    private static final MethodHandle iouring_register_syscall$mh;

    static {
        var lookup = LINKER.defaultLookup();
        var loaderLookup = java.lang.foreign.SymbolLookup.loaderLookup();
        java.lang.foreign.SymbolLookup combinedLookup = name -> loaderLookup.find(name).or(() -> lookup.find(name));

        MemorySegment syscallAddr = combinedLookup.find("syscall").orElseThrow();

        // long syscall(long number, int entries, void *params) -- for io_uring_setup
        syscall$mh = LINKER.downcallHandle(
            syscallAddr,
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS),
            Linker.Option.firstVariadicArg(1)
        );

        // long syscall(__NR_io_uring_enter, int fd, int to_submit, int min_complete, int flags, void *sig, int sigsetsize)
        iouring_enter_syscall$mh = LINKER.downcallHandle(
            syscallAddr,
            FunctionDescriptor.of(
                JAVA_LONG, JAVA_LONG,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                ADDRESS, JAVA_INT
            ),
            Linker.Option.firstVariadicArg(1)
        );

        // long syscall(__NR_io_uring_register, int fd, int opcode, void *arg, int nr_args)
        iouring_register_syscall$mh = LINKER.downcallHandle(
            syscallAddr,
            FunctionDescriptor.of(
                JAVA_LONG, JAVA_LONG,
                JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT
            ),
            Linker.Option.firstVariadicArg(1)
        );

        open$mh = LINKER.downcallHandle(
            combinedLookup.find("open").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.firstVariadicArg(2)
        );

        close$mh = LINKER.downcallHandle(
            combinedLookup.find("close").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT)
        );

        mmap$mh = LINKER.downcallHandle(
            combinedLookup.find("mmap").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)
        );

        munmap$mh = LINKER.downcallHandle(
            combinedLookup.find("munmap").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)
        );

        posix_fadvise$mh = LINKER.downcallHandle(
            combinedLookup.find("posix_fadvise").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)
        );
    }

    private final Arena arena;
    private final int fileFd;
    private final int ringFd;
    private final int ringEntries;
    private final int sqMask;
    private final int cqMask;

    // SQ ring pointers
    private final MemorySegment sqRing;
    private final long sqRingSize;
    private final int sqHeadOff;
    private final int sqTailOff;
    private final int sqArrayOff;

    // CQ ring pointers
    private final MemorySegment cqRing;
    private final long cqRingSize;
    private final int cqHeadOff;
    private final int cqTailOff;
    private final int cqCqesOff;

    // SQE array
    private final MemorySegment sqeArray;
    private final long sqeArraySize;

    // Pre-allocated aligned 4KB buffers
    private final MemorySegment[] buffers;
    private final int bufferSize;

    /**
     * Creates a new io_uring-based vector reader.
     *
     * @param cachePath   path to the shared_snapshot_cache file
     * @param entries     number of SQ/CQ entries (power of 2, e.g. 1024)
     * @param vectorBytes size of each vector in bytes (e.g. 4096 for 1024-dim float32)
     */
    public IoUringVectorReader(Path cachePath, int entries, int vectorBytes) throws IOException {
        this.bufferSize = vectorBytes;
        this.arena = Arena.ofShared();

        try {
            // 1. Open the cache file with O_DIRECT
            this.fileFd = openFile(cachePath, O_RDONLY | O_DIRECT);
            if (fileFd < 0) {
                throw new IOException("Failed to open " + cachePath + " with O_DIRECT, fd=" + fileFd);
            }

            // 2. posix_fadvise(FADV_RANDOM) to disable readahead
            int adviseRet = fadvise(fileFd, 0, 0, POSIX_FADV_RANDOM);
            if (adviseRet != 0) {
                System.err.println("[io_uring] posix_fadvise returned " + adviseRet + " (non-fatal)");
            }

            // 3. io_uring_setup
            MemorySegment params = arena.allocate(PARAMS_SIZE);
            params.fill((byte) 0);
            params.set(JAVA_INT, PARAMS_FLAGS_OFF, IORING_SETUP_SINGLE_ISSUER);

            this.ringFd = ioUringSetup(entries, params);
            if (ringFd < 0) {
                throw new IOException("io_uring_setup failed, returned " + ringFd);
            }

            // Read back ring parameters
            this.ringEntries = params.get(JAVA_INT, PARAMS_SQ_ENTRIES_OFF);
            int cqEntries = params.get(JAVA_INT, PARAMS_CQ_ENTRIES_OFF);
            this.sqMask = ringEntries - 1;
            this.cqMask = cqEntries - 1;

            // SQ ring offsets
            this.sqHeadOff = params.get(JAVA_INT, PARAMS_SQ_OFF_HEAD);
            this.sqTailOff = params.get(JAVA_INT, PARAMS_SQ_OFF_TAIL);
            int sqRingMaskOff = params.get(JAVA_INT, PARAMS_SQ_OFF_RING_MASK);
            int sqRingEntriesOff = params.get(JAVA_INT, PARAMS_SQ_OFF_RING_ENTRIES);
            this.sqArrayOff = params.get(JAVA_INT, PARAMS_SQ_OFF_ARRAY);

            // CQ ring offsets
            this.cqHeadOff = params.get(JAVA_INT, PARAMS_CQ_OFF_HEAD);
            this.cqTailOff = params.get(JAVA_INT, PARAMS_CQ_OFF_TAIL);
            this.cqCqesOff = params.get(JAVA_INT, PARAMS_CQ_OFF_CQES);

            // 4. mmap the rings
            this.sqRingSize = (long) sqArrayOff + (long) ringEntries * 4;
            this.sqRing = mmapRing(ringFd, sqRingSize, IORING_OFF_SQ_RING);

            this.cqRingSize = (long) cqCqesOff + (long) cqEntries * CQE_SIZE;
            this.cqRing = mmapRing(ringFd, cqRingSize, IORING_OFF_CQ_RING);

            this.sqeArraySize = (long) ringEntries * SQE_SIZE;
            this.sqeArray = mmapRing(ringFd, sqeArraySize, IORING_OFF_SQES);

            // Initialize SQ array: identity mapping (sqArray[i] = i)
            for (int i = 0; i < ringEntries; i++) {
                sqRing.set(JAVA_INT, (long) sqArrayOff + (long) i * 4, i);
            }

            // 5. Allocate aligned buffers
            this.buffers = new MemorySegment[ringEntries];
            int alignedSize = alignUp(vectorBytes, 4096);
            for (int i = 0; i < ringEntries; i++) {
                // Allocate with 4K alignment for O_DIRECT
                buffers[i] = arena.allocate(alignedSize, 4096);
            }

            // 6. Register buffers with io_uring
            registerBuffers();

            // 7. Register the file fd
            registerFile();

            System.err.println("[io_uring] initialized: ring_entries=" + ringEntries
                + ", cq_entries=" + cqEntries
                + ", buffer_size=" + vectorBytes
                + ", fd=" + fileFd
                + ", ring_fd=" + ringFd);

        } catch (Throwable t) {
            arena.close();
            if (t instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to initialize io_uring", t);
        }
    }

    /**
     * Submits read requests and returns completions via the callback.
     * Reads are submitted in batches up to the ring size, and completions
     * are reaped as they arrive.
     *
     * @param physicalOffsets array of physical file offsets to read
     * @param count          number of reads to submit
     * @param handler        called for each completed read: handler.onComplete(index, buffer, bytesRead)
     */
    public void submitAndReap(long[] physicalOffsets, int count, CompletionHandler handler) throws IOException {
        int submitted = 0;
        while (submitted < count) {
            int batchSize = Math.min(ringEntries, count - submitted);
            submitBatch(physicalOffsets, submitted, batchSize);
            reapBatch(batchSize, handler);
            submitted += batchSize;
        }
    }

    private void submitBatch(long[] offsets, int startIdx, int batchSize) throws IOException {
        // Write SQEs
        for (int i = 0; i < batchSize; i++) {
            long sqeBase = (long) i * SQE_SIZE;
            sqeArray.set(JAVA_BYTE, sqeBase + SQE_OPCODE_OFF, IORING_OP_READ_FIXED);
            sqeArray.set(JAVA_BYTE, sqeBase + SQE_FLAGS_OFF, IOSQE_FIXED_FILE);
            sqeArray.set(JAVA_SHORT, sqeBase + SQE_IOPRIO_OFF, (short) 0);
            sqeArray.set(JAVA_INT, sqeBase + SQE_FD_OFF, 0); // registered fd index 0
            sqeArray.set(JAVA_LONG, sqeBase + SQE_OFF_OFF, offsets[startIdx + i]);
            sqeArray.set(JAVA_LONG, sqeBase + SQE_ADDR_OFF, buffers[i].address());
            sqeArray.set(JAVA_INT, sqeBase + SQE_LEN_OFF, bufferSize);
            sqeArray.set(JAVA_INT, sqeBase + SQE_RW_FLAGS_OFF, 0);
            sqeArray.set(JAVA_LONG, sqeBase + SQE_USER_DATA_OFF, startIdx + i);
            sqeArray.set(JAVA_SHORT, sqeBase + SQE_BUF_INDEX_OFF, (short) i);
        }

        // Advance SQ tail
        // Memory ordering: store-release on tail so kernel sees the SQEs
        int sqTail = (int) sqRing.get(JAVA_INT, sqTailOff);
        VarHandle.storeStoreFence();
        sqRing.set(JAVA_INT, sqTailOff, sqTail + batchSize);
        VarHandle.storeStoreFence();

        // Submit + wait for at least 1 completion
        int ret = ioUringEnter(ringFd, batchSize, 1, IORING_ENTER_GETEVENTS);
        if (ret < 0) {
            throw new IOException("io_uring_enter failed: " + ret);
        }
    }

    private void reapBatch(int expected, CompletionHandler handler) throws IOException {
        int reaped = 0;
        while (reaped < expected) {
            VarHandle.loadLoadFence();
            int cqHead = cqRing.get(JAVA_INT, cqHeadOff);
            int cqTail = cqRing.get(JAVA_INT, cqTailOff);

            if (cqHead == cqTail) {
                // No completions yet -- wait for more
                int ret = ioUringEnter(ringFd, 0, 1, IORING_ENTER_GETEVENTS);
                if (ret < 0) {
                    throw new IOException("io_uring_enter (reap) failed: " + ret);
                }
                continue;
            }

            while (cqHead != cqTail && reaped < expected) {
                int cqeIdx = cqHead & cqMask;
                long cqeBase = (long) cqCqesOff + (long) cqeIdx * CQE_SIZE;

                long userData = cqRing.get(JAVA_LONG, cqeBase + CQE_USER_DATA_OFF);
                int res = cqRing.get(JAVA_INT, cqeBase + CQE_RES_OFF);

                int globalIdx = (int) userData;
                int bufIdx = globalIdx % ringEntries;

                handler.onComplete(globalIdx, buffers[bufIdx], res);

                cqHead++;
                reaped++;
            }

            // Advance CQ head (store-release)
            VarHandle.storeStoreFence();
            cqRing.set(JAVA_INT, cqHeadOff, cqHead);
        }

        // Reset SQ head for next batch
        sqRing.set(JAVA_INT, sqHeadOff, sqRing.get(JAVA_INT, sqTailOff));
    }

    public int getRingEntries() {
        return ringEntries;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public void close() throws IOException {
        try {
            if (ringFd >= 0) closefd(ringFd);
            if (fileFd >= 0) closefd(fileFd);
            if (sqRing != null && sqRing.address() != 0) munmapSafe(sqRing, sqRingSize);
            if (cqRing != null && cqRing.address() != 0) munmapSafe(cqRing, cqRingSize);
            if (sqeArray != null && sqeArray.address() != 0) munmapSafe(sqeArray, sqeArraySize);
        } finally {
            arena.close();
        }
    }

    // --- FFI wrappers ---

    private static int openFile(Path path, int flags) throws IOException {
        try (Arena localArena = Arena.ofConfined()) {
            MemorySegment pathStr = localArena.allocateFrom(path.toAbsolutePath().toString());
            return (int) open$mh.invokeExact(pathStr, flags);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FFI open() failed", t);
        }
    }

    private static int fadvise(int fd, long offset, long len, int advice) throws IOException {
        try {
            return (int) posix_fadvise$mh.invokeExact(fd, offset, len, advice);
        } catch (Throwable t) {
            throw new IOException("FFI posix_fadvise() failed", t);
        }
    }

    /**
     * Calls io_uring_setup via syscall().
     * long syscall(__NR_io_uring_setup, unsigned entries, struct io_uring_params *p)
     */
    private static int ioUringSetup(int entries, MemorySegment params) throws IOException {
        try {
            long ret = (long) syscall$mh.invokeExact((long) __NR_io_uring_setup, entries, params);
            return (int) ret;
        } catch (Throwable t) {
            throw new IOException("syscall(io_uring_setup) failed", t);
        }
    }

    /**
     * Calls io_uring_enter via syscall(). We need a different arity than setup,
     * so we use a dedicated method handle.
     */
    private int ioUringEnter(int fd, int toSubmit, int minComplete, int flags) throws IOException {
        try {
            long ret = ioUringEnterSyscall(fd, toSubmit, minComplete, flags);
            return (int) ret;
        } catch (Throwable t) {
            throw new IOException("syscall(io_uring_enter) failed", t);
        }
    }

    private static long ioUringEnterSyscall(int fd, int toSubmit, int minComplete, int flags) throws Throwable {
        return (long) iouring_enter_syscall$mh.invokeExact(
            (long) __NR_io_uring_enter,
            fd,
            toSubmit,
            minComplete,
            flags,
            MemorySegment.NULL,
            0
        );
    }

    private static long ioUringRegisterSyscall(int ringFd, int opcode, MemorySegment arg, int nrArgs) throws Throwable {
        return (long) iouring_register_syscall$mh.invokeExact(
            (long) __NR_io_uring_register,
            ringFd,
            opcode,
            arg,
            nrArgs
        );
    }

    private MemorySegment mmapRing(int fd, long size, long offset) throws IOException {
        try {
            MemorySegment addr = (MemorySegment) mmap$mh.invokeExact(
                MemorySegment.NULL,
                size,
                PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_POPULATE,
                fd,
                offset
            );
            long rawAddr = addr.address();
            if (rawAddr == -1L || rawAddr == 0L) {
                throw new IOException("mmap failed for io_uring ring (offset=" + offset + ", size=" + size + ")");
            }
            // Reinterpret to the actual size so we can access the full range
            return addr.reinterpret(size);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("FFI mmap() failed", t);
        }
    }

    private void registerBuffers() throws IOException {
        // Build iovec array: { void *iov_base, size_t iov_len } x ringEntries
        MemorySegment iovecs = arena.allocate((long) IOVEC_SIZE * ringEntries, 8);
        int alignedSize = alignUp(bufferSize, 4096);
        for (int i = 0; i < ringEntries; i++) {
            long base = (long) i * IOVEC_SIZE;
            iovecs.set(JAVA_LONG, base, buffers[i].address());      // iov_base
            iovecs.set(JAVA_LONG, base + 8, alignedSize);           // iov_len
        }

        try {
            long ret = ioUringRegisterSyscall(ringFd, IORING_REGISTER_BUFFERS, iovecs, ringEntries);
            if (ret < 0) {
                System.err.println("[io_uring] IORING_REGISTER_BUFFERS failed: " + ret + " (continuing without registered buffers)");
            }
        } catch (Throwable t) {
            throw new IOException("io_uring register buffers failed", t);
        }
    }

    private void registerFile() throws IOException {
        MemorySegment fds = arena.allocate(4, 4);
        fds.set(JAVA_INT, 0, fileFd);

        try {
            long ret = ioUringRegisterSyscall(ringFd, IORING_REGISTER_FILES, fds, 1);
            if (ret < 0) {
                System.err.println("[io_uring] IORING_REGISTER_FILES failed: " + ret + " (continuing without registered files)");
            }
        } catch (Throwable t) {
            throw new IOException("io_uring register files failed", t);
        }
    }

    private static void closefd(int fd) {
        try {
            close$mh.invokeExact(fd);
        } catch (Throwable t) {
            // best effort
        }
    }

    private static void munmapSafe(MemorySegment seg, long size) {
        try {
            munmap$mh.invokeExact(seg, size);
        } catch (Throwable t) {
            // best effort
        }
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Callback interface for completed reads.
     */
    @FunctionalInterface
    public interface CompletionHandler {
        /**
         * Called when a read completes.
         *
         * @param index     the original index from the physicalOffsets array
         * @param buffer    the buffer containing the read data (valid until next submitAndReap call)
         * @param bytesRead number of bytes read, or negative errno on failure
         */
        void onComplete(int index, MemorySegment buffer, int bytesRead) throws IOException;
    }
}
