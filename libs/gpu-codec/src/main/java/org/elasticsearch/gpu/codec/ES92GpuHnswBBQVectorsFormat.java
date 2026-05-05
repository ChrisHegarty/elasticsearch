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
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.elasticsearch.gpu.CuVSGPUSupport;
import org.elasticsearch.index.codec.vectors.es93.ES93BinaryQuantizedVectorsFormat;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;

import java.io.IOException;
import java.util.function.Supplier;

import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_BEAM_WIDTH;
import static org.elasticsearch.gpu.codec.ES92GpuHnswVectorsFormat.DEFAULT_MAX_CONN;
import static org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.MAX_DIMS_COUNT;

/**
 * Codec format for GPU-accelerated BBQ (binary quantized) HNSW vector indexes.
 * The HNSW graph is built on GPU from full float32 vectors, while BBQ quantization
 * and search is performed on CPU.
 */
public class ES92GpuHnswBBQVectorsFormat extends KnnVectorsFormat {
    public static final String NAME = "ES93HnswBinaryQuantizedVectorsFormat";
    static final int MAXIMUM_MAX_CONN = 512;
    static final int MAXIMUM_BEAM_WIDTH = 3200;
    private final int maxConn;
    private final int beamWidth;

    private final FlatVectorsFormat flatVectorsFormat;
    private final Supplier<CuVSResourceManager> cuVSResourceManagerSupplier;
    private final long totalDeviceMemory;

    public ES92GpuHnswBBQVectorsFormat() {
        this(
            CuVSResourceManager::pooling,
            CuVSGPUSupport.instance().getTotalGpuMemory(),
            DEFAULT_MAX_CONN,
            DEFAULT_BEAM_WIDTH,
            DenseVectorFieldMapper.ElementType.FLOAT,
            false
        );
    }

    public ES92GpuHnswBBQVectorsFormat(
        long totalDeviceMemory,
        int maxConn,
        int beamWidth,
        DenseVectorFieldMapper.ElementType elementType,
        boolean useDirectIO
    ) {
        this(CuVSResourceManager::pooling, totalDeviceMemory, maxConn, beamWidth, elementType, useDirectIO);
    }

    ES92GpuHnswBBQVectorsFormat(
        Supplier<CuVSResourceManager> cuVSResourceManagerSupplier,
        long totalDeviceMemory,
        int maxConn,
        int beamWidth,
        DenseVectorFieldMapper.ElementType elementType,
        boolean useDirectIO
    ) {
        super(NAME);
        this.totalDeviceMemory = totalDeviceMemory;
        this.cuVSResourceManagerSupplier = cuVSResourceManagerSupplier;
        if (maxConn <= 0 || maxConn > MAXIMUM_MAX_CONN) {
            throw new IllegalArgumentException(
                "maxConn must be positive and less than or equal to " + MAXIMUM_MAX_CONN + "; maxConn=" + maxConn
            );
        }
        if (beamWidth <= 0 || beamWidth > MAXIMUM_BEAM_WIDTH) {
            throw new IllegalArgumentException(
                "beamWidth must be positive and less than or equal to " + MAXIMUM_BEAM_WIDTH + "; beamWidth=" + beamWidth
            );
        }
        this.maxConn = maxConn;
        this.beamWidth = beamWidth;
        this.flatVectorsFormat = new ES93BinaryQuantizedVectorsFormat(elementType, useDirectIO);
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return new ES92GpuHnswVectorsWriter(
            cuVSResourceManagerSupplier.get(),
            totalDeviceMemory,
            state,
            maxConn,
            beamWidth,
            flatVectorsFormat.fieldsWriter(state)
        );
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return new Lucene99HnswVectorsReader(state, flatVectorsFormat.fieldsReader(state));
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return MAX_DIMS_COUNT;
    }

    @Override
    public String toString() {
        return NAME
            + "(name="
            + NAME
            + ", maxConn="
            + maxConn
            + ", beamWidth="
            + beamWidth
            + ", flatVectorFormat="
            + flatVectorsFormat
            + ")";
    }
}
