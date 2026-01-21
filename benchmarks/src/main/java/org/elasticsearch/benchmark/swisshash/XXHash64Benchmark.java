/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.swisshash;

//import com.google.common.hash.HashFunction;

import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.swisshash.BitMixer;
import org.elasticsearch.swisshash.xxhash.XxHash64;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@State(Scope.Thread)
public class XXHash64Benchmark {

    static {
        LogConfigurator.configureESLogging(); // native access requires logging to be initialized
    }

    private static final XXHash64 JP_X64 = XXHashFactory.fastestInstance().hash64();
    private static final XXHash32 JP_X32 = XXHashFactory.fastestInstance().hash32();
    //static final HashFunction GA_x32 = com.google.common.hash.Hashing.goodFastHash(32);

    @Param({ "8", "16", "32", "64", "128", "256" })
    public int size;

    private BytesRef bytesRef;

    @Setup
    public void setup() {
        System.out.println("HEGO JP_X64=" + JP_X64);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) i;
        }
        bytesRef = new BytesRef(data);
    }

    // ---------------- Murmur3 ----------------

    @Benchmark
    public int murmur3_32() {
        return BitMixer.mix(bytesRef.hashCode());
    }

    @Benchmark
    public int murmur3_32_guavaLike() {
        return GuavaLikeHash.hashBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
    }

    // ---------------- jpountz xxHash64 ----------------

    @Benchmark
    public int xxhash32_jpountz() {
        return JP_X32.hash(bytesRef.bytes, bytesRef.offset, bytesRef.length, 0);
    }

    @Benchmark
    public long xxhash64_jpountz() {
        return JP_X64.hash(bytesRef.bytes, bytesRef.offset, bytesRef.length, 0L);
    }

//    @Benchmark
//    public int xxhash64_jpountz_folded() {
//        long h = JP_X64.hash(bytesRef.bytes, bytesRef.offset, bytesRef.length, 0L);
//        return (int)(h ^ (h >>> 32));
//    }

    // ---------------- Vector API xxHash64 ----------------

//    @Benchmark
//    public long xxhash64_vector() {
//        return XxHash64.hash(bytesRef.bytes, bytesRef.offset, bytesRef.length);
//    }
//
//    @Benchmark
//    public int xxhash64_vector_folded() {
//        long h64 = XxHash64.hash(bytesRef.bytes, bytesRef.offset, bytesRef.length);
//        return XxHash64.hash32(h64);
//    }

    static class GuavaLikeHash {

        private static final int CHUNK_SIZE = 4;
        private static final int C1 = 0xcc9e2d51;
        private static final int C2 = 0x1b873593;
        private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);


        public static int hashBytes(byte[] input, int off, int len) {
            // checkPositionIndexes(off, off + len, input.length);
            int h1 = 0; // seed
            int i;
            for (i = 0; i + CHUNK_SIZE <= len; i += CHUNK_SIZE) {
                int k1 = mixK1(getIntLittleEndian(input, off + i));
                h1 = mixH1(h1, k1);
            }

            int k1 = 0;
            for (int shift = 0; i < len; i++, shift += 8) {
                k1 ^= Byte.toUnsignedInt(input[off + i]) << shift;
            }
            h1 ^= mixK1(k1);
            return fmix(h1, len);
        }

        private static int mixK1(int k1) {
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            return k1;
        }

        private static int mixH1(int h1, int k1) {
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
            return h1;
        }

        private static int getIntLittleEndian(byte[] input, int offset) {
            return (int) INT_HANDLE.get(input, offset);
        }

        private static int fmix(int h1, int length) {
            h1 ^= length;
            h1 ^= h1 >>> 16;
            h1 *= 0x85ebca6b;
            h1 ^= h1 >>> 13;
            h1 *= 0xc2b2ae35;
            h1 ^= h1 >>> 16;
            return h1;
        }
    }
}
