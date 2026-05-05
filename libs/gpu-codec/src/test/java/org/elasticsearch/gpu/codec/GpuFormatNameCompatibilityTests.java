/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gpu.codec;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.TestUtil;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.gpu.CuVSGPUSupport;
import org.elasticsearch.index.codec.vectors.es93.ES93HnswBinaryQuantizedVectorsFormat;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_BEAM_WIDTH;
import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_MAX_CONN;

/**
 * Verifies that GPU HNSW format names match their CPU equivalents, so that
 * Lucene's {@link PerFieldKnnVectorsFormat} resolves the correct reader at
 * search time via {@link KnnVectorsFormat#forName(String)}.
 */
public class GpuFormatNameCompatibilityTests extends ESTestCase {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
    }

    public void testGpuBBQFormatNameMatchesCpuBBQ() {
        var gpuFormat = new ES92GpuHnswBBQVectorsFormat(1024, DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH,
            DenseVectorFieldMapper.ElementType.FLOAT, false);
        var cpuFormat = new ES93HnswBinaryQuantizedVectorsFormat();
        assertEquals(cpuFormat.getName(), gpuFormat.getName());
    }

    public void testGpuSQFormatNameMatchesCpuSQ() {
        var gpuFormat = new ES92GpuHnswSQVectorsFormat(1024, DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH, null, 7, false);
        assertEquals("ES814HnswScalarQuantizedVectorsFormat", gpuFormat.getName());
        var resolved = KnnVectorsFormat.forName(gpuFormat.getName());
        assertEquals(gpuFormat.getName(), resolved.getName());
    }

    public void testGpuFloatFormatNameMatchesLuceneHnsw() {
        var gpuFormat = new ES92GpuHnswVectorsFormat(CuVSResourceManager::pooling, 1024, DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH);
        assertEquals("Lucene99HnswVectorsFormat", gpuFormat.getName());
    }

    public void testForNameResolvesCpuBBQFormat() {
        var resolved = KnnVectorsFormat.forName(ES92GpuHnswBBQVectorsFormat.NAME);
        assertNotNull(resolved);
        assertEquals(ES93HnswBinaryQuantizedVectorsFormat.NAME, resolved.getName());
    }

    public void testForNameResolvesCpuSQFormat() {
        var resolved = KnnVectorsFormat.forName(ES92GpuHnswSQVectorsFormat.NAME);
        assertNotNull(resolved);
        assertEquals("ES814HnswScalarQuantizedVectorsFormat", resolved.getName());
    }

    public void testBBQFieldInfoFormatNameAfterIndexing() throws IOException {
        var gpuSupport = CuVSGPUSupport.instance();
        assumeTrue("cuvs not supported", gpuSupport.isSupported());
        doTestFieldInfoFormatName(
            new ES92GpuHnswBBQVectorsFormat(
                gpuSupport.getTotalGpuMemory(), DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH,
                DenseVectorFieldMapper.ElementType.FLOAT, false
            ),
            ES93HnswBinaryQuantizedVectorsFormat.NAME
        );
    }

    public void testSQFieldInfoFormatNameAfterIndexing() throws IOException {
        var gpuSupport = CuVSGPUSupport.instance();
        assumeTrue("cuvs not supported", gpuSupport.isSupported());
        doTestFieldInfoFormatName(
            new ES92GpuHnswSQVectorsFormat(
                gpuSupport.getTotalGpuMemory(), DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH, null, 7, false
            ),
            "ES814HnswScalarQuantizedVectorsFormat"
        );
    }

    private void doTestFieldInfoFormatName(KnnVectorsFormat gpuFormat, String expectedFormatName) throws IOException {
        try (Directory dir = newDirectory()) {
            IndexWriterConfig iwc = new IndexWriterConfig();
            iwc.setCodec(TestUtil.alwaysKnnVectorsFormat(gpuFormat));
            try (IndexWriter writer = new IndexWriter(dir, iwc)) {
                Document doc = new Document();
                doc.add(new KnnFloatVectorField("vector", new float[] { 1.0f, 2.0f, 3.0f }, VectorSimilarityFunction.DOT_PRODUCT));
                writer.addDocument(doc);
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                for (LeafReaderContext leaf : reader.leaves()) {
                    FieldInfo fi = leaf.reader().getFieldInfos().fieldInfo("vector");
                    assertNotNull(fi);
                    String storedFormatName = fi.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY);
                    assertEquals(
                        "Format name written to FieldInfo must match CPU equivalent for correct SPI resolution",
                        expectedFormatName,
                        storedFormatName
                    );
                }
            }
        }
    }
}
