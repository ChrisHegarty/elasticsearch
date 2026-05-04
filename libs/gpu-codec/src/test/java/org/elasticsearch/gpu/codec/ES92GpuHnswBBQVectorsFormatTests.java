/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gpu.codec;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.tests.index.BaseKnnVectorsFormatTestCase;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.gpu.CuVSGPUSupport;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.junit.BeforeClass;

import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_BEAM_WIDTH;
import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_MAX_CONN;
import static org.elasticsearch.test.ESTestCase.randomFrom;
import static org.hamcrest.Matchers.containsString;

@LuceneTestCase.SuppressSysoutChecks(bugUrl = "https://github.com/rapidsai/cuvs/issues/1310")
public class ES92GpuHnswBBQVectorsFormatTests extends BaseKnnVectorsFormatTestCase {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging();
    }

    static Codec codec;

    @BeforeClass
    public static void beforeClass() {
        var gpuSupport = CuVSGPUSupport.instance();
        assumeTrue("cuvs not supported", gpuSupport.isSupported());
        codec = TestUtil.alwaysKnnVectorsFormat(
            new ES92GpuHnswBBQVectorsFormat(
                gpuSupport.getTotalGpuMemory(),
                DEFAULT_MAX_CONN,
                DEFAULT_BEAM_WIDTH,
                DenseVectorFieldMapper.ElementType.FLOAT,
                false
            )
        );
    }

    @Override
    protected Codec getCodec() {
        return codec;
    }

    @Override
    protected VectorSimilarityFunction randomSimilarity() {
        var randomGPUSupportedSimilarity = randomFrom(
            DenseVectorFieldMapper.VectorSimilarity.L2_NORM,
            DenseVectorFieldMapper.VectorSimilarity.COSINE,
            DenseVectorFieldMapper.VectorSimilarity.DOT_PRODUCT
        );

        return randomGPUSupportedSimilarity.vectorSimilarityFunction(IndexVersion.current(), DenseVectorFieldMapper.ElementType.FLOAT);
    }

    @Override
    protected VectorEncoding randomVectorEncoding() {
        return VectorEncoding.FLOAT32;
    }

    @Override
    public void testRandomBytes() {
        // No bytes support
    }

    @Override
    public void testSortedIndexBytes() {
        // No bytes support
    }

    @Override
    public void testByteVectorScorerIteration() {
        // No bytes support
    }

    @Override
    public void testEmptyByteVectorData() {
        // No bytes support
    }

    @Override
    public void testMergingWithDifferentByteKnnFields() {
        // No bytes support
    }

    @Override
    public void testMismatchedFields() {
        // No bytes support
    }

    @Override
    protected boolean supportsFloatVectorFallback() {
        return false;
    }

    public void testMaxConnTooLow() {
        expectThrows(
            IllegalArgumentException.class,
            () -> new ES92GpuHnswBBQVectorsFormat(1024, 0, 100, DenseVectorFieldMapper.ElementType.FLOAT, false)
        );
    }

    public void testMaxConnTooHigh() {
        expectThrows(
            IllegalArgumentException.class,
            () -> new ES92GpuHnswBBQVectorsFormat(1024, 513, 100, DenseVectorFieldMapper.ElementType.FLOAT, false)
        );
    }

    public void testBeamWidthTooLow() {
        expectThrows(
            IllegalArgumentException.class,
            () -> new ES92GpuHnswBBQVectorsFormat(1024, 16, 0, DenseVectorFieldMapper.ElementType.FLOAT, false)
        );
    }

    public void testBeamWidthTooHigh() {
        expectThrows(
            IllegalArgumentException.class,
            () -> new ES92GpuHnswBBQVectorsFormat(1024, 16, 3201, DenseVectorFieldMapper.ElementType.FLOAT, false)
        );
    }

    public void testToString() {
        var format = new ES92GpuHnswBBQVectorsFormat(1024, 32, 200, DenseVectorFieldMapper.ElementType.FLOAT, false);
        String str = format.toString();
        assertThat(str, containsString("maxConn=32"));
        assertThat(str, containsString("beamWidth=200"));
        assertThat(str, containsString("ES93BinaryQuantizedVectorsFormat"));
    }

    public void testMaxDimensions() {
        var format = new ES92GpuHnswBBQVectorsFormat(1024, 16, 100, DenseVectorFieldMapper.ElementType.FLOAT, false);
        assertEquals(DenseVectorFieldMapper.MAX_DIMS_COUNT, format.getMaxDimensions("test"));
    }
}
