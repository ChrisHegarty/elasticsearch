/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gpu.codec;

import com.nvidia.cuvs.CuVSResources;
import com.nvidia.cuvs.GPUInfoProvider;

/**
 * A {@link GPUMemoryService} that combines real GPU memory queries with software-side reservation tracking.
 *
 * <p>The GPU's reported free memory is a lagging indicator — between acquiring a resource and the CUDA kernel
 * actually allocating device memory, other threads can observe stale free memory values. To close this race
 * window, this service also maintains a software ledger of estimated reservations. The available memory
 * returned is the minimum of the device-reported free memory and the ledger-tracked available memory,
 * ensuring concurrent builds cannot over-commit.
 *
 * <p>Thread safety: all methods are called under the {@link CuVSResourceManager.PoolingCuVSResourceManager} lock,
 * so no additional synchronization is needed in this class.
 */
class RealGPUMemoryService implements GPUMemoryService {
    private final GPUInfoProvider gpuInfoProvider;
    private long totalDeviceMemoryInBytes; // lazily initialized; immutable once set
    private long reservedMemoryInBytes;

    RealGPUMemoryService(GPUInfoProvider gpuInfoProvider) {
        this.gpuInfoProvider = gpuInfoProvider;
    }

    @Override
    public long totalMemoryInBytes(CuVSResources res) {
        if (totalDeviceMemoryInBytes == 0) {
            totalDeviceMemoryInBytes = gpuInfoProvider.getCurrentInfo(res).totalDeviceMemoryInBytes();
        }
        return totalDeviceMemoryInBytes;
    }

    @Override
    public long availableMemoryInBytes(CuVSResources res) {
        long deviceFree = gpuInfoProvider.getCurrentInfo(res).freeDeviceMemoryInBytes();
        long ledgerAvailable = totalMemoryInBytes(res) - reservedMemoryInBytes;
        return Math.min(deviceFree, ledgerAvailable);
    }

    @Override
    public void reserveMemory(long memoryInBytes) {
        checkNonNegative(memoryInBytes, "reserve amount");
        reservedMemoryInBytes += memoryInBytes;
    }

    @Override
    public void releaseMemory(long memoryInBytes) {
        checkNonNegative(memoryInBytes, "release amount");
        reservedMemoryInBytes -= memoryInBytes;
        checkNonNegative(reservedMemoryInBytes, "memory ledger");
    }

    static void checkNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalStateException(name + " must be non-negative, got: " + value);
        }
    }
}
