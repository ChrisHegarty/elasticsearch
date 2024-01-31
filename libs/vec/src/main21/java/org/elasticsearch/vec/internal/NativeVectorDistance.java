/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec.internal;

import java.lang.foreign.MemorySegment;
import java.security.AccessController;
import java.security.PrivilegedAction;

import static org.elasticsearch.vec.internal.gen.vec_h.dot8s;

public final class NativeVectorDistance {

    /**
     * Computes the dot product of given byte vectors
     * @param a address of the first vector
     * @param b address of the second vector
     * @param length the vector dimensions
     */
    static int dotProduct(MemorySegment a, MemorySegment b, int length) {
        return dot8s(a, b, length);
    }

    static float squareDistance(MemorySegment a, MemorySegment b, int length) {
        return 0f; // TODO
    }

    @SuppressWarnings("removal")
    private static void loadLibrary() {
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            System.loadLibrary("vec");
            return null;
        });
    }
}
