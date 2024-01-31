/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec.internal;

import org.elasticsearch.vec.VectorSimilarityType;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

// Scalar Quantized vectors are inherently bytes.
final class DotProduct extends AbstractScalarQuantizedVectorScorer {

    private final VectorDataInput data;

    DotProduct(int dims, int maxOrd, float scoreCorrectionConstant, VectorSimilarityType similarityType, VectorDataInput data) {
        super(dims, maxOrd, scoreCorrectionConstant, similarityType);
        this.data = data;
    }

    @Override
    public float score(int firstOrd, int secondOrd) {
        final int length = dims;
        int firstByteOffset = firstOrd * (length + Float.BYTES);
        MemorySegment firstSeg = data.addressFor(firstByteOffset);
        float firstOffset = data.readFloat(firstByteOffset + length);

        int secondByteOffset = secondOrd * (length + Float.BYTES);
        MemorySegment secondSeg = data.addressFor(secondByteOffset);
        float secondOffset = data.readFloat(secondByteOffset + length);

        int dotProduct = NativeVectorDistance.dotProduct(firstSeg, secondSeg, length);
        float adjustedDistance = dotProduct * scoreCorrectionConstant + firstOffset + secondOffset;
        return (1 + adjustedDistance) / 2;
    }

    @Override
    public void close() throws IOException {
        data.close();
    }
}
