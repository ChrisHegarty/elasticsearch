/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.zstd;

import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.nativeaccess.NativeAccess;
import org.elasticsearch.nativeaccess.Zstd;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures raw zstd decompression throughput via the Panama FFM binding,
 * with no Lucene codec framing, no IndexInput, no seek/readVInt overhead.
 * This isolates the native decompression speed as seen from Java.
 *
 * Compares heap (byte[]) and off-heap (native MemorySegment) buffer paths.
 */
@Fork(value = 1, jvmArgsPrepend = { "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ZstdRawDecompressBenchmark {

    private static final int NUM_BLOCKS = 256;

    @Param({ "4096", "16384", "65536" })
    int blockSize;

    @Param({ "HEAP", "NATIVE" })
    String bufferMode;

    private Zstd zstd;

    // Heap path
    private byte[][] compressedBlocks;
    private byte[][] decompressedBlocks;

    // Native path
    private MemorySegment[] compressedSegments;
    private MemorySegment[] decompressedSegments;
    private Arena arena;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        Utils.configureBenchmarkLogging();
        zstd = NativeAccess.instance().getZstd();
        byte[] sourceData = loadSourceData();

        compressedBlocks = new byte[NUM_BLOCKS][];
        decompressedBlocks = new byte[NUM_BLOCKS][];

        for (int i = 0; i < NUM_BLOCKS; i++) {
            byte[] original = new byte[blockSize];
            int offset = (i * blockSize) % Math.max(1, sourceData.length - blockSize);
            System.arraycopy(sourceData, offset, original, 0, blockSize);

            int bound = zstd.compressBound(blockSize);
            byte[] cBuf = new byte[bound];
            int cLen = zstd.compress(cBuf, 0, bound, original, 0, blockSize, 1);
            compressedBlocks[i] = new byte[cLen];
            System.arraycopy(cBuf, 0, compressedBlocks[i], 0, cLen);
            decompressedBlocks[i] = new byte[blockSize];
        }

        if (bufferMode.equals("NATIVE")) {
            arena = Arena.ofShared();
            compressedSegments = new MemorySegment[NUM_BLOCKS];
            decompressedSegments = new MemorySegment[NUM_BLOCKS];
            for (int i = 0; i < NUM_BLOCKS; i++) {
                compressedSegments[i] = arena.allocate(compressedBlocks[i].length);
                MemorySegment.copy(compressedBlocks[i], 0, compressedSegments[i], ValueLayout.JAVA_BYTE, 0, compressedBlocks[i].length);
                decompressedSegments[i] = arena.allocate(blockSize);
            }
        }
    }

    private byte[] loadSourceData() throws IOException {
        String dataFile = System.getProperty("dataFile");
        if (dataFile != null) {
            byte[] fileData = Files.readAllBytes(Path.of(dataFile));
            if (fileData.length < blockSize) {
                throw new IllegalArgumentException("dataFile too small: " + fileData.length + " < blockSize " + blockSize);
            }
            return fileData;
        }
        Random rng = new Random(42);
        byte[] data = new byte[NUM_BLOCKS * blockSize];
        rng.nextBytes(data);
        return data;
    }

    @Benchmark
    @OperationsPerInvocation(NUM_BLOCKS)
    public void decompress(Blackhole bh) {
        if (bufferMode.equals("HEAP")) {
            for (int i = 0; i < NUM_BLOCKS; i++) {
                int len = zstd.decompress(decompressedBlocks[i], 0, blockSize, compressedBlocks[i], 0, compressedBlocks[i].length);
                bh.consume(len);
            }
        } else {
            for (int i = 0; i < NUM_BLOCKS; i++) {
                int len = zstd.decompress(decompressedSegments[i], compressedSegments[i]);
                bh.consume(len);
            }
        }
    }
}
