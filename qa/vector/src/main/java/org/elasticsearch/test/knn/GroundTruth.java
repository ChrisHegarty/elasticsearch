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
import org.apache.lucene.index.VectorEncoding;
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
import org.apache.lucene.util.hnsw.NeighborQueue;
import org.elasticsearch.test.knn.data.DataGenerator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
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
 *       against each doc as it streams by. Much faster for large datasets. Supports
 *       optional filters by resolving the filter to a BitSet of accepted doc IDs upfront.</li>
 *   <li>{@link PerQuery} -- runs one Lucene FunctionQuery per query vector. Selectable
 *       with {@code -Des.knn.groundtruth.perQuery=true}.</li>
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
        if (Boolean.getBoolean("es.knn.groundtruth.perQuery")) {
            return new PerQuery(indexPath, numQueryVectors, similarityFunction, filterQuery);
        }
        return new SinglePass(docPaths, numDocs, dim, similarityFunction, indexPath, filterQuery);
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

    // ----- Single-pass strategy (default) -----

    class SinglePass implements GroundTruth {

        private static final int CHUNK_SIZE = 100_000;

        private final List<Path> docPaths;
        private final int numDocs;
        private final int dim;
        private final VectorSimilarityFunction similarityFunction;
        private final Path indexPath;
        private final Query filterQuery;

        SinglePass(List<Path> docPaths, int numDocs, int dim, VectorSimilarityFunction similarityFunction) {
            this(docPaths, numDocs, dim, similarityFunction, null, null);
        }

        SinglePass(
            List<Path> docPaths,
            int numDocs,
            int dim,
            VectorSimilarityFunction similarityFunction,
            Path indexPath,
            Query filterQuery
        ) {
            this.docPaths = docPaths;
            this.numDocs = numDocs;
            this.dim = dim;
            this.similarityFunction = similarityFunction;
            this.indexPath = indexPath;
            this.filterQuery = filterQuery;
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

            BitSet acceptedDocs = resolveFilter();
            try (var reader = IndexVectorReader.MultiFileVectorReader.create(docPaths, dim, VectorEncoding.FLOAT32, numDocs, false)) {
                List<Callable<NeighborQueue[]>> tasks = createChunkTasks(
                    numDocs,
                    (start, end) -> new FloatChunkTask(start, end, topK, queryVectors, reader, dim, similarityFunction, acceptedDocs)
                );
                return mergeChunkResults(ForkJoinPool.commonPool().invokeAll(tasks), numQueries, topK);
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

            BitSet acceptedDocs = resolveFilter();
            try (var reader = IndexVectorReader.MultiFileVectorReader.create(docPaths, dim, VectorEncoding.BYTE, numDocs, false)) {
                List<Callable<NeighborQueue[]>> tasks = createChunkTasks(
                    numDocs,
                    (start, end) -> new ByteChunkTask(start, end, topK, queryVectors, reader, dim, similarityFunction, acceptedDocs)
                );
                return mergeChunkResults(ForkJoinPool.commonPool().invokeAll(tasks), numQueries, topK);
            }
        }

        private BitSet resolveFilter() throws IOException {
            if (filterQuery == null) {
                return null;
            }
            BitSet accepted = new BitSet(numDocs);
            try (Directory dir = FSDirectory.open(indexPath); DirectoryReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                StoredFields storedFields = reader.storedFields();
                TopDocs topDocs = searcher.search(filterQuery, numDocs);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    if (scoreDoc.doc != NO_MORE_DOCS) {
                        int id = Integer.parseInt(storedFields.document(scoreDoc.doc).get(ID_FIELD));
                        accepted.set(id);
                    }
                }
            }
            logger.info("filter resolved to {} / {} accepted docs", accepted.cardinality(), numDocs);
            return accepted;
        }

        // -- helpers --

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

        private static int[][] mergeChunkResults(List<Future<NeighborQueue[]>> futures, int numQueries, int topK) {
            List<NeighborQueue[]> chunkResults = new ArrayList<>();
            for (Future<NeighborQueue[]> f : futures) {
                try {
                    chunkResults.add(f.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            int[][] result = new int[numQueries][];
            for (int q = 0; q < numQueries; q++) {
                NeighborQueue merged = new NeighborQueue(topK, false);
                for (NeighborQueue[] chunkHeaps : chunkResults) {
                    NeighborQueue chunkHeap = chunkHeaps[q];
                    while (chunkHeap.size() > 0) {
                        merged.insertWithOverflow(chunkHeap.topNode(), chunkHeap.topScore());
                        chunkHeap.pop();
                    }
                }
                result[q] = drainDescending(merged);
            }
            return result;
        }

        private static int[] drainDescending(NeighborQueue queue) {
            int[] ids = new int[queue.size()];
            for (int i = ids.length - 1; i >= 0; i--) {
                ids[i] = queue.pop();
            }
            return ids;
        }

        // -- chunk tasks --

        static class FloatChunkTask implements Callable<NeighborQueue[]> {
            private final int startDoc, endDoc, topK, dim;
            private final float[][] queryVectors;
            private final IndexVectorReader.MultiFileVectorReader reader;
            private final VectorSimilarityFunction simFunc;
            private final BitSet acceptedDocs;

            FloatChunkTask(
                int startDoc,
                int endDoc,
                int topK,
                float[][] queryVectors,
                IndexVectorReader.MultiFileVectorReader reader,
                int dim,
                VectorSimilarityFunction simFunc,
                BitSet acceptedDocs
            ) {
                this.startDoc = startDoc;
                this.endDoc = endDoc;
                this.topK = topK;
                this.queryVectors = queryVectors;
                this.reader = reader;
                this.dim = dim;
                this.simFunc = simFunc;
                this.acceptedDocs = acceptedDocs;
            }

            @Override
            public NeighborQueue[] call() throws IOException {
                int numQueries = queryVectors.length;
                NeighborQueue[] heaps = new NeighborQueue[numQueries];
                for (int q = 0; q < numQueries; q++) {
                    heaps[q] = new NeighborQueue(topK, false);
                }
                float[] docVec = new float[dim];
                ByteBuffer scratch = ByteBuffer.allocate(dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int doc = startDoc; doc < endDoc; doc++) {
                    if (acceptedDocs != null && acceptedDocs.get(doc) == false) continue;
                    reader.readFloat(doc, docVec, scratch);
                    for (int q = 0; q < numQueries; q++) {
                        float score = simFunc.compare(queryVectors[q], docVec);
                        heaps[q].insertWithOverflow(doc, score);
                    }
                    if (doc > startDoc && (doc - startDoc) % 1_000_000 == 0) {
                        logger.debug("single-pass float: scored {} / {} docs in chunk", doc - startDoc, endDoc - startDoc);
                    }
                }
                return heaps;
            }
        }

        static class ByteChunkTask implements Callable<NeighborQueue[]> {
            private final int startDoc, endDoc, topK, dim;
            private final byte[][] queryVectors;
            private final IndexVectorReader.MultiFileVectorReader reader;
            private final VectorSimilarityFunction simFunc;
            private final BitSet acceptedDocs;

            ByteChunkTask(
                int startDoc,
                int endDoc,
                int topK,
                byte[][] queryVectors,
                IndexVectorReader.MultiFileVectorReader reader,
                int dim,
                VectorSimilarityFunction simFunc,
                BitSet acceptedDocs
            ) {
                this.startDoc = startDoc;
                this.endDoc = endDoc;
                this.topK = topK;
                this.queryVectors = queryVectors;
                this.reader = reader;
                this.dim = dim;
                this.simFunc = simFunc;
                this.acceptedDocs = acceptedDocs;
            }

            @Override
            public NeighborQueue[] call() throws IOException {
                int numQueries = queryVectors.length;
                NeighborQueue[] heaps = new NeighborQueue[numQueries];
                for (int q = 0; q < numQueries; q++) {
                    heaps[q] = new NeighborQueue(topK, false);
                }
                byte[] docVec = new byte[dim];
                for (int doc = startDoc; doc < endDoc; doc++) {
                    if (acceptedDocs != null && acceptedDocs.get(doc) == false) continue;
                    reader.readByte(doc, docVec);
                    for (int q = 0; q < numQueries; q++) {
                        float score = simFunc.compare(queryVectors[q], docVec);
                        heaps[q].insertWithOverflow(doc, score);
                    }
                    if (doc > startDoc && (doc - startDoc) % 1_000_000 == 0) {
                        logger.debug("single-pass byte: scored {} / {} docs in chunk", doc - startDoc, endDoc - startDoc);
                    }
                }
                return heaps;
            }
        }
    }

}
