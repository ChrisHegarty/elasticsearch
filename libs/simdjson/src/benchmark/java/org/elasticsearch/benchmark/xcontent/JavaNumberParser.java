/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.simdjson.internal.parsers.DoubleParser;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * H11 spike helper: a verbatim copy of the number-parsing logic in {@code
 * SimdJsonDirectWalker.handleNumber}/{@code handleFloatingPoint}/{@code parse8Digits} - the real
 * production Java path (SWAR digit-widening for integers, the real {@link DoubleParser} for
 * floats) - extracted so {@link NumberBatchParsingBenchmark} can measure it in isolation, without
 * the surrounding object-walk/handler machinery. See {@code SIMDJSON_MAP_EVAL.md} H11.
 */
final class JavaNumberParser {

    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final DoubleParser doubleParser = new DoubleParser();

    boolean lastIsDouble;
    long lastLongValue;
    double lastDoubleValue;

    void parse(byte[] buffer, int idx) {
        boolean negative = buffer[idx] == '-';
        int pos = negative ? idx + 1 : idx;

        long digits = 0;
        int digitStart = pos;
        int loopBound = buffer.length - 8;

        while (pos <= loopBound) {
            long word = (long) LONG_LE.get(buffer, pos);
            long t = word - 0x3030303030303030L;
            if ((t & 0xF0F0F0F0F0F0F0F0L) != 0) {
                break;
            }
            digits = digits * 100_000_000L + parse8Digits(t);
            pos += 8;
        }
        byte ch = buffer[pos];
        while (ch >= '0' && ch <= '9') {
            digits = digits * 10 + (ch - '0');
            ch = buffer[++pos];
        }

        if (ch == '.' || ch == 'e' || ch == 'E') {
            parseFloatingPoint(buffer, idx, negative, digits, pos);
            return;
        }

        long val = negative ? -digits : digits;
        lastIsDouble = false;
        lastLongValue = val;
    }

    private void parseFloatingPoint(byte[] buffer, int startIdx, boolean negative, long intDigits, int pos) {
        int digitsStartIdx = negative ? startIdx + 1 : startIdx;
        long digits = intDigits;
        long exponent = 0;
        int digitCountEnd = pos;

        if (buffer[pos] == '.') {
            pos++;
            int fracStart = pos;
            byte ch = buffer[pos];
            while (ch >= '0' && ch <= '9') {
                digits = digits * 10 + (ch - '0');
                ch = buffer[++pos];
            }
            exponent = fracStart - pos;
            digitCountEnd = pos;
        }

        if (buffer[pos] == 'e' || buffer[pos] == 'E') {
            pos++;
            boolean expNeg = false;
            if (buffer[pos] == '-') {
                expNeg = true;
                pos++;
            } else if (buffer[pos] == '+') {
                pos++;
            }
            long exp = 0;
            byte ch = buffer[pos];
            while (ch >= '0' && ch <= '9') {
                exp = exp * 10 + (ch - '0');
                ch = buffer[++pos];
            }
            exponent += expNeg ? -exp : exp;
        }

        int digitCount = digitCountEnd - digitsStartIdx;
        double val = doubleParser.parse(buffer, startIdx, negative, digitsStartIdx, digitCount, digits, exponent);
        lastIsDouble = true;
        lastDoubleValue = val;
    }

    /**
     * Converts 8 pre-validated digit bytes (each 0x00..0x09) packed in a little-endian long into
     * their decimal value, matching {@code SimdJsonDirectWalker.parse8Digits}.
     */
    private static long parse8Digits(long t) {
        long m1 = (t & 0x00FF00FF00FF00FFL) * 10 + ((t >>> 8) & 0x00FF00FF00FF00FFL);
        long m2 = (m1 & 0x0000FFFF0000FFFFL) * 100 + ((m1 >>> 16) & 0x0000FFFF0000FFFFL);
        long m3 = (m2 & 0xFFFFFFFFL) * 10000 + (m2 >>> 32);
        return m3;
    }
}
