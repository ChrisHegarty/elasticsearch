/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.knn;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.knn.data.DataGenerator;
import org.elasticsearch.test.knn.data.NonPartitionDataGenerator;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;

public class GroundTruthTests extends ESTestCase {

    static final int NUM_DOCS = 500;
    static final int NUM_QUERIES = 10;
    static final int DIM = 32;
    static final int TOP_K = 10;

    /** Indexes random float32 vectors and verifies SinglePass and PerQuery return the same top-K doc IDs. */
    public void testSinglePassMatchesPerQueryFloat() throws Exception {
        assertSinglePassMatchesPerQueryFloat(randomSimilarity());
    }

    /** Same as the float test but with byte vectors, ensuring both strategies agree on byte-encoded data. */
    public void testSinglePassMatchesPerQueryByte() throws Exception {
        assertSinglePassMatchesPerQueryByte(randomSimilarity());
    }

    /** Times both strategies on 1M docs / 100 queries and writes elapsed ms to a file. Not a correctness test. */
    @AwaitsFix(bugUrl = "manual timing benchmark -- remove @AwaitsFix to run")
    public void testTimingSinglePassVsPerQuery1M() throws Exception {
        int numDocs = 1_000_000;
        int numQueries = 100;
        int dim = 128;
        int topK = 100;

        Path tempDir = createTempDir("ground-truth-timing");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        Path resultsFile = tempDir.resolve("timing-results.txt");

        long t0 = System.nanoTime();
        Random rng = new Random(42);
        writeRandomFloatVectors(rawVecFile, rng, numDocs, dim);
        float[][] queryVectors = generateFloatVectors(43, numQueries, dim);
        long dataGenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        t0 = System.nanoTime();
        buildLuceneIndexFromFile(indexDir, rawVecFile, numDocs, dim, DOT_PRODUCT);
        long indexBuildMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // -- SinglePass --
        DataGenerator dataGenSP = makeDataGenerator(new float[0][], queryVectors);
        GroundTruth singlePass = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, dim, DOT_PRODUCT);
        t0 = System.nanoTime();
        int[][] singlePassResult = singlePass.computeFloat(dataGenSP, topK);
        long singlePassMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // -- PerQuery --
        DataGenerator dataGenPQ = makeDataGenerator(new float[0][], queryVectors);
        GroundTruth perQuery = new GroundTruth.PerQuery(indexDir, numQueries, DOT_PRODUCT, null);
        t0 = System.nanoTime();
        int[][] perQueryResult = perQuery.computeFloat(dataGenPQ, topK);
        long perQueryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        String summary = String.format(
            "numDocs=%d  numQueries=%d  dim=%d  topK=%d%n"
                + "data generation: %d ms%n"
                + "index build:     %d ms%n"
                + "SinglePass:      %d ms%n"
                + "PerQuery:        %d ms%n"
                + "speedup:         %.1fx%n",
            numDocs,
            numQueries,
            dim,
            topK,
            dataGenMs,
            indexBuildMs,
            singlePassMs,
            perQueryMs,
            (double) perQueryMs / singlePassMs
        );
        Files.writeString(resultsFile, summary);

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    /** Writes random normalized float vectors directly to a file without holding them all in memory. */
    private static void writeRandomFloatVectors(Path path, Random rng, int count, int dim) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[dim];
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (int i = 0; i < count; i++) {
                for (int d = 0; d < dim; d++) {
                    vec[d] = rng.nextFloat() * 2 - 1;
                }
                VectorUtil.l2normalize(vec);
                buf.clear();
                buf.asFloatBuffer().put(vec);
                buf.position(0).limit(dim * Float.BYTES);
                ch.write(buf);
            }
        }
    }

    /** Builds a Lucene index by reading vectors from the raw file, avoiding holding all vectors in memory. */
    private static void buildLuceneIndexFromFile(Path indexDir, Path vecFile, int numDocs, int dim, VectorSimilarityFunction simFunc)
        throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (
            FileChannel ch = FileChannel.open(vecFile);
            Directory dir = FSDirectory.open(indexDir);
            IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig())
        ) {
            float[] vec = new float[dim];
            for (int i = 0; i < numDocs; i++) {
                buf.clear();
                ch.read(buf);
                buf.flip();
                buf.asFloatBuffer().get(vec);
                Document doc = new Document();
                doc.add(new KnnFloatVectorField(KnnIndexer.VECTOR_FIELD, vec.clone(), simFunc));
                doc.add(new StoredField(KnnIndexer.ID_FIELD, i));
                iw.addDocument(doc);
                if ((i + 1) % 100_000 == 0) {
                    iw.commit();
                }
            }
            iw.commit();
        }
    }

    private void assertSinglePassMatchesPerQueryFloat(VectorSimilarityFunction simFunc) throws Exception {
        long seed = randomLong();
        Path tempDir = createTempDir("gt-simfunc");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, NUM_DOCS, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, NUM_QUERIES, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, NUM_QUERIES, simFunc, null).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), NUM_DOCS, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        assertResultsEqual(perQueryResult, singlePassResult);
    }

    private void assertSinglePassMatchesPerQueryByte(VectorSimilarityFunction simFunc) throws Exception {
        long seed = randomLong();
        Path tempDir = createTempDir("gt-simfunc-byte");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        byte[][] docVectors = generateByteVectors(seed, NUM_DOCS, DIM);
        byte[][] queryVectors = generateByteVectors(seed + 1, NUM_QUERIES, DIM);

        writeRawByteVectors(rawVecFile, docVectors);
        buildLuceneByteIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, NUM_QUERIES, simFunc, null).computeByte(
            makeByteDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), NUM_DOCS, DIM, simFunc).computeByte(
            makeByteDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        assertResultsEqual(perQueryResult, singlePassResult);
    }

    // ----- P0: Factory method -----

    public void testCreateSelectsSinglePassByDefault() {
        Path dummyIndex = createTempDir("factory-test");
        GroundTruth gt = GroundTruth.create(List.of(dummyIndex.resolve("docs.bin")), dummyIndex, 100, 10, 32, DOT_PRODUCT, null);
        assertThat(gt, Matchers.instanceOf(GroundTruth.SinglePass.class));
    }

    public void testCreateSelectsSinglePassWithFilter() {
        Path dummyIndex = createTempDir("factory-filter-test");
        GroundTruth gt = GroundTruth.create(
            List.of(dummyIndex.resolve("docs.bin")),
            dummyIndex,
            100,
            10,
            32,
            DOT_PRODUCT,
            new org.apache.lucene.search.MatchAllDocsQuery()
        );
        assertThat(gt, Matchers.instanceOf(GroundTruth.SinglePass.class));
    }

    // ----- Filtered ground truth -----

    public void testSinglePassWithFilterMatchesPerQuery() throws Exception {
        assertSinglePassWithFilterMatchesPerQueryFloat(randomSimilarity());
    }

    private void assertSinglePassWithFilterMatchesPerQueryFloat(VectorSimilarityFunction simFunc) throws Exception {
        long seed = randomLong();
        int numDocs = 100;
        int numQueries = 5;
        int topK = 5;
        float selectivity = 0.5f;
        Path tempDir = createTempDir("gt-filtered");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, numQueries, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);
        Query filter = KnnSearcher.generateRandomQuery(new Random(seed), indexDir, numDocs, selectivity, false);

        int[][] perQueryResult = computeFilteredPerQueryFloat(indexDir, numQueries, topK, filter, simFunc, docVectors, queryVectors);
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, DIM, simFunc, indexDir, filter).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            topK
        );

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    // ----- Multi-file doc splits -----

    public void testSinglePassMultiFileDocSplit() throws Exception {
        long seed = randomLong();
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 100;
        int splitAt = 37;
        Path tempDir = createTempDir("gt-multi-file");
        Path file1 = tempDir.resolve("docs1.bin");
        Path file2 = tempDir.resolve("docs2.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, NUM_QUERIES, DIM);

        writeRawFloatVectors(file1, Arrays.copyOfRange(docVectors, 0, splitAt));
        writeRawFloatVectors(file2, Arrays.copyOfRange(docVectors, splitAt, numDocs));
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, NUM_QUERIES, simFunc, null).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(file1, file2), numDocs, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    public void testSinglePassThreeFileSplit() throws Exception {
        long seed = randomLong();
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 90;
        Path tempDir = createTempDir("gt-three-file");
        Path file1 = tempDir.resolve("docs1.bin");
        Path file2 = tempDir.resolve("docs2.bin");
        Path file3 = tempDir.resolve("docs3.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, 5, DIM);

        writeRawFloatVectors(file1, Arrays.copyOfRange(docVectors, 0, 20));
        writeRawFloatVectors(file2, Arrays.copyOfRange(docVectors, 20, 55));
        writeRawFloatVectors(file3, Arrays.copyOfRange(docVectors, 55, 90));
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, 5, simFunc, null).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(file1, file2, file3), numDocs, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            TOP_K
        );

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    // ----- topK edge cases -----

    public void testTopKEqualsOne() throws Exception {
        long seed = randomLong();
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 50;
        int numQueries = 3;
        Path tempDir = createTempDir("gt-topk1");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, numQueries, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, numQueries, simFunc, null).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            1
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            1
        );

        for (int q = 0; q < numQueries; q++) {
            assertEquals("topK=1 should return exactly 1 result", 1, singlePassResult[q].length);
        }
        assertResultsEqual(perQueryResult, singlePassResult);
    }

    public void testTopKEqualsNumDocs() throws Exception {
        long seed = randomLong();
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 20;
        Path tempDir = createTempDir("gt-topk-all");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, 3, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            numDocs
        );

        for (int q = 0; q < 3; q++) {
            assertEquals("topK==numDocs should return all docs", numDocs, singlePassResult[q].length);
            Set<Integer> ids = new HashSet<>();
            for (int id : singlePassResult[q]) {
                ids.add(id);
            }
            assertEquals("all doc IDs should be unique", numDocs, ids.size());
        }
    }

    // ----- Minimal counts -----

    public void testOneQueryOneDoc() throws Exception {
        VectorSimilarityFunction simFunc = randomSimilarity();
        Path tempDir = createTempDir("gt-1q1d");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(42, 1, DIM);
        float[][] queryVectors = generateFloatVectors(43, 1, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, 1, simFunc, null).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            1
        );
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), 1, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            1
        );

        assertEquals(1, singlePassResult.length);
        assertEquals(1, singlePassResult[0].length);
        assertEquals(0, singlePassResult[0][0]);
        assertResultsEqual(perQueryResult, singlePassResult);
    }

    public void testManyQueriesOneDoc() throws Exception {
        VectorSimilarityFunction simFunc = randomSimilarity();
        Path tempDir = createTempDir("gt-nq1d");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(42, 1, DIM);
        float[][] queryVectors = generateFloatVectors(43, 10, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), 1, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            5
        );

        assertEquals(10, singlePassResult.length);
        for (int q = 0; q < 10; q++) {
            assertEquals("only 1 doc exists", 1, singlePassResult[q].length);
            assertEquals(0, singlePassResult[q][0]);
        }
    }

    // ----- Chunk boundary merge -----

    public void testChunkBoundaryMerge() throws Exception {
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 100_001;
        int dim = 4;
        int numQueries = 2;
        int topK = 5;
        long seed = 42;

        Path tempDir = createTempDir("gt-chunk-merge");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] queryVectors = generateFloatVectors(seed + 1, numQueries, dim);

        Random rng = new Random(seed);
        writeRandomFloatVectors(rawVecFile, rng, numDocs, dim);
        buildLuceneIndexFromFile(indexDir, rawVecFile, numDocs, dim, simFunc);

        DataGenerator dataGenPQ = makeDataGenerator(new float[0][], queryVectors);
        DataGenerator dataGenSP = makeDataGenerator(new float[0][], queryVectors);

        int[][] perQueryResult = new GroundTruth.PerQuery(indexDir, numQueries, simFunc, null).computeFloat(dataGenPQ, topK);
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, dim, simFunc).computeFloat(dataGenSP, topK);

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    // ----- Score ties -----

    public void testScoreTiesAtKBoundary() throws Exception {
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 20;
        int dim = 4;
        int topK = 5;
        Path tempDir = createTempDir("gt-ties");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[] sharedVec = { 0.5f, 0.5f, 0.5f, 0.5f };
        VectorUtil.l2normalize(sharedVec);
        float[] queryVec = sharedVec.clone();

        float[][] docVectors = new float[numDocs][];
        for (int i = 0; i < 15; i++) {
            docVectors[i] = sharedVec.clone();
        }
        Random rng = new Random(99);
        for (int i = 15; i < numDocs; i++) {
            docVectors[i] = new float[dim];
            for (int d = 0; d < dim; d++) {
                docVectors[i][d] = rng.nextFloat() * 0.01f;
            }
            VectorUtil.l2normalize(docVectors[i]);
        }

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, simFunc);

        float[][] queryVectors = { queryVec };
        int[][] singlePassResult = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, dim, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            topK
        );

        assertEquals(1, singlePassResult.length);
        assertEquals(topK, singlePassResult[0].length);
        for (int id : singlePassResult[0]) {
            assertTrue("tied doc should be from the identical-vector group (0-14), got " + id, id >= 0 && id < 15);
        }
    }

    // ----- Descending score order -----

    public void testResultsInDescendingScoreOrder() throws Exception {
        long seed = randomLong();
        VectorSimilarityFunction simFunc = randomSimilarity();
        int numDocs = 50;
        int numQueries = 5;
        int topK = 10;
        Path tempDir = createTempDir("gt-order");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Files.createDirectories(tempDir);

        float[][] docVectors = generateFloatVectors(seed, numDocs, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, numQueries, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);

        int[][] result = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, DIM, simFunc).computeFloat(
            makeDataGenerator(docVectors, queryVectors),
            topK
        );

        for (int q = 0; q < numQueries; q++) {
            float prevScore = Float.MAX_VALUE;
            for (int id : result[q]) {
                float score = simFunc.compare(queryVectors[q], docVectors[id]);
                assertTrue("query " + q + ": score " + score + " should be <= previous " + prevScore, score <= prevScore + 1e-6f);
                prevScore = score;
            }
        }
    }

    // ----- assertions -----

    private void assertResultsEqual(int[][] expected, int[][] actual) {
        assertEquals("number of queries", expected.length, actual.length);
        for (int q = 0; q < expected.length; q++) {
            // Sort before comparing: when multiple docs share the same score the
            // tie-break order can differ between strategies, but the set of IDs must match.
            int[] e = expected[q].clone();
            int[] a = actual[q].clone();
            Arrays.sort(e);
            Arrays.sort(a);
            assertArrayEquals("query " + q + " results differ", e, a);
        }
    }

    /** Computes filtered ground truth sequentially using PerQuery. */
    private static int[][] computeFilteredPerQueryFloat(
        Path indexDir,
        int numQueries,
        int topK,
        Query filter,
        VectorSimilarityFunction simFunc,
        float[][] docVectors,
        float[][] queryVectors
    ) throws IOException {
        int[][] result = new int[numQueries][];
        try (Directory dir = FSDirectory.open(indexDir); DirectoryReader reader = DirectoryReader.open(dir)) {
            DataGenerator dataGen = makeDataGenerator(docVectors, queryVectors);
            IndexVectorReader queryReader = dataGen.queries();
            for (int i = 0; i < numQueries; i++) {
                float[] qv = queryReader.nextFloatVector().vector();
                new GroundTruth.PerQuery.ComputeNNFloatTask(i, topK, qv, result, reader, filter, simFunc).call();
            }
        }
        return result;
    }

    private static VectorSimilarityFunction randomSimilarity() {
        return randomFrom(VectorSimilarityFunction.values());
    }

    // -- data generation helpers --

    private static float[][] generateFloatVectors(long seed, int count, int dim) {
        Random rng = new Random(seed);
        float[][] vecs = new float[count][dim];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dim; d++) {
                vecs[i][d] = rng.nextFloat() * 2 - 1;
            }
            VectorUtil.l2normalize(vecs[i]);
        }
        return vecs;
    }

    private static byte[][] generateByteVectors(long seed, int count, int dim) {
        Random rng = new Random(seed);
        byte[][] vecs = new byte[count][dim];
        for (int i = 0; i < count; i++) {
            rng.nextBytes(vecs[i]);
        }
        return vecs;
    }

    private static void writeRawFloatVectors(Path path, float[][] vectors) throws IOException {
        int dim = vectors[0].length;
        ByteBuffer buf = ByteBuffer.allocate(dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (float[] vec : vectors) {
                buf.clear();
                buf.asFloatBuffer().put(vec);
                buf.position(0).limit(dim * Float.BYTES);
                ch.write(buf);
            }
        }
    }

    private static void writeRawByteVectors(Path path, byte[][] vectors) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (byte[] vec : vectors) {
                ch.write(ByteBuffer.wrap(vec));
            }
        }
    }

    private static void buildLuceneIndex(Path indexDir, float[][] vectors, VectorSimilarityFunction simFunc) throws IOException {
        try (Directory dir = FSDirectory.open(indexDir); IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig())) {
            for (int i = 0; i < vectors.length; i++) {
                Document doc = new Document();
                doc.add(new KnnFloatVectorField(KnnIndexer.VECTOR_FIELD, vectors[i], simFunc));
                doc.add(new StoredField(KnnIndexer.ID_FIELD, i));
                iw.addDocument(doc);
            }
            iw.commit();
        }
        try (Directory dir = FSDirectory.open(indexDir); DirectoryReader reader = DirectoryReader.open(dir)) {
            assertEquals(vectors.length, reader.numDocs());
        }
    }

    private static void buildLuceneByteIndex(Path indexDir, byte[][] vectors, VectorSimilarityFunction simFunc) throws IOException {
        try (Directory dir = FSDirectory.open(indexDir); IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig())) {
            for (int i = 0; i < vectors.length; i++) {
                Document doc = new Document();
                doc.add(new KnnByteVectorField(KnnIndexer.VECTOR_FIELD, vectors[i], simFunc));
                doc.add(new StoredField(KnnIndexer.ID_FIELD, i));
                iw.addDocument(doc);
            }
            iw.commit();
        }
    }

    private static DataGenerator makeDataGenerator(float[][] docVectors, float[][] queryVectors) {
        return new NonPartitionDataGenerator(
            () -> new InMemoryFloatVectorReader(docVectors),
            docVectors.length,
            () -> new InMemoryFloatVectorReader(queryVectors),
            queryVectors.length
        );
    }

    private static DataGenerator makeByteDataGenerator(byte[][] docVectors, byte[][] queryVectors) {
        return new NonPartitionDataGenerator(
            () -> new InMemoryByteVectorReader(docVectors),
            docVectors.length,
            () -> new InMemoryByteVectorReader(queryVectors),
            queryVectors.length
        );
    }

    private static class InMemoryFloatVectorReader implements IndexVectorReader {
        private final float[][] vectors;
        private int pos;

        InMemoryFloatVectorReader(float[][] vectors) {
            this.vectors = vectors;
        }

        @Override
        public OrdinalVector<float[]> nextFloatVector() {
            int ord = pos++;
            return new OrdinalVector<>(ord, vectors[ord]);
        }

        @Override
        public OrdinalVector<byte[]> nextByteVector() {
            throw new UnsupportedOperationException();
        }
    }

    private static class InMemoryByteVectorReader implements IndexVectorReader {
        private final byte[][] vectors;
        private int pos;

        InMemoryByteVectorReader(byte[][] vectors) {
            this.vectors = vectors;
        }

        @Override
        public OrdinalVector<float[]> nextFloatVector() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrdinalVector<byte[]> nextByteVector() {
            int ord = pos++;
            return new OrdinalVector<>(ord, vectors[ord]);
        }
    }
}
