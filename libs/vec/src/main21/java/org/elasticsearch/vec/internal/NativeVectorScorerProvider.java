/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec.internal;

import org.elasticsearch.vec.VectorScorer;
import org.elasticsearch.vec.VectorScorerProvider;
import org.elasticsearch.vec.VectorSimilarityType;

import java.nio.file.Path;

public final class NativeVectorScorerProvider implements VectorScorerProvider {

    // Invoked by provider lookup mechanism
    NativeVectorScorerProvider() {}

    @Override
    public VectorScorer getScalarQuantizedVectorScorer(
        int dim,
        int maxOrd,
        float scoreCorrectionConstant,
        VectorSimilarityType similarityType,
        Path path
    ) {
        Path p = path; // TODO: open a reader here
        VectorDataInput data = null;
        return switch (similarityType) {
            case COSINE -> null;
            case DOT_PRODUCT -> new DotProduct(dim, maxOrd, scoreCorrectionConstant, similarityType, data);
            case EUCLIDEAN -> null;
            case MAXIMUM_INNER_PRODUCT -> null;
        };
    }
}
