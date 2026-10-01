/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector.scorer;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.UpdateableRandomVectorScorer;
import org.elasticsearch.benchmark.vector.VectorImplementation;
import org.elasticsearch.index.codec.vectors.VectorTestUtils;
import org.elasticsearch.simdvec.VectorScorerFactory;
import org.elasticsearch.simdvec.VectorSimilarityType;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.floatVectorValues;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.getScorerFactoryOrDie;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.luceneScoreSupplier;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.luceneScorer;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.panamaScoreSupplier;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.panamaScorer;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.supportsHeapSegments;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.writeFloatVectorData;
import static org.elasticsearch.simdvec.ScalarOperations.dotProduct;
import static org.elasticsearch.simdvec.ScalarOperations.squareDistance;

public class VectorScorerFloat32BulkBenchmark extends VectorScorerBulkBenchmark {

    // With dims=1024, each vector is 4KB. Target cache/page-cache overflow points:
    // 32 vectors = 128KB: fits comfortably in L1/L2
    // 375 vectors = 1.5MB: overflows L1/L2, fits in L3
    // 32500 vectors = ~127MB: overflows L3
    // 2000000 vectors = ~7.6GB: approaches/exceeds typical page-cache and RAM budgets, forcing
    // genuine scattered mmap page-ins rather than cache-resident access (see #setup()).
    @Param({ "32", "375", "32500", "2000000" })
    public int numVectors;

    @Param
    public VectorImplementation implementation;

    @Param({ "DOT_PRODUCT", "EUCLIDEAN" })
    public VectorSimilarityType function;

    private static class ScalarDotProduct implements UpdateableRandomVectorScorer {
        private final FloatVectorValues values;

        private float[] queryVector;

        private ScalarDotProduct(FloatVectorValues values) {
            this.values = values;
        }

        @Override
        public float score(int ordinal) throws IOException {
            return VectorUtil.normalizeToUnitInterval(dotProduct(queryVector, values.vectorValue(ordinal)));
        }

        @Override
        public int maxOrd() {
            return 0;
        }

        @Override
        public void setScoringOrdinal(int targetOrd) throws IOException {
            queryVector = values.vectorValue(targetOrd).clone();
        }
    }

    private static class ScalarSquareDistance implements UpdateableRandomVectorScorer {
        private final FloatVectorValues values;

        private float[] queryVector;

        private ScalarSquareDistance(FloatVectorValues values) {
            this.values = values;
        }

        @Override
        public float score(int ordinal) throws IOException {
            return VectorUtil.normalizeDistanceToUnitInterval(squareDistance(queryVector, values.vectorValue(ordinal)));
        }

        @Override
        public int maxOrd() {
            return 0;
        }

        @Override
        public void setScoringOrdinal(int targetOrd) throws IOException {
            queryVector = values.vectorValue(targetOrd).clone();
        }
    }

    static class VectorData extends VectorScorerBulkBenchmark.VectorData {
        private final int dims;
        private final int numVectors;
        // Captured rather than consuming `random` directly, so writeVectorData can be called more
        // than once (e.g. once per implementation under test, see BenchmarkTest) and regenerate the
        // exact same bytes each time without ever materializing the whole dataset as a float[][] --
        // the latter doesn't scale once numVectors is large enough to need a many-GB backing file.
        private final long seed;
        private final float[] queryVector;

        VectorData(int dims, int numVectors, int numVectorsToScore, Random random, DataAccessPattern accessMode) {
            super(numVectors, numVectorsToScore, random, accessMode);
            this.dims = dims;
            this.numVectors = numVectors;
            this.seed = random.nextLong();
            queryVector = VectorTestUtils.randomFloatVector(random, dims);
        }

        @Override
        void writeVectorData(Directory directory) throws IOException {
            writeFloatVectorData(directory, dims, numVectors, new Random(seed));
        }
    }

    // Each op scores up to this many vectors, in random (shuffled) order, uniformly sampled from
    // the full numVectors range. For the large-numVectors tier, scoring the *entire* multi-GB
    // dataset on every single op makes one op take minutes (dominated by real page faults), which
    // defeats JMH's time-based iteration control. Capping at a value still much larger than the
    // smaller tiers keeps per-op time tractable while remaining a broad, uniformly scattered sample
    // across the whole backing file -- not a small subset that would get fully page-cache-resident
    // after the first iteration.
    private static final int MAX_VECTORS_TO_SCORE = 100_000;

    @Setup
    public void setup() throws IOException {
        setup(new VectorData(dims, numVectors, Math.min(numVectors, MAX_VECTORS_TO_SCORE), ThreadLocalRandom.current(), accessMode));
    }

    void setup(VectorData vectorData) throws IOException {
        setup(vectorData, numVectors);
    }

    @Override
    void createScorers(IndexInput in, VectorScorerBulkBenchmark.VectorData vectorData) throws IOException {
        VectorScorerFactory factory = getScorerFactoryOrDie();
        var values = floatVectorValues(dims, numVectors, in, function.function());

        switch (implementation) {
            case SCALAR:
                scorer = switch (function) {
                    case DOT_PRODUCT -> new ScalarDotProduct(values);
                    case EUCLIDEAN -> new ScalarSquareDistance(values);
                    default -> throw new IllegalArgumentException(function + " not supported");
                };
                break;
            case LUCENE:
                scorer = luceneScoreSupplier(values, function.function()).scorer();
                if (supportsHeapSegments()) {
                    queryScorer = luceneScorer(values, function.function(), ((VectorData) vectorData).queryVector);
                }
                break;
            case PANAMA:
                scorer = panamaScoreSupplier(values, function.function()).scorer();
                if (supportsHeapSegments()) {
                    queryScorer = panamaScorer(values, function.function(), ((VectorData) vectorData).queryVector);
                }
                break;
            case NATIVE:
                scorer = factory.getFloat32VectorScorerSupplier(function, in, values).orElseThrow().scorer();
                if (supportsHeapSegments()) {
                    queryScorer = factory.getFloat32VectorScorer(function.function(), values, ((VectorData) vectorData).queryVector)
                        .orElseThrow();
                }
                break;
        }
    }
}
