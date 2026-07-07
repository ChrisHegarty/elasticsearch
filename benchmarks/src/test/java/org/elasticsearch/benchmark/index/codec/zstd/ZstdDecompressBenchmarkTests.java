/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.zstd;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.test.ESTestCase;

import java.util.List;

/**
 * Verifies that the ZstdDecompressBenchmark setup/teardown works correctly
 * and that decompression produces valid output for each directory type and mode.
 */
public class ZstdDecompressBenchmarkTests extends ESTestCase {

    private final String directoryType;
    private final String decompressMode;

    public ZstdDecompressBenchmarkTests(String directoryType, String decompressMode) {
        this.directoryType = directoryType;
        this.decompressMode = decompressMode;
    }

    public void testDecompress() throws Exception {
        var bench = new ZstdDecompressBenchmark();
        bench.directoryType = directoryType;
        bench.blockSize = 4096;
        bench.decompressMode = decompressMode;
        bench.setup();
        try {
            byte[] result = bench.decompressAll();
            assertNotNull(result);
            int expectedLen = decompressMode.equals("FULL") ? 4096 : 4096 / 2;
            assertTrue("result buffer should be at least " + expectedLen, result.length >= expectedLen);
        } finally {
            bench.tearDown();
        }
    }

    @ParametersFactory
    public static Iterable<Object[]> parametersFactory() {
        List<String> dirTypes = List.of("NIOFS", "MMAP", "SNAP");
        List<String> modes = List.of("FULL", "SLICE");
        return () -> dirTypes.stream().flatMap(d -> modes.stream().map(m -> new Object[] { d, m })).iterator();
    }
}
