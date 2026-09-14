/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdjson;

import org.elasticsearch.simdjson.internal.SimdJsonNativeSupport;

import java.lang.foreign.MemorySegment;

/**
 * H11 spike (see {@code SIMDJSON_MAP_EVAL.md}): thin Java entry point over the native
 * {@code simdjson_parse_numbers_batch} function, which parses many JSON numbers in one native
 * call given their start offsets in a shared source buffer. Exists to measure whether native
 * arithmetic beats {@link org.elasticsearch.simdjson.internal.parsers.DoubleParser} / the SWAR
 * integer parsing in {@link SimdJsonDirectWalker} for the identical fast-path algorithm — see the
 * native function's doc comment in {@code es_simdjson.cpp} for exactly what it covers and what it
 * doesn't (anything outside the fast path reports {@link #TYPE_NEEDS_FALLBACK}).
 *
 * <p>Benchmark-only: not wired into {@link SimdJsonDirectWalker} or any production path.
 */
public final class NumberBatchParser {

    public static final byte TYPE_INT64 = 0;
    public static final byte TYPE_DOUBLE = 1;
    public static final byte TYPE_NEEDS_FALLBACK = 2;

    private NumberBatchParser() {}

    public static boolean isAvailable() {
        return SimdJsonNativeSupport.isLoaded();
    }

    /**
     * Parses the numbers starting at {@code numberOffsets[0..count)} within {@code buf[0..len)}.
     * Results are written index-for-index into {@code outTypes}/{@code outBits}/{@code outLens}
     * (each must have length &gt;= {@code count}). See {@link #TYPE_INT64}/{@link #TYPE_DOUBLE}/
     * {@link #TYPE_NEEDS_FALLBACK} for how to interpret {@code outTypes}/{@code outBits}.
     */
    public static void parse(byte[] buf, int len, int[] numberOffsets, int count, byte[] outTypes, long[] outBits, int[] outLens) {
        int rc = SimdJsonNativeSupport.library()
            .parseNumbersBatch(
                MemorySegment.ofArray(buf),
                len,
                MemorySegment.ofArray(numberOffsets),
                count,
                MemorySegment.ofArray(outTypes),
                MemorySegment.ofArray(outBits),
                MemorySegment.ofArray(outLens)
            );
        if (rc != 0) {
            throw new IllegalStateException("simdjson_parse_numbers_batch failed: " + rc);
        }
    }
}
