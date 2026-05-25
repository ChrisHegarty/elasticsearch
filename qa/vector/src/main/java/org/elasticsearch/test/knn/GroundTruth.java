/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.knn;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queries.function.FunctionQuery;
import org.apache.lucene.queries.function.valuesource.ByteKnnVectorFieldSource;
import org.apache.lucene.queries.function.valuesource.ByteVectorSimilarityFunction;
import org.apache.lucene.queries.function.valuesource.ConstKnnByteVectorValueSource;
import org.apache.lucene.queries.function.valuesource.ConstKnnFloatValueSource;
import org.apache.lucene.queries.function.valuesource.FloatKnnVectorFieldSource;
import org.apache.lucene.queries.function.valuesource.FloatVectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.test.knn.data.DataGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.elasticsearch.test.knn.KnnIndexTester.logger;
import static org.elasticsearch.test.knn.KnnIndexer.ID_FIELD;
import static org.elasticsearch.test.knn.KnnIndexer.VECTOR_FIELD;

/**
 * Computes brute-force exact nearest neighbors (ground truth) for recall evaluation.
 * <p>
 * Two strategies are available:
 * <ul>
 *   <li>{@link SinglePass} (default) -- scans all doc vectors once, scoring every query
 *       against each doc as it streams by. Much faster for large unfiltered datasets.</li>
 *   <li>{@link PerQuery} -- runs one Lucene FunctionQuery per query vector. Required for
 *       filtered queries, or selectable with {@code -Des.knn.groundtruth.legacy=true}.</li>
 * </ul>
 */
interface GroundTruth {

    int[][] computeFloat(DataGenerator dataGenerator, int topK) throws IOException;

    int[][] computeByte(DataGenerator dataGenerator, int topK) throws IOException;

    static GroundTruth create(
        List<Path> docPaths,
        Path indexPath,
        int numDocs,
        int numQueryVectors,
        int dim,
        VectorSimilarityFunction similarityFunction,
        Query filterQuery
    ) {
        boolean useLegacy = Boolean.getBoolean("es.knn.groundtruth.legacy");
        if (filterQuery != null || useLegacy) {
            return new PerQuery(indexPath, numQueryVectors, similarityFunction, filterQuery);
        }
        return new SinglePass(docPaths, numDocs, dim, similarityFunction);
    }

    static int[] getResultIds(TopDocs topDocs, StoredFields storedFields) throws IOException {
        int[] resultIds = new int[topDocs.scoreDocs.length];
        int i = 0;
        for (ScoreDoc doc : topDocs.scoreDocs) {
            if (doc.doc != NO_MORE_DOCS) {
                resultIds[i++] = Integer.parseInt(storedFields.document(doc.doc).get(ID_FIELD));
            }
        }
        return resultIds;
    }
    // ----- Per-query strategy (original, supports filters) -----

    class PerQuery implements GroundTruth {

        private final Path indexPath;
        private final int numQueryVectors;
        private final VectorSimilarityFunction similarityFunction;
        private final Query filterQuery;

        PerQuery(Path indexPath, int numQueryVectors, VectorSimilarityFunction similarityFunction, Query filterQuery) {
            this.indexPath = indexPath;
            this.numQueryVectors = numQueryVectors;
            this.similarityFunction = similarityFunction;
            this.filterQuery = filterQuery;
        }

        @Override
        public int[][] computeFloat(DataGenerator dataGenerator, int topK) throws IOException {
            int[][] result = new int[dataGenerator.numQueries()][];
            try (Directory dir = FSDirectory.open(indexPath); DirectoryReader reader = DirectoryReader.open(dir)) {
                List<Callable<Void>> tasks = new ArrayList<>();
                IndexVectorReader queryReader = dataGenerator.queries();
                for (int i = 0; i < numQueryVectors; i++) {
                    float[] queryVector = queryReader.nextFloatVector().vector();
                    tasks.add(new ComputeNNFloatTask(i, topK, queryVector, result, reader, filterQuery, similarityFunction));
                }
                ForkJoinPool.commonPool().invokeAll(tasks);
                return result;
            }
        }

        @Override
        public int[][] computeByte(DataGenerator dataGenerator, int topK) throws IOException {
            int[][] result = new int[dataGenerator.numQueries()][];
            try (Directory dir = FSDirectory.open(indexPath); DirectoryReader reader = DirectoryReader.open(dir)) {
                List<Callable<Void>> tasks = new ArrayList<>();
                IndexVectorReader queryReader = dataGenerator.queries();
                for (int i = 0; i < numQueryVectors; i++) {
                    byte[] queryVector = queryReader.nextByteVector().vector();
                    tasks.add(new ComputeNNByteTask(i, topK, queryVector, result, reader, filterQuery, similarityFunction));
                }
                ForkJoinPool.commonPool().invokeAll(tasks);
                return result;
            }
        }

        static class ComputeNNFloatTask implements Callable<Void> {
            private final int queryOrd;
            private final float[] query;
            private final int[][] result;
            private final IndexReader reader;
            private final VectorSimilarityFunction similarityFunction;
            private final Query filterQuery;
            private final int topK;

            ComputeNNFloatTask(
                int queryOrd,
                int topK,
                float[] query,
                int[][] result,
                IndexReader reader,
                Query filterQuery,
                VectorSimilarityFunction similarityFunction
            ) {
                this.queryOrd = queryOrd;
                this.query = query;
                this.result = result;
                this.reader = reader;
                this.similarityFunction = similarityFunction;
                this.filterQuery = filterQuery;
                this.topK = topK;
            }

            @Override
            public Void call() {
                IndexSearcher searcher = new IndexSearcher(reader);
                try {
                    var queryVector = new ConstKnnFloatValueSource(query);
                    var docVectors = new FloatKnnVectorFieldSource(VECTOR_FIELD);
                    Query query = new FunctionQuery(new FloatVectorSimilarityFunction(similarityFunction, queryVector, docVectors));
                    if (filterQuery != null) {
                        query = new BooleanQuery.Builder().add(query, BooleanClause.Occur.SHOULD)
                            .add(filterQuery, BooleanClause.Occur.FILTER)
                            .build();
                    }
                    var topDocs = searcher.search(query, topK);
                    result[queryOrd] = getResultIds(topDocs, reader.storedFields());
                    if ((queryOrd + 1) % 100 == 0) {
                        logger.debug(" exact knn scored " + (queryOrd + 1));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        }

        static class ComputeNNByteTask implements Callable<Void> {
            private final int queryOrd;
            private final byte[] query;
            private final int[][] result;
            private final IndexReader reader;
            private final VectorSimilarityFunction similarityFunction;
            private final Query filterQuery;
            private final int topK;

            ComputeNNByteTask(
                int queryOrd,
                int topK,
                byte[] query,
                int[][] result,
                IndexReader reader,
                Query filterQuery,
                VectorSimilarityFunction similarityFunction
            ) {
                this.queryOrd = queryOrd;
                this.query = query;
                this.result = result;
                this.reader = reader;
                this.similarityFunction = similarityFunction;
                this.filterQuery = filterQuery;
                this.topK = topK;
            }

            @Override
            public Void call() {
                IndexSearcher searcher = new IndexSearcher(reader);
                try {
                    var queryVector = new ConstKnnByteVectorValueSource(query);
                    var docVectors = new ByteKnnVectorFieldSource(VECTOR_FIELD);
                    Query query = new FunctionQuery(new ByteVectorSimilarityFunction(similarityFunction, queryVector, docVectors));
                    if (filterQuery != null) {
                        query = new BooleanQuery.Builder().add(query, BooleanClause.Occur.SHOULD)
                            .add(filterQuery, BooleanClause.Occur.FILTER)
                            .build();
                    }
                    var topDocs = searcher.search(query, topK);
                    result[queryOrd] = getResultIds(topDocs, reader.storedFields());
                    if ((queryOrd + 1) % 100 == 0) {
                        logger.debug(" exact knn scored " + (queryOrd + 1));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }
        }
    }

    // ----- Single-pass strategy (default, unfiltered only) -----

    class SinglePass implements GroundTruth {

        private static final int CHUNK_SIZE = 100_000;

        private final List<Path> docPaths;
        private final int numDocs;
        private final int dim;
        private final VectorSimilarityFunction similarityFunction;

        SinglePass(List<Path> docPaths, int numDocs, int dim, VectorSimilarityFunction similarityFunction) {
            this.docPaths = docPaths;
            this.numDocs = numDocs;
            this.dim = dim;
            this.similarityFunction = similarityFunction;
        }

        @Override
        public int[][] computeFloat(DataGenerator dataGenerator, int topK) throws IOException {
            int numQueries = dataGenerator.numQueries();
            float[][] queryVectors = new float[numQueries][];
            try (IndexVectorReader queryReader = dataGenerator.queries()) {
                for (int i = 0; i < numQueries; i++) {
                    queryVectors[i] = queryReader.nextFloatVector().vector();
                }
            }

            int vectorByteSize = dim * Float.BYTES;
            List<MappedFileRegion> mappedRegions = mapDocFiles(vectorByteSize);
            try {
                List<Callable<BoundedScoreHeap[]>> tasks = createChunkTasks(
                    numDocs,
                    (start, end) -> new FloatChunkTask(
                        start,
                        end,
                        topK,
                        queryVectors,
                        mappedRegions,
                        vectorByteSize,
                        dim,
                        similarityFunction
                    )
                );
                return mergeChunkResults(ForkJoinPool.commonPool().invokeAll(tasks), numQueries, topK);
            } finally {
                closeRegions(mappedRegions);
            }
        }

        @Override
        public int[][] computeByte(DataGenerator dataGenerator, int topK) throws IOException {
            int numQueries = dataGenerator.numQueries();
            byte[][] queryVectors = new byte[numQueries][];
            try (IndexVectorReader queryReader = dataGenerator.queries()) {
                for (int i = 0; i < numQueries; i++) {
                    queryVectors[i] = queryReader.nextByteVector().vector();
                }
            }

            int vectorByteSize = dim;
            List<MappedFileRegion> mappedRegions = mapDocFiles(vectorByteSize);
            try {
                List<Callable<BoundedScoreHeap[]>> tasks = createChunkTasks(
                    numDocs,
                    (start, end) -> new ByteChunkTask(
                        start,
                        end,
                        topK,
                        queryVectors,
                        mappedRegions,
                        vectorByteSize,
                        dim,
                        similarityFunction
                    )
                );
                return mergeChunkResults(ForkJoinPool.commonPool().invokeAll(tasks), numQueries, topK);
            } finally {
                closeRegions(mappedRegions);
            }
        }

        // -- helpers --

        record MappedFileRegion(FileChannel channel, ByteBuffer buffer, int startDoc, int numDocs) {}

        @FunctionalInterface
        interface ChunkTaskFactory<T> {
            T create(int startDoc, int endDoc);
        }

        private static <T> List<Callable<T>> createChunkTasks(int totalDocs, ChunkTaskFactory<Callable<T>> factory) {
            int numChunks = (totalDocs + CHUNK_SIZE - 1) / CHUNK_SIZE;
            List<Callable<T>> tasks = new ArrayList<>(numChunks);
            int docOffset = 0;
            for (int c = 0; c < numChunks; c++) {
                int chunkStart = docOffset;
                int chunkEnd = Math.min(docOffset + CHUNK_SIZE, totalDocs);
                tasks.add(factory.create(chunkStart, chunkEnd));
                docOffset = chunkEnd;
            }
            return tasks;
        }

        private List<MappedFileRegion> mapDocFiles(int vectorByteSize) throws IOException {
            List<MappedFileRegion> regions = new ArrayList<>();
            int docOffset = 0;
            int docsRemaining = numDocs;
            for (Path path : docPaths) {
                FileChannel ch = FileChannel.open(path);
                long fileSize = ch.size();
                int fileDocs = (int) (fileSize / vectorByteSize);
                fileDocs = Math.min(fileDocs, docsRemaining);
                ByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, (long) fileDocs * vectorByteSize);
                mapped.order(ByteOrder.LITTLE_ENDIAN);
                regions.add(new MappedFileRegion(ch, mapped, docOffset, fileDocs));
                docOffset += fileDocs;
                docsRemaining -= fileDocs;
                if (docsRemaining <= 0) break;
            }
            return regions;
        }

        private static void closeRegions(List<MappedFileRegion> regions) throws IOException {
            IOException first = null;
            for (MappedFileRegion r : regions) {
                try {
                    r.channel().close();
                } catch (IOException e) {
                    if (first == null) first = e;
                    else first.addSuppressed(e);
                }
            }
            if (first != null) throw first;
        }

        static MappedFileRegion findRegion(List<MappedFileRegion> regions, int globalDoc) {
            for (MappedFileRegion r : regions) {
                if (globalDoc >= r.startDoc && globalDoc < r.startDoc + r.numDocs) {
                    return r;
                }
            }
            throw new IllegalArgumentException("doc " + globalDoc + " not found in any mapped region");
        }

        private static int[][] mergeChunkResults(List<Future<BoundedScoreHeap[]>> futures, int numQueries, int topK) {
            List<BoundedScoreHeap[]> chunkResults = new ArrayList<>();
            for (Future<BoundedScoreHeap[]> f : futures) {
                try {
                    chunkResults.add(f.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            int[][] result = new int[numQueries][];
            for (int q = 0; q < numQueries; q++) {
                BoundedScoreHeap merged = new BoundedScoreHeap(topK);
                for (BoundedScoreHeap[] chunkHeaps : chunkResults) {
                    BoundedScoreHeap chunkHeap = chunkHeaps[q];
                    for (int i = 0; i < chunkHeap.size(); i++) {
                        merged.offer(chunkHeap.ordAt(i), chunkHeap.scoreAt(i));
                    }
                }
                result[q] = merged.ordinals();
            }
            return result;
        }

        // -- chunk tasks --

        static class FloatChunkTask implements Callable<BoundedScoreHeap[]> {
            private final int startDoc, endDoc, topK, dim;
            private final float[][] queryVectors;
            private final List<MappedFileRegion> regions;
            private final int vectorByteSize;
            private final VectorSimilarityFunction simFunc;

            FloatChunkTask(
                int startDoc,
                int endDoc,
                int topK,
                float[][] queryVectors,
                List<MappedFileRegion> regions,
                int vectorByteSize,
                int dim,
                VectorSimilarityFunction simFunc
            ) {
                this.startDoc = startDoc;
                this.endDoc = endDoc;
                this.topK = topK;
                this.queryVectors = queryVectors;
                this.regions = regions;
                this.vectorByteSize = vectorByteSize;
                this.dim = dim;
                this.simFunc = simFunc;
            }

            @Override
            public BoundedScoreHeap[] call() {
                int numQueries = queryVectors.length;
                BoundedScoreHeap[] heaps = new BoundedScoreHeap[numQueries];
                for (int q = 0; q < numQueries; q++) {
                    heaps[q] = new BoundedScoreHeap(topK);
                }
                float[] docVec = new float[dim];
                for (int doc = startDoc; doc < endDoc; doc++) {
                    MappedFileRegion region = findRegion(regions, doc);
                    int localDoc = doc - region.startDoc;
                    int offset = localDoc * vectorByteSize;
                    region.buffer.slice(offset, vectorByteSize).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(docVec);
                    for (int q = 0; q < numQueries; q++) {
                        float score = simFunc.compare(queryVectors[q], docVec);
                        heaps[q].offer(doc, score);
                    }
                    if (doc > startDoc && (doc - startDoc) % 1_000_000 == 0) {
                        logger.debug("single-pass float: scored {} / {} docs in chunk", doc - startDoc, endDoc - startDoc);
                    }
                }
                return heaps;
            }
        }

        static class ByteChunkTask implements Callable<BoundedScoreHeap[]> {
            private final int startDoc, endDoc, topK, dim;
            private final byte[][] queryVectors;
            private final List<MappedFileRegion> regions;
            private final int vectorByteSize;
            private final VectorSimilarityFunction simFunc;

            ByteChunkTask(
                int startDoc,
                int endDoc,
                int topK,
                byte[][] queryVectors,
                List<MappedFileRegion> regions,
                int vectorByteSize,
                int dim,
                VectorSimilarityFunction simFunc
            ) {
                this.startDoc = startDoc;
                this.endDoc = endDoc;
                this.topK = topK;
                this.queryVectors = queryVectors;
                this.regions = regions;
                this.vectorByteSize = vectorByteSize;
                this.dim = dim;
                this.simFunc = simFunc;
            }

            @Override
            public BoundedScoreHeap[] call() {
                int numQueries = queryVectors.length;
                BoundedScoreHeap[] heaps = new BoundedScoreHeap[numQueries];
                for (int q = 0; q < numQueries; q++) {
                    heaps[q] = new BoundedScoreHeap(topK);
                }
                byte[] docVec = new byte[dim];
                for (int doc = startDoc; doc < endDoc; doc++) {
                    MappedFileRegion region = findRegion(regions, doc);
                    int localDoc = doc - region.startDoc;
                    int offset = localDoc * vectorByteSize;
                    region.buffer.slice(offset, vectorByteSize).get(docVec);
                    for (int q = 0; q < numQueries; q++) {
                        float score = simFunc.compare(queryVectors[q], docVec);
                        heaps[q].offer(doc, score);
                    }
                    if (doc > startDoc && (doc - startDoc) % 1_000_000 == 0) {
                        logger.debug("single-pass byte: scored {} / {} docs in chunk", doc - startDoc, endDoc - startDoc);
                    }
                }
                return heaps;
            }
        }
    }

    /**
     * Fixed-capacity min-heap that retains the top-K (ordinal, score) pairs by score.
     * The heap root is the minimum score; new entries that exceed it displace the root.
     */
    class BoundedScoreHeap {
        private final int capacity;
        private int size;
        private final int[] ords;
        private final float[] scores;

        BoundedScoreHeap(int capacity) {
            this.capacity = capacity;
            this.ords = new int[capacity];
            this.scores = new float[capacity];
        }

        void offer(int ord, float score) {
            if (size < capacity) {
                ords[size] = ord;
                scores[size] = score;
                size++;
                if (size == capacity) {
                    buildMinHeap();
                }
            } else if (score > scores[0]) {
                ords[0] = ord;
                scores[0] = score;
                siftDown(0);
            }
        }

        int size() {
            return size;
        }

        int ordAt(int i) {
            return ords[i];
        }

        float scoreAt(int i) {
            return scores[i];
        }

        /** Returns ordinals sorted by descending score (best match first). */
        int[] ordinals() {
            int len = size;
            int[] sorted = new int[len];
            float[] sortedScores = new float[len];
            System.arraycopy(ords, 0, sorted, 0, len);
            System.arraycopy(scores, 0, sortedScores, 0, len);
            for (int i = len - 1; i > 0; i--) {
                int tmpOrd = sorted[0];
                float tmpScore = sortedScores[0];
                sorted[0] = sorted[i];
                sortedScores[0] = sortedScores[i];
                sorted[i] = tmpOrd;
                sortedScores[i] = tmpScore;
                siftDown(sorted, sortedScores, 0, i);
            }
            return sorted;
        }

        private void buildMinHeap() {
            for (int i = size / 2 - 1; i >= 0; i--) {
                siftDown(i);
            }
        }

        private void siftDown(int i) {
            siftDown(ords, scores, i, size);
        }

        private static void siftDown(int[] ords, float[] scores, int i, int n) {
            while (true) {
                int left = 2 * i + 1;
                int right = 2 * i + 2;
                int smallest = i;
                if (left < n && scores[left] < scores[smallest]) {
                    smallest = left;
                }
                if (right < n && scores[right] < scores[smallest]) {
                    smallest = right;
                }
                if (smallest == i) break;
                int tmpOrd = ords[i];
                float tmpScore = scores[i];
                ords[i] = ords[smallest];
                scores[i] = scores[smallest];
                ords[smallest] = tmpOrd;
                scores[smallest] = tmpScore;
                i = smallest;
            }
        }
    }
}
