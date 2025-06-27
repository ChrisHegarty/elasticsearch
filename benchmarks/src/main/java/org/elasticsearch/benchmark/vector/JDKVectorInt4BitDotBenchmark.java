/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.benchmark.vector;

import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.quantization.OptimizedScalarQuantizer;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.logging.NodeNamePatternConverter;
import org.elasticsearch.nativeaccess.NativeAccess;
import org.elasticsearch.nativeaccess.VectorSimilarityFunctions;
import org.elasticsearch.simdvec.ES91OSQVectorsScorer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class JDKVectorInt4BitDotBenchmark {

    static {
        NodeNamePatternConverter.setGlobalNodeName("foo");
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging(); // native access requires logging to be initialized
    }

    int numVectors = ES91OSQVectorsScorer.BULK_SIZE;
    byte[] docVectors;
    byte[][] docVectorsArray;
    byte[] queryVector;
    int[] scores;
    int[] scratchScores;

    MemorySegment docVectorsHeapSeg, queryVectorHeapSeg, scoresHeapSeg;
//  MemorySegment nativeSegA, nativeSegB;
    Arena arena;

    @Param({ "1024" })
    public int size;

    @Setup(Level.Iteration)
    public void init() throws Exception {
        Random random = new Random(123);
        // length = OptimizedScalarQuantizer.discretize(size, 64) / 8;

        docVectors = new byte[(numVectors * size) / 8];
        random.nextBytes(docVectors);

        docVectorsArray = new byte[numVectors][];
        for (int i = 0; i < numVectors; i++) {
            int offset = (i * size) / 8;
            docVectorsArray[i] = Arrays.copyOfRange(docVectors, offset, offset + (size / 8));
        }

        queryVector = new byte[size / 2]; // 2 dims per byte
        random.nextBytes(queryVector);

        scores = new int[16];
        scratchScores = new int[16];

        docVectorsHeapSeg = MemorySegment.ofArray(docVectors);
        queryVectorHeapSeg = MemorySegment.ofArray(queryVector);
        scoresHeapSeg = MemorySegment.ofArray(scores);

        arena = Arena.ofConfined();
    }

    @TearDown
    public void teardown() {
        arena.close();
    }

    @Benchmark
    @Fork(value = 3, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
    public int[] int4BitDotProductLucene() {
        for (int i = 0; i < numVectors; i++) {
            scratchScores[i] = (int) VectorUtil.int4BitDotProduct(queryVector, docVectorsArray[i]);
        }
        return scratchScores;
    }

    @Benchmark
    @Fork(value = 3, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
    public int int4BitDotProductWithNativeSeg() {
        return int4BitDotProduct(queryVectorHeapSeg, docVectorsHeapSeg, 0, scoresHeapSeg, 16, size);
    }

    static final VectorSimilarityFunctions vectorSimilarityFunctions = vectorSimilarityFunctions();

    static VectorSimilarityFunctions vectorSimilarityFunctions() {
        return NativeAccess.instance().getVectorSimilarityFunctions().get();
    }

    int int4BitDotProduct(MemorySegment query, MemorySegment doc, long offset, MemorySegment scores, int count, int dims) {
        try {
            return (int) vectorSimilarityFunctions.int4BitDotProductHandle().invokeExact(query, doc, offset, scores, count, dims);
        } catch (Throwable e) {
            if (e instanceof Error err) {
                throw err;
            } else if (e instanceof RuntimeException re) {
                throw re;
            } else {
                throw new RuntimeException(e);
            }
        }
    }
}
