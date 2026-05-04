/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.gpu;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gpu.GPUSupport;
import org.elasticsearch.gpu.codec.ES92GpuHnswBBQVectorsFormat;
import org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.BBQHnswIndexOptions;
import org.elasticsearch.index.mapper.vectors.VectorsFormatProvider;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

public class GPUPluginFormatProviderTests extends ESTestCase {

    private static GPUPlugin createPlugin(GPUPlugin.GpuMode mode) {
        Settings settings = Settings.builder().put(GPUPlugin.VECTORS_INDEXING_USE_GPU_NODE_SETTING.getKey(), mode).build();
        return new GPUPlugin(settings, new MockGPUSupport()) {
            @Override
            protected boolean isGpuIndexingFeatureAllowed() {
                return true;
            }
        };
    }

    public void testBBQHnswReturnsGpuBBQFormat() {
        GPUPlugin plugin = createPlugin(GPUPlugin.GpuMode.TRUE);
        VectorsFormatProvider provider = plugin.getVectorsFormatProvider();

        BBQHnswIndexOptions indexOptions = new BBQHnswIndexOptions(16, 100, false, null, -1);
        KnnVectorsFormat format = provider.getKnnVectorsFormat(
            null,
            indexOptions,
            DenseVectorFieldMapper.VectorSimilarity.COSINE,
            DenseVectorFieldMapper.ElementType.FLOAT,
            null,
            0
        );

        assertNotNull(format);
        assertThat(format, instanceOf(ES92GpuHnswBBQVectorsFormat.class));
    }

    public void testBBQHnswReturnsNullWhenGpuDisabled() {
        GPUPlugin plugin = createPlugin(GPUPlugin.GpuMode.FALSE);
        VectorsFormatProvider provider = plugin.getVectorsFormatProvider();

        BBQHnswIndexOptions indexOptions = new BBQHnswIndexOptions(16, 100, false, null, -1);
        KnnVectorsFormat format = provider.getKnnVectorsFormat(
            null,
            indexOptions,
            DenseVectorFieldMapper.VectorSimilarity.COSINE,
            DenseVectorFieldMapper.ElementType.FLOAT,
            null,
            0
        );

        assertThat(format, nullValue());
    }

    public void testByteElementTypeReturnsNull() {
        GPUPlugin plugin = createPlugin(GPUPlugin.GpuMode.TRUE);
        VectorsFormatProvider provider = plugin.getVectorsFormatProvider();

        BBQHnswIndexOptions indexOptions = new BBQHnswIndexOptions(16, 100, false, null, -1);
        KnnVectorsFormat format = provider.getKnnVectorsFormat(
            null,
            indexOptions,
            DenseVectorFieldMapper.VectorSimilarity.COSINE,
            DenseVectorFieldMapper.ElementType.BYTE,
            null,
            0
        );

        assertThat(format, nullValue());
    }

    public void testBBQHnswPassesThroughMAndEfConstruction() {
        GPUPlugin plugin = createPlugin(GPUPlugin.GpuMode.TRUE);
        VectorsFormatProvider provider = plugin.getVectorsFormatProvider();

        int m = 32;
        int efConstruction = 200;
        BBQHnswIndexOptions indexOptions = new BBQHnswIndexOptions(m, efConstruction, false, null, -1);
        KnnVectorsFormat format = provider.getKnnVectorsFormat(
            null,
            indexOptions,
            DenseVectorFieldMapper.VectorSimilarity.COSINE,
            DenseVectorFieldMapper.ElementType.FLOAT,
            null,
            0
        );

        assertNotNull(format);
        String str = format.toString();
        int expectedMaxConn = ES92GpuHnswVectorsFormat.cagraGraphDegree(m);
        int expectedBeamWidth = ES92GpuHnswVectorsFormat.cagraIntermediateGraphDegree(m, efConstruction);
        assertTrue("Expected maxConn=" + expectedMaxConn + " in " + str, str.contains("maxConn=" + expectedMaxConn));
        assertTrue("Expected beamWidth=" + expectedBeamWidth + " in " + str, str.contains("beamWidth=" + expectedBeamWidth));
    }

    public void testBBQHnswIndexOptionsAccessors() {
        int m = 24;
        int efConstruction = 150;
        BBQHnswIndexOptions options = new BBQHnswIndexOptions(m, efConstruction, true, null, 500);

        assertEquals(m, options.m());
        assertEquals(efConstruction, options.efConstruction());
        assertEquals(500, options.flatIndexThreshold());
    }

    private record MockGPUSupport() implements GPUSupport {
        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public long getTotalGpuMemory() {
            return 8L * 1024 * 1024 * 1024;
        }

        @Override
        public String getGpuName() {
            return "MockGPU";
        }
    }
}
