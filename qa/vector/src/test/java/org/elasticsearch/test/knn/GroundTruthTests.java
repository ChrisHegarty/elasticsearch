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
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.knn.data.DataGenerator;
import org.elasticsearch.test.knn.data.NonPartitionDataGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class GroundTruthTests extends ESTestCase {

    static final int NUM_DOCS = 500;
    static final int NUM_QUERIES = 10;
    static final int DIM = 32;
    static final int TOP_K = 10;

    /** Indexes random float32 vectors and verifies SinglePass and PerQuery return the same top-K doc IDs. */
    public void testSinglePassMatchesPerQueryFloat() throws Exception {
        long seed = randomLong();
        Path tempDir = createTempDir("ground-truth-test");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        float[][] docVectors = generateFloatVectors(seed, NUM_DOCS, DIM);
        float[][] queryVectors = generateFloatVectors(seed + 1, NUM_QUERIES, DIM);

        writeRawFloatVectors(rawVecFile, docVectors);
        buildLuceneIndex(indexDir, docVectors, VectorSimilarityFunction.DOT_PRODUCT);

        DataGenerator dataGenForPerQuery = makeDataGenerator(docVectors, queryVectors);
        DataGenerator dataGenForSinglePass = makeDataGenerator(docVectors, queryVectors);

        GroundTruth perQuery = new GroundTruth.PerQuery(indexDir, NUM_QUERIES, VectorSimilarityFunction.DOT_PRODUCT, null);
        GroundTruth singlePass = new GroundTruth.SinglePass(List.of(rawVecFile), NUM_DOCS, DIM, VectorSimilarityFunction.DOT_PRODUCT);

        int[][] perQueryResult = perQuery.computeFloat(dataGenForPerQuery, TOP_K);
        int[][] singlePassResult = singlePass.computeFloat(dataGenForSinglePass, TOP_K);

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    /** Same as the float test but with byte vectors, ensuring both strategies agree on byte-encoded data. */
    public void testSinglePassMatchesPerQueryByte() throws Exception {
        long seed = randomLong();
        Path tempDir = createTempDir("ground-truth-test-byte");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        byte[][] docVectors = generateByteVectors(seed, NUM_DOCS, DIM);
        byte[][] queryVectors = generateByteVectors(seed + 1, NUM_QUERIES, DIM);

        writeRawByteVectors(rawVecFile, docVectors);
        buildLuceneByteIndex(indexDir, docVectors, VectorSimilarityFunction.DOT_PRODUCT);

        DataGenerator dataGenForPerQuery = makeByteDataGenerator(docVectors, queryVectors);
        DataGenerator dataGenForSinglePass = makeByteDataGenerator(docVectors, queryVectors);

        GroundTruth perQuery = new GroundTruth.PerQuery(indexDir, NUM_QUERIES, VectorSimilarityFunction.DOT_PRODUCT, null);
        GroundTruth singlePass = new GroundTruth.SinglePass(List.of(rawVecFile), NUM_DOCS, DIM, VectorSimilarityFunction.DOT_PRODUCT);

        int[][] perQueryResult = perQuery.computeByte(dataGenForPerQuery, TOP_K);
        int[][] singlePassResult = singlePass.computeByte(dataGenForSinglePass, TOP_K);

        assertResultsEqual(perQueryResult, singlePassResult);
    }

    /** Times both strategies on 1M docs / 100 queries and prints elapsed ms. Not a correctness test -- just a timing comparison. */
    public void testTimingSinglePassVsPerQuery1M() throws Exception {
        int numDocs = 1_000_000;
        int numQueries = 100;
        int dim = 128;
        int topK = 100;

        Path tempDir = createTempDir("ground-truth-timing");
        Path rawVecFile = tempDir.resolve("docs.bin");
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);

        System.out.println("generating " + numDocs + " random float vectors (dim=" + dim + ")...");
        long t0 = System.nanoTime();
        Random rng = new Random(42);
        writeRandomFloatVectors(rawVecFile, rng, numDocs, dim);
        float[][] queryVectors = generateFloatVectors(43, numQueries, dim);
        System.out.println("data generation: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");

        System.out.println("building Lucene index for PerQuery strategy...");
        t0 = System.nanoTime();
        buildLuceneIndexFromFile(indexDir, rawVecFile, numDocs, dim, VectorSimilarityFunction.DOT_PRODUCT);
        System.out.println("index build: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");

        // -- SinglePass --
        DataGenerator dataGenSP = makeDataGenerator(new float[0][], queryVectors);
        GroundTruth singlePass = new GroundTruth.SinglePass(List.of(rawVecFile), numDocs, dim, VectorSimilarityFunction.DOT_PRODUCT);
        System.out.println("running SinglePass...");
        t0 = System.nanoTime();
        int[][] singlePassResult = singlePass.computeFloat(dataGenSP, topK);
        long singlePassMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("SinglePass: " + singlePassMs + " ms");

        // -- PerQuery --
        DataGenerator dataGenPQ = makeDataGenerator(new float[0][], queryVectors);
        GroundTruth perQuery = new GroundTruth.PerQuery(indexDir, numQueries, VectorSimilarityFunction.DOT_PRODUCT, null);
        System.out.println("running PerQuery...");
        t0 = System.nanoTime();
        int[][] perQueryResult = perQuery.computeFloat(dataGenPQ, topK);
        long perQueryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("PerQuery: " + perQueryMs + " ms");

        System.out.println(
            "=== SinglePass: " + singlePassMs + " ms  vs  PerQuery: " + perQueryMs + " ms  (speedup: "
                + String.format("%.1f", (double) perQueryMs / singlePassMs) + "x) ==="
        );

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
