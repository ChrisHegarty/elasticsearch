/*
 * @notice
 *
 * Copyright 2021-2024 The simdjson-java contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Based on a modification of https://github.com/simdjson/simdjson-java,
 * licensed under the Apache License 2.0.
 */

package org.elasticsearch.sourcebatch.simdjson;

public class BitIndexes {

    private int[] indexes;

    private int writeIdx;
    private int readIdx;
    private int readEnd;

    BitIndexes(int capacity) {
        indexes = new int[capacity];
    }

    void write(int blockIndex, long bits) {
        if (bits == 0) {
            return;
        }

        int idx = blockIndex - 64;
        int cnt = Long.bitCount(bits);
        for (int i = 0; i < 8; i++) {
            indexes[i + writeIdx] = idx + Long.numberOfTrailingZeros(bits);
            bits = clearLowestBit(bits);
        }

        if (cnt > 8) {
            for (int i = 8; i < 16; i++) {
                indexes[i + writeIdx] = idx + Long.numberOfTrailingZeros(bits);
                bits = clearLowestBit(bits);
            }
            if (cnt > 16) {
                int i = 16;
                do {
                    indexes[i + writeIdx] = idx + Long.numberOfTrailingZeros(bits);
                    bits = clearLowestBit(bits);
                    i++;
                } while (i < cnt);
            }
        }
        writeIdx += cnt;
    }

    private long clearLowestBit(long bits) {
        return bits & (bits - 1);
    }

    public void advance() {
        readIdx++;
    }

    public int getAndAdvance() {
        assert readIdx <= readEnd;
        return indexes[readIdx++];
    }

    int getLast() {
        return indexes[readEnd - 1];
    }

    int advanceAndGet() {
        assert readIdx + 1 <= readEnd;
        return indexes[++readIdx];
    }

    public int peek() {
        assert readIdx <= readEnd;
        return indexes[readIdx];
    }

    boolean hasNext() {
        return readEnd > readIdx;
    }

    public boolean isEnd() {
        return readEnd == readIdx;
    }

    boolean isPastEnd() {
        return readIdx > readEnd;
    }

    void finish() {
        // If we go past the end of the detected structural indexes, it means we are dealing with an invalid JSON.
        // Thus, we need to stop processing immediately and throw an exception. To avoid checking after every increment
        // of readIdx whether this has happened, we jump to the first structural element. This should produce the
        // desired outcome, i.e., an iterator should detect invalid JSON. To understand how this works, let's first
        // exclude primitive values (numbers, strings, booleans, nulls) from the scope of possible JSON documents. We
        // can do this because, when these values are parsed, the length of the input buffer is verified, ensuring we
        // never go past its end. Therefore, we can focus solely on objects and arrays. Since we always check that if
        // the first character is '{', the last one must be '}', and if the first character is '[', the last one must
        // be ']', we know that if we've reached beyond the buffer without crashing, the input is either '{...}' or '[...]'.
        // Thus, if we jump to the first structural element, we will generate either '{...}{' or '[...]['. Both of these
        // are invalid sequences and will be detected by the iterator, which will then stop processing and throw an
        // exception informing about the invalid JSON.
        indexes[writeIdx] = 0;
        readEnd = writeIdx;
    }

    public void reset() {
        writeIdx = 0;
        readIdx = 0;
        readEnd = 0;
    }

    /** Returns the total number of structural indices written so far. */
    public int writeCount() {
        return writeIdx;
    }

    /**
     * Restricts the read window to {@code [from, to)} within the underlying index array.
     * The sentinel at {@code indexes[to]} must already be set (e.g. via {@link #finish()} or
     * by storing the first index of the next document).
     */
    public void setReadWindow(int from, int to) {
        this.readIdx = from;
        this.readEnd = to;
    }

    /**
     * Ensures the internal array can hold at least {@code minCapacity} index entries.
     * Grows geometrically if needed.
     */
    public void ensureCapacity(int minCapacity) {
        if (indexes.length < minCapacity) {
            int newLen = Math.max(indexes.length * 2, minCapacity);
            int[] bigger = new int[newLen];
            System.arraycopy(indexes, 0, bigger, 0, writeIdx);
            indexes = bigger;
        }
    }

    /**
     * Returns the byte-offset value stored at position {@code pos} in the index array.
     * Does not affect the read cursor.
     */
    public int getIndexAt(int pos) {
        return indexes[pos];
    }

    /**
     * Writes a sentinel value at position {@code pos}. Used by batch parsing to mark
     * the end of a per-document sub-range so that overrunning produces a detectable
     * invalid-JSON sequence.
     */
    public void writeSentinel(int pos, int sentinelValue) {
        indexes[pos] = sentinelValue;
    }

    /**
     * Finds the first structural index whose byte offset is {@code >= docStart}. Uses a linear
     * scan from {@code searchFrom} since documents are processed in order and the target is
     * typically very close to the current position.
     */
    public int findFirstIndexAtOrAfter(int searchFrom, int docStart) {
        for (int i = searchFrom; i < writeIdx; i++) {
            if (indexes[i] >= docStart) {
                return i;
            }
        }
        return writeIdx;
    }

    /**
     * Returns the raw index array for direct bulk writes (e.g. from a native FFI call).
     * The caller must also call {@link #setWriteIdx(int)} after writing.
     */
    public int[] rawIndexes() {
        return indexes;
    }

    /**
     * Sets the write cursor position. Used after a native FFI call bulk-writes
     * structural indices into the raw array returned by {@link #rawIndexes()}.
     */
    public void setWriteIdx(int idx) {
        this.writeIdx = idx;
    }
}
