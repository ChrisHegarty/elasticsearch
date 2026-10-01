/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.IntFunction;

/**
 * Reusable, lazily-grown off-heap scratch buffer of {@code count} elements of a given
 * {@link ValueLayout} -- e.g. an array of native addresses ({@link ValueLayout#ADDRESS}) or an
 * array of {@code float} scores ({@link ValueLayout#JAVA_FLOAT}).
 *
 * <p>Used so that bulk native calls can be bound without {@code @Critical}: a plain downcall does
 * the usual safepoint/handshake transition on entry and exit, so a thread executing it never blocks
 * an unrelated thread's shared-{@code Arena} close (see JDK-8310644) for the duration of the call,
 * however long that call happens to run (e.g. due to a page fault against {@code mmap}'d index
 * data). A {@code @Critical}-bound call cannot be interrupted that way, which is what makes it unsafe
 * to use for calls whose duration isn't tightly bounded. Buffers obtained from this class let such
 * calls take a native segment in place of an argument that would otherwise require heap access (e.g.
 * a {@code float[]} wrapped via {@code MemorySegment.ofArray}).
 *
 * <p>Not thread-safe; instances must not be shared across threads.
 */
public final class OffHeapSegmentScratch implements IntFunction<MemorySegment> {

    private final ValueLayout layout;
    private MemorySegment seg;

    public OffHeapSegmentScratch(ValueLayout layout) {
        this.layout = layout;
    }

    /**
     * Returns a {@link MemorySegment} of at least {@code count} slots of this instance's
     * {@link ValueLayout}. The buffer may be larger than requested (it is grown lazily and never
     * shrunk across calls); callers must respect their own {@code count} when reading or writing.
     * Always returns the same backing segment for a given instance until a larger one is needed.
     *
     * <p>Segments are returned from an auto arena, so they are garbage-collected; there is deliberately
     * no explicit {@code close()} here, and therefore no shared-arena-close handshake to worry about.
     */
    @Override
    public MemorySegment apply(int count) {
        long needed = (long) count * layout.byteSize();
        if (seg == null || seg.byteSize() < needed) {
            // No need to call close() here, or to keep a reference to the Arena: Arena#ofAuto is
            // not closeable, and returns MemorySegments whose lifetime is managed automatically by GC.
            seg = Arena.ofAuto().allocate(needed, layout.byteAlignment());
        }
        return seg;
    }
}
