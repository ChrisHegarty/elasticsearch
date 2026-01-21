/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash.xxhash;


import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

public final class XxHash64 {

    // ================== Constants ==================
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_256; // TODO PECIES_PREFERRED;
    private static final LongVector PRIME1 = LongVector.broadcast(SPECIES, PRIME64_1);
    private static final LongVector PRIME2 = LongVector.broadcast(SPECIES, PRIME64_2);

    private XxHash64() {}

    // Folding to 32 bits - TODO move this out of here
    public static int hash32(long h64) {
        return Long.hashCode(h64);
    }

    public static long hash(byte[] data, int offset, int length) {
        return hash(data, offset, length, 0L);
    }

    public static long hash(byte[] data, int offset, int length, long seed) {
        int index = offset;
        int end = offset + length;
        long hash;

        if (length >= 32) {
            // ---- init state ----
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;

            LongVector acc = LongVector.fromArray(
                SPECIES,
                new long[]{v1, v2, v3, v4},
                0
            );

            int limit = end - 32;

            // ---- main vectorized loop (32 bytes / iter) ----
            while (index <= limit) {
                LongVector input = LongVector.fromMemorySegment(
                    SPECIES,
                    MemorySegment.ofArray(data),
                    index,
                    ByteOrder.LITTLE_ENDIAN);

                acc = round(acc, input);
                index += 32;
            }

            // ---- merge state ----
            hash =
                Long.rotateLeft(acc.lane(0), 1)
                    + Long.rotateLeft(acc.lane(1), 7)
                    + Long.rotateLeft(acc.lane(2), 12)
                    + Long.rotateLeft(acc.lane(3), 18);

            hash = mergeRound(hash, acc.lane(0));
            hash = mergeRound(hash, acc.lane(1));
            hash = mergeRound(hash, acc.lane(2));
            hash = mergeRound(hash, acc.lane(3));
        } else {
            hash = seed + PRIME64_5;
        }

        hash += length;

        // ================== Tail ==================

        while (index + 8 <= end) {
            long k1 = readLongLE(data, index);
            k1 *= PRIME64_2;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= PRIME64_1;
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * PRIME64_1 + PRIME64_4;
            index += 8;
        }

        if (index + 4 <= end) {
            hash ^= (readIntLE(data, index) & 0xFFFFFFFFL) * PRIME64_1;
            hash = Long.rotateLeft(hash, 23) * PRIME64_2 + PRIME64_3;
            index += 4;
        }

        while (index < end) {
            hash ^= (data[index] & 0xFFL) * PRIME64_5;
            hash = Long.rotateLeft(hash, 11) * PRIME64_1;
            index++;
        }

        // ================== Final avalanche ==================
        hash ^= hash >>> 33;
        hash *= PRIME64_2;
        hash ^= hash >>> 29;
        hash *= PRIME64_3;
        hash ^= hash >>> 32;

        return hash;
    }

    // ================== Helpers ==================

    private static LongVector round(LongVector acc, LongVector input) {
        acc = acc.add(input.mul(PRIME2));
        acc = rotl(acc, 31);
        acc = acc.mul(PRIME1);
        return acc;
    }

    private static long mergeRound(long hash, long v) {
        v *= PRIME64_2;
        v = Long.rotateLeft(v, 31);
        v *= PRIME64_1;
        hash ^= v;
        hash = hash * PRIME64_1 + PRIME64_4;
        return hash;
    }

    private static LongVector rotl(LongVector v, int bits) {
        return v.lanewise(VectorOperators.LSHL, bits)
            .or(v.lanewise(VectorOperators.LSHR, 64 - bits));
    }

    private static long readLongLE(byte[] data, int offset) {
        return ((long)data[offset] & 0xFF)
            | (((long)data[offset + 1] & 0xFF) << 8)
            | (((long)data[offset + 2] & 0xFF) << 16)
            | (((long)data[offset + 3] & 0xFF) << 24)
            | (((long)data[offset + 4] & 0xFF) << 32)
            | (((long)data[offset + 5] & 0xFF) << 40)
            | (((long)data[offset + 6] & 0xFF) << 48)
            | (((long)data[offset + 7] & 0xFF) << 56);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }
}

