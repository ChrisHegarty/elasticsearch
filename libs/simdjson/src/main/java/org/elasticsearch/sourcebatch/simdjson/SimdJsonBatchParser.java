/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.sourcebatch.simdjson;

/**
 * Batch-aware JSON parser that runs SIMD stage 1 (structural indexing + UTF-8 validation) once
 * over a contiguous buffer containing multiple JSON documents, then provides per-document
 * {@link BitIndexes} windows for direct walking.
 *
 * <p>This amortizes the SIMD setup cost (vector broadcasts, register initialization) across many
 * small documents instead of paying it once per document.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 *   SimdJsonBatchParser batch = new SimdJsonBatchParser(256 * 1024);
 *   batch.stage1(buffer, totalLen);
 *   for (int i = 0; i < docCount; i++) {
 *       batch.prepareDocumentWindow(docOffsets[i], docLengths[i]);
 *       // walk batch.bitIndexes() directly ...
 *   }
 * }</pre>
 *
 * <p><strong>Not thread-safe.</strong> Each thread must own its own instance.
 */
public class SimdJsonBatchParser {

    private static final int PADDING = 64;

    private final StructuralIndexer indexer;
    private final BitIndexes bitIndexes;

    private boolean stage1Done;
    private int savedSentinelPos = -1;
    private int savedSentinelValue;
    private int nextSearchFrom;

    /**
     * Optional callback for delegating stage 1 to a native implementation.
     * When set and the delegate succeeds, the Java {@link StructuralIndexer} is bypassed.
     */
    private Stage1Delegate nativeDelegate;

    /**
     * Functional interface for native stage 1 delegation.
     * Implementations run structural indexing on {@code buffer[0..len)} and populate
     * {@code bitIndexes} with the resulting structural byte offsets.
     */
    @FunctionalInterface
    public interface Stage1Delegate {
        void index(byte[] buffer, int len, BitIndexes bitIndexes);
    }

    /**
     * @param capacity maximum total batch size in bytes (sum of all documents)
     */
    public SimdJsonBatchParser(int capacity) {
        if (!SimdJsonSupport.VECTOR_AVAILABLE) {
            throw new IllegalStateException("jdk.incubator.vector is not available at runtime");
        }
        int indexCapacity = Math.max(capacity, 1024);
        bitIndexes = new BitIndexes(indexCapacity);
        indexer = new StructuralIndexer(bitIndexes);
    }

    /**
     * Installs a native stage 1 delegate. When set, {@link #stage1} will try the native path
     * first, falling back to the Java {@link StructuralIndexer} on failure.
     */
    public void setNativeDelegate(Stage1Delegate delegate) {
        this.nativeDelegate = delegate;
    }

    /**
     * Runs stage 1 (SIMD structural indexing + UTF-8 validation) over the entire
     * {@code buffer[0..len)}. The buffer must have at least {@value PADDING} bytes of readable
     * space past {@code len} (need not be zeroed — the indexer's remainder handling pads
     * internally).
     *
     * <p>If a native delegate is installed, it is tried first. On any failure, the method
     * falls back to the Java {@link StructuralIndexer}.
     *
     * <p>After this call, use {@link #prepareDocumentWindow} to set up per-document windows.
     */
    public void stage1(byte[] buffer, int len) {
        if (nativeDelegate != null) {
            try {
                nativeDelegate.index(buffer, len, bitIndexes);
                this.stage1Done = true;
                this.nextSearchFrom = 0;
                this.savedSentinelPos = -1;
                return;
            } catch (Exception e) {
                // fall through to Java path
            }
        }
        Utf8Validator.validate(buffer, len);
        bitIndexes.ensureCapacity(len + 1);
        bitIndexes.reset();
        indexer.indexAppend(buffer, len);
        this.stage1Done = true;
        this.nextSearchFrom = 0;
        this.savedSentinelPos = -1;
    }

    /**
     * Sets up the {@link BitIndexes} read window for the document at
     * {@code buffer[docOffset..docOffset+docLen)} without building a tape.
     * After this call, the caller can walk {@link #bitIndexes()} directly.
     *
     * <p>Documents must be prepared in ascending offset order.
     *
     * @param docOffset byte offset of the document start within the batch buffer
     * @param docLen    length of the document in bytes
     */
    public void prepareDocumentWindow(int docOffset, int docLen) {
        if (!stage1Done) {
            throw new IllegalStateException("stage1() must be called before prepareDocumentWindow()");
        }

        restoreSentinel();

        int totalIndices = bitIndexes.writeCount();
        int from = bitIndexes.findFirstIndexAtOrAfter(nextSearchFrom, docOffset);
        int docEnd = docOffset + docLen;

        int to = from;
        while (to < totalIndices && bitIndexes.getIndexAt(to) < docEnd) {
            to++;
        }
        nextSearchFrom = to;

        if (to <= totalIndices) {
            savedSentinelPos = to;
            savedSentinelValue = (to < totalIndices) ? bitIndexes.getIndexAt(to) : 0;
            bitIndexes.writeSentinel(to, bitIndexes.getIndexAt(from));
        } else {
            savedSentinelPos = -1;
        }

        bitIndexes.setReadWindow(from, to);
    }

    /**
     * Returns the underlying {@link BitIndexes} for direct access by a fused walker.
     */
    public BitIndexes bitIndexes() {
        return bitIndexes;
    }

    /** Restores any sentinel that was written by a previous {@link #prepareDocumentWindow} call. */
    private void restoreSentinel() {
        if (savedSentinelPos >= 0) {
            bitIndexes.writeSentinel(savedSentinelPos, savedSentinelValue);
            savedSentinelPos = -1;
        }
    }

    /**
     * Ensures the internal {@link BitIndexes} can hold at least {@code minCapacity} entries.
     */
    public void ensureIndexCapacity(int minCapacity) {
        bitIndexes.ensureCapacity(minCapacity);
    }
}
