/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.reflect;

import org.apache.lucene.codecs.lucene95.HasIndexSlice;
import org.apache.lucene.util.hnsw.CloseableRandomVectorScorerSupplier;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.elasticsearch.index.codec.vectors.Lucene99ScalarQuantizedVectorsWriter;
import org.elasticsearch.simdvec.QuantizedByteVectorValuesAccess;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class VectorsFormatReflectionUtils {
    private static final VarHandle FLOAT_SUPPLIER_HANDLE;
    private static final VarHandle BYTE_SUPPLIER_HANDLE;
    private static final VarHandle BINARIZED_RAW_SCORER_HANDLE;

    private static final Class<?> FLAT_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS;
    private static final Class<?> SCALAR_QUANTIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS;
    private static final Class<?> BINARIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS;

    static {
        try {
            FLAT_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS = Class.forName(
                "org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsWriter$FlatCloseableRandomVectorScorerSupplier"
            );
            var lookup = MethodHandles.privateLookupIn(FLAT_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS, MethodHandles.lookup());
            FLOAT_SUPPLIER_HANDLE = lookup.findVarHandle(
                FLAT_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS,
                "supplier",
                RandomVectorScorerSupplier.class
            );

            SCALAR_QUANTIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS = Class.forName(
                Lucene99ScalarQuantizedVectorsWriter.class.getCanonicalName() + "$ScalarQuantizedCloseableRandomVectorScorerSupplier"
            );
            lookup = MethodHandles.privateLookupIn(SCALAR_QUANTIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS, MethodHandles.lookup());
            BYTE_SUPPLIER_HANDLE = lookup.findVarHandle(
                SCALAR_QUANTIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS,
                "supplier",
                RandomVectorScorerSupplier.class
            );

            BINARIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS = Class.forName(
                "org.elasticsearch.index.codec.vectors.es818.ES818BinaryQuantizedVectorsWriter$BinarizedCloseableRandomVectorScorerSupplier"
            );
            lookup = MethodHandles.privateLookupIn(BINARIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS, MethodHandles.lookup());
            BINARIZED_RAW_SCORER_HANDLE = lookup.findVarHandle(
                BINARIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS,
                "rawVectorScorerSupplier",
                CloseableRandomVectorScorerSupplier.class
            );

        } catch (IllegalAccessException e) {
            throw new AssertionError("should not happen, check opens", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static RandomVectorScorerSupplier getFlatRandomVectorScorerInnerSupplier(CloseableRandomVectorScorerSupplier scorerSupplier) {
        if (scorerSupplier.getClass().equals(FLAT_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS)) {
            return (RandomVectorScorerSupplier) FLOAT_SUPPLIER_HANDLE.get(scorerSupplier);
        }
        if (BINARIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS.isInstance(scorerSupplier)) {
            var rawScorer = (CloseableRandomVectorScorerSupplier) BINARIZED_RAW_SCORER_HANDLE.get(scorerSupplier);
            if (rawScorer != null) {
                return getFlatRandomVectorScorerInnerSupplier(rawScorer);
            }
        }
        return null;
    }

    public static RandomVectorScorerSupplier getScalarQuantizedRandomVectorScorerInnerSupplier(
        CloseableRandomVectorScorerSupplier scorerSupplier
    ) {
        if (scorerSupplier.getClass().equals(SCALAR_QUANTIZED_CLOSEABLE_RANDOM_VECTOR_SCORER_SUPPLIER_CLASS)) {
            return (RandomVectorScorerSupplier) BYTE_SUPPLIER_HANDLE.get(scorerSupplier);
        }
        return null;
    }

    public static HasIndexSlice getByteScoringSupplierVectorOrNull(RandomVectorScorerSupplier scorerSupplier) {
        if (scorerSupplier instanceof QuantizedByteVectorValuesAccess quantizedByteVectorValuesAccess) {
            return quantizedByteVectorValuesAccess.get();
        }
        return null;
    }

}
