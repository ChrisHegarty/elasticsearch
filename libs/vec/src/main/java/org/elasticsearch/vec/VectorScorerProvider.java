/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;

public interface VectorScorerProvider {

    VectorScorer getScalarQuantizedVectorScorer(
        int dim,
        int maxOrd,
        float scoreCorrectionConstant,
        VectorSimilarityType similarityType,
        Path path
    );

    static VectorScorerProvider getInstanceOrNull() {
        return Holder.INSTANCE;
    }

    final class Holder {

        private Holder() {}

        private static final VectorScorerProvider INSTANCE = getOrNull();

        static VectorScorerProvider getOrNull() {
            if (jdk21OrGreater() && isAArch64()) {
                return loadImpl(Runtime.version().feature());
            } else {
                return null;
            }
        }

        static boolean isAArch64() {
            String arch = getProperty("os.arch");
            if ("aarch64".equals(arch)) {
                return true;
            }
            return false;
        }

        static boolean jdk21OrGreater() {
            return Runtime.version().feature() >= 21;
        }

        private static VectorScorerProvider loadImpl(int runtimeVersion) {
            try {
                var lookup = MethodHandles.lookup();
                var clazz = lookup.findClass("org.elasticsearch.vec.internal.NativeVectorScorerProvider");
                var constructor = lookup.findConstructor(clazz, MethodType.methodType(void.class));
                try {
                    return (VectorScorerProvider) constructor.invokeExact();
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new LinkageError("NativeVectorScorerProvider for Java " + runtimeVersion + " has a bad constructor", e);
            } catch (ClassNotFoundException cnfe) {
                throw new LinkageError("NativeVectorScorerProvider is missing for Java " + runtimeVersion, cnfe);
            }
        }

        @SuppressWarnings("removal")
        static String getProperty(String name) {
            PrivilegedAction<String> pa = () -> System.getProperty(name);
            return AccessController.doPrivileged(pa);
        }
    }
}
