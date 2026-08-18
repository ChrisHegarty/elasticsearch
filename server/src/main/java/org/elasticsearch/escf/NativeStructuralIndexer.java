/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.escf;

import org.elasticsearch.foreign.LoaderHelper;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.sourcebatch.simdjson.BitIndexes;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.elasticsearch.foreign.LinkerHelper.downcallHandle;

/**
 * Delegates stage 1 structural indexing to the native simdjson C++ library via Panama FFI.
 *
 * <p>The native library ({@code libes_simdjson}) wraps simdjson's
 * {@code dom_parser_implementation::stage1()}, which auto-selects the best SIMD backend
 * (AVX-512, AVX2, SSE4.2, NEON) at runtime.
 *
 * <p>Each instance holds a native context ({@code es_stage1_ctx*}) that is reused across
 * calls. Instances are <strong>not thread-safe</strong> — each thread should own its own
 * instance (managed by {@link SimdJsonPool}).
 *
 * <p><b>Off-heap buffer strategy.</b>
 * Panama FFI downcalls reject heap-backed {@link MemorySegment}s, so this class maintains
 * persistent off-heap buffers for both input and output. The input {@code byte[]} is copied
 * into an off-heap buffer, and the native function writes structural indices into an off-heap
 * {@code int[]} buffer. Results are then copied back into the Java {@link BitIndexes} array.
 * Buffers grow as needed and are reused across calls, so the amortized cost is just the
 * memcpy (which is small relative to the SIMD stage 1 work).
 */
final class NativeStructuralIndexer implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(NativeStructuralIndexer.class);

    private static final MethodHandle CREATE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle RUN_INTO;
    private static final MethodHandle PADDING_MH;

    static final boolean AVAILABLE;
    static final int NATIVE_PADDING;

    static {
        boolean loaded = false;
        int padding = 64;
        MethodHandle create = null, destroy = null, runInto = null, paddingMh = null;

        try {
            LoaderHelper.loadLibrary("es_simdjson");

            create = downcallHandle(
                "es_stage1_create",
                FunctionDescriptor.of(ADDRESS, JAVA_INT)
            );
            destroy = downcallHandle(
                "es_stage1_destroy",
                FunctionDescriptor.ofVoid(ADDRESS)
            );
            runInto = downcallHandle(
                "es_stage1_run_into",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
            );
            paddingMh = downcallHandle(
                "es_stage1_padding",
                FunctionDescriptor.of(JAVA_INT)
            );
            padding = (int) paddingMh.invokeExact();
            loaded = true;
            logger.info("Native simdjson stage 1 loaded (SIMDJSON_PADDING={})", padding);
        } catch (UnsatisfiedLinkError e) {
            logger.debug("Native simdjson library not available: [{}]", e.getMessage());
        } catch (Throwable t) {
            logger.warn("Failed to initialize native simdjson stage 1", t);
        }

        AVAILABLE = loaded;
        NATIVE_PADDING = padding;
        CREATE = create;
        DESTROY = destroy;
        RUN_INTO = runInto;
        PADDING_MH = paddingMh;
    }

    private final Arena arena;
    private MemorySegment ctx;
    private final MemorySegment outCount;
    private MemorySegment inputBuf;
    private int inputBufSize;
    private MemorySegment outputBuf;
    private int outputBufCapacity;

    NativeStructuralIndexer(int initialCapacity) {
        if (AVAILABLE == false) {
            throw new IllegalStateException("Native simdjson is not available");
        }
        this.arena = Arena.ofConfined();
        this.outCount = arena.allocate(JAVA_INT);

        int inputSize = initialCapacity + NATIVE_PADDING;
        this.inputBuf = arena.allocate(inputSize);
        this.inputBufSize = inputSize;

        int outCap = Math.max(initialCapacity, 4096);
        this.outputBuf = arena.allocate((long) outCap * JAVA_INT.byteSize());
        this.outputBufCapacity = outCap;

        try {
            this.ctx = (MemorySegment) CREATE.invokeExact(initialCapacity);
        } catch (Throwable t) {
            arena.close();
            throw new IllegalStateException("Failed to create native stage 1 context", t);
        }
        if (ctx.equals(MemorySegment.NULL)) {
            arena.close();
            throw new IllegalStateException("Native es_stage1_create returned null");
        }
    }

    /**
     * Runs native stage 1 over {@code buffer[0..len)} and writes the resulting structural
     * indices directly into {@code bitIndexes}. The buffer must have at least
     * {@link #NATIVE_PADDING} bytes of readable space past {@code len}.
     *
     * @throws org.elasticsearch.sourcebatch.simdjson.JsonParsingException on invalid UTF-8
     *         or other structural errors detected by simdjson
     */
    void index(byte[] buffer, int len, BitIndexes bitIndexes) {
        try {
            bitIndexes.ensureCapacity(len + 1);
            bitIndexes.reset();

            int requiredInput = len + NATIVE_PADDING;
            if (requiredInput > inputBufSize) {
                inputBuf = arena.allocate(requiredInput);
                inputBufSize = requiredInput;
            }
            MemorySegment.copy(buffer, 0, inputBuf, ValueLayout.JAVA_BYTE, 0, len);

            int[] rawIndexes = bitIndexes.rawIndexes();
            if (rawIndexes.length > outputBufCapacity) {
                outputBuf = arena.allocate((long) rawIndexes.length * JAVA_INT.byteSize());
                outputBufCapacity = rawIndexes.length;
            }

            int err = (int) RUN_INTO.invokeExact(
                ctx,
                inputBuf, len,
                outputBuf, outputBufCapacity,
                outCount
            );
            if (err != 0) {
                throw new org.elasticsearch.sourcebatch.simdjson.JsonParsingException(
                    "Native simdjson stage 1 failed with error code " + err
                );
            }

            int count = outCount.get(JAVA_INT, 0);
            MemorySegment.copy(outputBuf, JAVA_INT, 0, rawIndexes, 0, count);
            bitIndexes.setWriteIdx(count);
        } catch (org.elasticsearch.sourcebatch.simdjson.JsonParsingException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native stage 1 invocation failed", t);
        }
    }

    @Override
    public void close() {
        if (ctx != null && DESTROY != null) {
            try {
                DESTROY.invokeExact(ctx);
            } catch (Throwable t) {
                logger.warn("Failed to destroy native stage 1 context", t);
            }
            ctx = null;
        }
        arena.close();
    }
}
