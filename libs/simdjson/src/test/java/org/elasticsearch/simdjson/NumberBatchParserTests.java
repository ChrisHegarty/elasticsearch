/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdjson;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * H11 spike correctness check for {@link NumberBatchParser} (see {@code SIMDJSON_MAP_EVAL.md}).
 * Not testing production code — {@link NumberBatchParser} is a benchmark-only prototype, not
 * wired into any shipped path.
 */
public class NumberBatchParserTests extends SimdJsonTestCase {

    @Override
    protected NativeRequirement nativeRequirement() {
        return NativeRequirement.LIBRARY;
    }

    public void testFastPathValues() {
        List<String> numbers = List.of(
            "0",
            "1",
            "-1",
            "123",
            "-123456789",
            "9999999999999999", // 16 nines, well within fast-path int range
            "0.5",
            "-0.5",
            "3.14159",
            "45.678",
            "-45.678",
            "1.5e10",
            "1.5e-10",
            "2e5",
            "0.0",
            "100000000000000000", // 18 digits, still fast-path
            "-999999999999999999" // 18 sig digits + sign, still fast-path
        );
        assertBatchMatchesJava(numbers);
    }

    public void testFallbackCasesReportNeedsFallback() {
        // > 19 digits and > 22 decimal exponent are outside the deliberately-scoped fast path.
        List<String> numbers = List.of(
            "99999999999999999999", // 20 digits
            "1.23456789012345678901234", // 24 significant digits
            "1e50",
            "1e-50"
        );
        byte[] buf = buildBuffer(numbers);
        int[] offsets = computeNumberOffsets(numbers, buf);
        byte[] outTypes = new byte[numbers.size()];
        long[] outBits = new long[numbers.size()];
        int[] outLens = new int[numbers.size()];

        NumberBatchParser.parse(buf, buf.length, offsets, numbers.size(), outTypes, outBits, outLens);

        for (int i = 0; i < numbers.size(); i++) {
            assertEquals("expected NEEDS_FALLBACK for [" + numbers.get(i) + "]", NumberBatchParser.TYPE_NEEDS_FALLBACK, outTypes[i]);
        }
    }

    private void assertBatchMatchesJava(List<String> numbers) {
        byte[] buf = buildBuffer(numbers);
        int[] offsets = computeNumberOffsets(numbers, buf);
        byte[] outTypes = new byte[numbers.size()];
        long[] outBits = new long[numbers.size()];
        int[] outLens = new int[numbers.size()];

        NumberBatchParser.parse(buf, buf.length, offsets, numbers.size(), outTypes, outBits, outLens);

        for (int i = 0; i < numbers.size(); i++) {
            String number = numbers.get(i);
            boolean isFloat = number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0;
            assertEquals("wrong consumed length for [" + number + "]", number.length(), outLens[i]);
            if (isFloat) {
                assertEquals("wrong type for [" + number + "]", NumberBatchParser.TYPE_DOUBLE, outTypes[i]);
                double expected = Double.parseDouble(number);
                double actual = Double.longBitsToDouble(outBits[i]);
                assertEquals("wrong value for [" + number + "]", expected, actual, 0.0);
            } else {
                assertEquals("wrong type for [" + number + "]", NumberBatchParser.TYPE_INT64, outTypes[i]);
                long expected = Long.parseLong(number);
                assertEquals("wrong value for [" + number + "]", expected, outBits[i]);
            }
        }
    }

    /** Builds a buffer with each number followed by a comma, matching real JSON number termination. */
    private static byte[] buildBuffer(List<String> numbers) {
        StringBuilder sb = new StringBuilder();
        for (String n : numbers) {
            sb.append(n).append(',');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int[] computeNumberOffsets(List<String> numbers, byte[] buf) {
        int[] offsets = new int[numbers.size()];
        int pos = 0;
        for (int i = 0; i < numbers.size(); i++) {
            offsets[i] = pos;
            pos += numbers.get(i).length() + 1; // +1 for the trailing comma
        }
        return offsets;
    }
}
