/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash.xxhash;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

import org.elasticsearch.test.ESTestCase;


import static java.nio.charset.StandardCharsets.UTF_8;

public class XxHash64Tests extends ESTestCase {

    private static final XXHash64 REF =
        XXHashFactory.fastestInstance().hash64();

    public void testEmpty() {
        byte[] data = new byte[0];
        long h1 = XxHash64.hash(data, 0, 0);
        long h2 = REF.hash(data, 0, 0, 0L);
        assertEquals(h2, h1);
    }

    public void testSmallStrings() {
        testString("");
        testString("a");
        testString("hello");
        testString("The quick brown fox jumps over the lazy dog");
    }

    private void testString(String s) {
        byte[] data = s.getBytes(UTF_8);
        long expected = REF.hash(data, 0, data.length, 0L);
        long actual = XxHash64.hash(data, 0, data.length);
        assertEquals(expected, actual);
    }

    public void testOffsets() {
        byte[] buf = new byte[64];
        byte[] payload = "offset-test".getBytes(UTF_8);
        System.arraycopy(payload, 0, buf, 13, payload.length);
        long expected = REF.hash(buf, 13, payload.length, 0L);
        long actual = XxHash64.hash(buf, 13, payload.length);
        assertEquals(expected, actual);
    }

    public void testRandomData() {
        int size = randomIntBetween(0, 128_000);;
        byte[] data = new byte[size];

        for (int i = 0; i < 100; i++) {
            random().nextBytes(data);
            long expected = REF.hash(data, 0, data.length, 0L);
            long actual = XxHash64.hash(data, 0, data.length);
            assertEquals(expected, actual);
        }
    }

    public void testFoldTo32Bit() {
        byte[] data = "fold-test".getBytes(UTF_8);
        long h64 = XxHash64.hash(data, 0, data.length);
        int folded = (int)(h64 ^ (h64 >>> 32));
        // sanity checks
        assertEquals(folded, XxHash64.hash32(h64));
    }
}
