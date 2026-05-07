/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gpu.codec;

import com.nvidia.cuvs.CuVSMatrix;

/**
 * Quantization strategy used by the GPU HNSW codec format.
 * Determines the data type used for CAGRA graph building and the
 * distance metric selection for DOT_PRODUCT similarity.
 */
enum GpuHnswQuantizationType {
    NONE(CuVSMatrix.DataType.FLOAT),
    INT8_SQ(CuVSMatrix.DataType.BYTE),
    BBQ(CuVSMatrix.DataType.FLOAT);

    private final CuVSMatrix.DataType cagraDataType;

    GpuHnswQuantizationType(CuVSMatrix.DataType cagraDataType) {
        this.cagraDataType = cagraDataType;
    }

    CuVSMatrix.DataType cagraDataType() {
        return cagraDataType;
    }

    boolean isQuantized() {
        return this != NONE;
    }
}
