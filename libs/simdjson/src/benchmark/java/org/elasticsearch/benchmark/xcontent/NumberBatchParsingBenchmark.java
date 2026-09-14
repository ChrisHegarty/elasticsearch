/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.simdjson.NumberBatchParser;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * H11 spike (see {@code SIMDJSON_MAP_EVAL.md}): is it worth folding number parsing into a native
 * batched call, given the Java side ({@code DoubleParser} / the SWAR integer loop in {@code
 * SimdJsonDirectWalker}) already ports the identical fast-path algorithms simdjson's own native
 * stage 2 uses? This isolates *just* the number-parsing arithmetic — no strings, no structure, no
 * boxing/{@code Map} building — from a large batch of realistic-shaped numbers (small ints and
 * few-decimal doubles, matching e.g. {@code clickbench_flat}'s numeric fields), comparing:
 *
 * <ul>
 *   <li>{@link #javaParse} — the real production Java path: the exact SWAR digit-widening
 *       integer loop from {@code SimdJsonDirectWalker.handleNumber}, and the real {@code
 *       DoubleParser} (fast path + slow path) for floats. One call per number, as today.</li>
 *   <li>{@link #nativeBatchParse} — {@link NumberBatchParser}, one native call for the *entire*
 *       batch (matching the per-document, not per-token, cost-amortization discipline the H9
 *       finding established as essential). Fast-path only; every number generated here is
 *       constructed to be within that fast path, so this is a fair like-for-like comparison, not
 *       one side doing less work.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :libs:simdjson:benchmark --args "NumberBatchParsingBenchmark -rf json -rff build/jmh-numbers.json"
 * }</pre>
 */
@Fork(value = 1, jvmArgsAppend = { "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED" })
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class NumberBatchParsingBenchmark {

    private static final int COUNT = 5000;

    @Param({ "ints", "doubles", "mixed" })
    private String shape;

    private byte[] buffer;
    private int[] offsets;
    private byte[] outTypes;
    private long[] outBits;
    private int[] outLens;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkLogging.configure();
        Random random = new Random(42);
        StringBuilder sb = new StringBuilder();
        offsets = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            offsets[i] = sb.length();
            appendNumber(sb, random);
            sb.append(',');
        }
        buffer = sb.toString().getBytes(StandardCharsets.UTF_8);
        outTypes = new byte[COUNT];
        outBits = new long[COUNT];
        outLens = new int[COUNT];

        if (NumberBatchParser.isAvailable() == false) {
            throw new IllegalStateException("native simdjson library not available - see SIMDJSON_MAP_EVAL.md H11");
        }
        selfCheck();
    }

    /** Verifies javaParse and nativeBatchParse agree on every generated number before benchmarking either. */
    private void selfCheck() {
        NumberBatchParser.parse(buffer, buffer.length, offsets, COUNT, outTypes, outBits, outLens);
        JavaNumberParser javaParser = new JavaNumberParser();
        for (int i = 0; i < COUNT; i++) {
            if (outTypes[i] == NumberBatchParser.TYPE_NEEDS_FALLBACK) {
                throw new IllegalStateException("generated a number outside the native fast path at index " + i);
            }
            javaParser.parse(buffer, offsets[i]);
            boolean javaIsDouble = javaParser.lastIsDouble;
            boolean nativeIsDouble = outTypes[i] == NumberBatchParser.TYPE_DOUBLE;
            if (javaIsDouble != nativeIsDouble) {
                throw new IllegalStateException("type mismatch at index " + i);
            }
            if (javaIsDouble) {
                if (Double.longBitsToDouble(outBits[i]) != javaParser.lastDoubleValue) {
                    throw new IllegalStateException("double value mismatch at index " + i);
                }
            } else if (outBits[i] != javaParser.lastLongValue) {
                throw new IllegalStateException("long value mismatch at index " + i);
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // no-op; buffers are Trial-scoped and GC'd with the benchmark state.
    }

    private void appendNumber(StringBuilder sb, Random random) {
        boolean asDouble = switch (shape) {
            case "ints" -> false;
            case "doubles" -> true;
            default -> random.nextBoolean(); // "mixed"
        };
        boolean negative = random.nextBoolean();
        if (negative) sb.append('-');
        if (asDouble) {
            // few-decimal doubles, matching realistic prices/percentages/metrics - well within
            // the native fast path's <=19 significant digits / |exp10|<=22 scope.
            long whole = random.nextInt(1_000_000);
            int frac = random.nextInt(1000);
            sb.append(whole).append('.').append(String.format("%03d", frac));
        } else {
            sb.append(random.nextInt(1_000_000_000));
        }
    }

    @Benchmark
    @OperationsPerInvocation(COUNT)
    public void javaParse(Blackhole bh) {
        JavaNumberParser parser = new JavaNumberParser();
        for (int i = 0; i < COUNT; i++) {
            parser.parse(buffer, offsets[i]);
            bh.consume(parser.lastIsDouble ? Double.doubleToRawLongBits(parser.lastDoubleValue) : parser.lastLongValue);
        }
    }

    @Benchmark
    @OperationsPerInvocation(COUNT)
    public void nativeBatchParse(Blackhole bh) {
        NumberBatchParser.parse(buffer, buffer.length, offsets, COUNT, outTypes, outBits, outLens);
        bh.consume(outBits);
        bh.consume(outTypes);
    }
}
