/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec.internal;

import org.elasticsearch.vec.VectorScorer;
import org.elasticsearch.vec.VectorSimilarityType;

public abstract sealed class AbstractScalarQuantizedVectorScorer implements VectorScorer permits DotProduct
{

    protected final VectorSimilarityType similarityType;
    protected final int dims;
    protected final int maxOrd;
    protected final float scoreCorrectionConstant;

    protected AbstractScalarQuantizedVectorScorer(
        int dims,
        int maxOrd,
        float scoreCorrectionConstant,
        VectorSimilarityType similarityType
    ) {
        this.similarityType = similarityType;
        this.dims = dims;
        this.maxOrd = maxOrd;
        this.scoreCorrectionConstant = scoreCorrectionConstant;
    }

    @Override
    public VectorSimilarityType similarityType() {
        return similarityType;
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public int maxOrd() {
        return maxOrd;
    }
}
