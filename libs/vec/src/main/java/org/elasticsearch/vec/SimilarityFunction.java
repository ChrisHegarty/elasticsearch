/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec;

//import java.io.Closeable;
//import java.io.IOException;

// Scores .. double addressing
// Thread-safe ..
public final class SimilarityFunction {

    float squareDistance(int firstOrd, int secondOrd, int length) {
        return 0f;
    }

    // float compare(int firstOrd, int secondOrd) throws IOException;

    // VectorSimilarity similarity();

    // int maxOrd(); // given ords to score cannot exceed this maximum
}
