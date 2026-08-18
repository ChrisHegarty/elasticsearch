/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.escf;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.sourcebatch.simdjson.FieldNameTable;
import org.elasticsearch.sourcebatch.simdjson.SimdJsonBatchParser;
import org.elasticsearch.sourcebatch.simdjson.SimdJsonSupport;

/**
 * Thread-local pool of {@link SimdJsonBatchParser} and {@link SimdJsonDirectWalker} instances
 * for use by {@link EscfEncoder}.
 *
 * <p>All SIMD parser instances on all threads share a single {@link FieldNameTable} root.
 * Each thread-local walker holds a {@link FieldNameTable.Child} that starts with a
 * read-only snapshot of the shared names. New field names discovered during parsing are
 * added locally and merged back to the root via {@code release()}, making them available
 * to future parsers on any thread — the same parent/child pattern Jackson uses for
 * {@code ByteQuadsCanonicalizer}.
 *
 * <p>When the native simdjson library is available, each thread-local batch parser is wired
 * to a {@link NativeStructuralIndexer} that delegates stage 1 to the C++ simdjson library.
 * If the native library is not present, the pure-Java {@code StructuralIndexer} is used.
 *
 * <p>{@link #AVAILABLE} must be checked before calling any accessor — the constructors
 * throw {@link IllegalStateException} when {@code jdk.incubator.vector} is absent at runtime.
 */
final class SimdJsonPool {

    private static final Logger logger = LogManager.getLogger(SimdJsonPool.class);

    /** Documents larger than this threshold are handled by the Jackson parser. */
    static final int MAX_DOC_BYTES = 16 * 1024;

    /** Default batch capacity: 256 KiB, enough for ~90 clickbench_flat documents. */
    private static final int BATCH_CAPACITY = 256 * 1024;

    /**
     * True when {@code jdk.incubator.vector} is available at runtime. When false, every
     * eligibility check in {@link EscfEncoder} short-circuits to the Jackson path without ever
     * touching the {@link ThreadLocal} (which would fail on construction).
     */
    static final boolean AVAILABLE = SimdJsonSupport.isAvailable();

    /** True when the native simdjson C++ library is loaded and stage 1 can be delegated. */
    static final boolean NATIVE_AVAILABLE = NativeStructuralIndexer.AVAILABLE;

    /**
     * Shared root field name table. All thread-local walkers hold children of this root,
     * enabling cross-thread field name sharing without synchronization during parsing.
     */
    private static final FieldNameTable NAME_TABLE = new FieldNameTable();

    /**
     * Scratch buffer: {@code MAX_DOC_BYTES + 64} bytes so that the SIMD structural indexer
     * has sufficient padding past the document end.
     */
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[MAX_DOC_BYTES + 64]);

    private static final ThreadLocal<SimdJsonBatchParser> BATCH_PARSER = ThreadLocal.withInitial(() -> {
        SimdJsonBatchParser parser = new SimdJsonBatchParser(BATCH_CAPACITY);
        if (NATIVE_AVAILABLE) {
            NativeStructuralIndexer nativeIndexer = new NativeStructuralIndexer(BATCH_CAPACITY);
            parser.setNativeDelegate(nativeIndexer::index);
            logger.debug("Thread [{}] using native simdjson stage 1", Thread.currentThread().getName());
        }
        return parser;
    });

    private static final ThreadLocal<SimdJsonDirectWalker> DIRECT_WALKER = ThreadLocal.withInitial(
        () -> new SimdJsonDirectWalker(NAME_TABLE.makeChild())
    );

    private SimdJsonPool() {}

    /**
     * Returns the thread-local scratch buffer of length {@code MAX_DOC_BYTES + 64}.
     * Used to copy a non-zero-offset {@link org.elasticsearch.common.bytes.BytesReference} into
     * a zero-offset array before handing it to the parser.
     */
    static byte[] scratch() {
        return SCRATCH.get();
    }

    /** Returns the thread-local batch parser. Only call when {@link #AVAILABLE} is true. */
    static SimdJsonBatchParser batchParser() {
        return BATCH_PARSER.get();
    }

    /** Returns the thread-local fused stage2+walk instance. Only call when {@link #AVAILABLE} is true. */
    static SimdJsonDirectWalker directWalker() {
        return DIRECT_WALKER.get();
    }
}
