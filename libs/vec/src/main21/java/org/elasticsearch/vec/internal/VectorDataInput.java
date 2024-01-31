/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.vec.internal;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

public class VectorDataInput implements Closeable {

    static final ValueLayout.OfInt LAYOUT_LE_INT = JAVA_INT_UNALIGNED.withOrder(LITTLE_ENDIAN);

    private final Arena arena;
    private final MemorySegment segment;
    private final int dims;

    VectorDataInput(Arena arena, MemorySegment segment, int dims) {
        this.arena = arena;
        this.segment = segment;
        this.dims = dims;
    }

    MemorySegment addressFor(int offset) {
        return segment.asSlice(offset, dims);
    }

    float readFloat(long offset) {
        return Float.intBitsToFloat(segment.get(LAYOUT_LE_INT, offset));
    }

    public static VectorDataInput createVectorDataInput(Path path)
        throws IOException {
        final String resourceDescription = "MemorySegmentIndexInput(path=\"" + path.toString() + "\")";

        // Work around for JDK-8259028: we need to unwrap our test-only file system layers
        // path = Unwrappable.unwrapAll(path);

//        boolean success = false;
//        final Arena arena = Arena.ofShared();
//        try (var fc = FileChannel.open(path, StandardOpenOption.READ)) {
//            final long fileSize = fc.size();
//            final IndexInput in =
//                VectorDataInput.newInstance(
//                    resourceDescription,
//                    arena,
//                    map(arena, resourceDescription, fc, chunkSizePower, fileSize),
//                    fileSize,
//                    chunkSizePower);
//            success = true;
//            return in;
//        } finally {
//            if (success == false) {
//                arena.close();
//            }
//        }
        return null;
    }

    @Override
    public void close() throws IOException {
        arena.close();
    }
}
