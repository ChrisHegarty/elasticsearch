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
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.gpu.CuVSGPUSupport;
import org.elasticsearch.gpu.GPUSupport;
import org.elasticsearch.index.codec.vectors.es93.ES93HnswBinaryQuantizedVectorsFormat;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.ESTestCase.WithoutEntitlements;

import org.junit.BeforeClass;

import java.io.IOException;

import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_BEAM_WIDTH;
import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_MAX_CONN;

/**
 * Verifies that GPU HNSW format names match their CPU equivalents, so that
 * Lucene's {@link PerFieldKnnVectorsFormat} resolves the correct reader at
 * search time via {@link KnnVectorsFormat#forName(String)}.
 */
@WithoutEntitlements // CuVS native library loading is not covered by gpu-codec test entitlements
public class GpuFormatNameCompatibilityTests extends ESTestCase {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
    }

    static GPUSupport gpuSupport;
    static boolean gpuSupported;

    @BeforeClass
    public static void beforeClass() {
        gpuSupport = CuVSGPUSupport.instance();
        gpuSupported = gpuSupport.isSupported();
    }

    public void testGpuBBQFormatNameMatchesCpuBBQ() {
        assertEquals(ES93HnswBinaryQuantizedVectorsFormat.NAME, ES92GpuHnswBBQVectorsFormat.NAME);
    }

    public void testGpuSQFormatNameMatchesCpuSQ() {
        assertEquals("ES814HnswScalarQuantizedVectorsFormat", ES92GpuHnswSQVectorsFormat.NAME);
    }

    public void testGpuFloatFormatName() {
        assertEquals("Lucene99HnswVectorsFormat", ES92GpuHnswVectorsFormat.NAME);
    }

    // -- the remainder of the tests require a GPU to be present

    public void testGpuBBQFormatNameMatchesCpuBBQ2() {
        assumeTrue("cuvs not supported", gpuSupported);
        assertEquals(ES93HnswBinaryQuantizedVectorsFormat.NAME, (new ES92GpuHnswBBQVectorsFormat()).getName());
    }

    public void testGpuSQFormatNameMatchesCpuSQ2() {
        assumeTrue("cuvs not supported", gpuSupported);
        assertEquals("ES814HnswScalarQuantizedVectorsFormat", (new ES92GpuHnswSQVectorsFormat()).getName());
    }

    public void testGpuFloatFormatName2() {
        assumeTrue("cuvs not supported", gpuSupported);
        assertEquals("Lucene99HnswVectorsFormat", (new ES92GpuHnswVectorsFormat()).getName());
    }

    public void testForNameResolvesCpuBBQFormat() {
        assumeTrue("cuvs not supported", gpuSupported);
        var resolved = KnnVectorsFormat.forName(ES92GpuHnswBBQVectorsFormat.NAME);
        assertNotNull(resolved);
        assertEquals(ES93HnswBinaryQuantizedVectorsFormat.NAME, resolved.getName());
    }

    public void testForNameResolvesCpuSQFormat() {
        assumeTrue("cuvs not supported", gpuSupported);
        var resolved = KnnVectorsFormat.forName(ES92GpuHnswSQVectorsFormat.NAME);
        assertNotNull(resolved);
        assertEquals("ES814HnswScalarQuantizedVectorsFormat", resolved.getName());
    }

    public void testBBQFieldInfoFormatNameAfterIndexing() throws IOException {
        assumeTrue("cuvs not supported", gpuSupported);
        doTestFieldInfoFormatName(
            new ES92GpuHnswBBQVectorsFormat(
                gpuSupport.getTotalGpuMemory(), DEFAULT_MAX_CONN, DEFAULT_BEAM_WIDTH,
                DenseVectorFieldMapper.ElementType.FLOAT, false
            ),
            ES93HnswBinaryQuantizedVectorsFormat.NAME
        );
    }

    public void testSQFieldInfoFormatNameAfterIndexing() throws IOException {
        assumeTrue("cuvs not supported", gpuSupported);
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
