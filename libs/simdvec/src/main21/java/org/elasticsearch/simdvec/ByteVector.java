/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

import jdk.incubator.vector.VectorSpecies;

import java.util.Optional;

public final class ByteVector {

    private static final VectorSpecies<Byte> BS = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;

    private final jdk.incubator.vector.ByteVector vector;

    ByteVector(jdk.incubator.vector.ByteVector vector) {
        this.vector = vector;
    }

    public static int vectorByteSize() {
        return BS.vectorByteSize();
    }

    public static ByteVector fromArray(byte[] a, int offset) {
        return new ByteVector(jdk.incubator.vector.ByteVector.fromArray(BS, a, offset));
    }

    public long eq(byte e) {
        return vector.eq(e).toLong();
    }

    static {
        ByteVector.class.getModule().addReads(lookupVectorModule());
    }

    private static Module lookupVectorModule() {
        return Optional.ofNullable(ByteVector.class.getModule().getLayer())
            .orElse(ModuleLayer.boot())
            .findModule("jdk.incubator.vector")
            .orElseThrow(() -> new AssertionError("vector module not found"));
    }
}
