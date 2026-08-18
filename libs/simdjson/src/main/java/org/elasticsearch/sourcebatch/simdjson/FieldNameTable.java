/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.sourcebatch.simdjson;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe field name canonicalization table using a parent/child pattern inspired by
 * Jackson's {@code ByteQuadsCanonicalizer}.
 *
 * <p>A single <strong>root</strong> instance is shared across all threads (e.g. one per node
 * or one per index). Each parsing thread obtains a <strong>child</strong> via
 * {@link #makeChild()}, which starts with a read-only snapshot of the parent's entries.
 * Lookups hit the snapshot first (zero allocation on hit). New names discovered during
 * parsing are added to a thread-local overflow area. When parsing is done, the child calls
 * {@link Child#release()} to atomically merge new entries back into the parent, making them
 * available to all future children.
 *
 * <p>The root is thread-safe (all mutations go through {@link AtomicReference#compareAndSet}).
 * Children are <strong>not</strong> thread-safe and must be confined to a single thread.
 *
 * <p>Hash table layout uses open addressing with linear probing. Short keys (≤ 16 bytes)
 * are compared via packed int quads; longer keys use {@code Arrays.equals} on stored
 * byte[] copies.
 */
public final class FieldNameTable {

    public static final int CAPACITY = 2048;
    public static final int MAX_COUNT = CAPACITY * 3 / 4;
    public static final int MAX_INLINE_BYTES = 16;
    public static final int MAX_INLINE_QUADS = MAX_INLINE_BYTES / Integer.BYTES;
    public static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Immutable snapshot of the hash table state, shared between parent and children.
     */
    record Snapshot(String[] names, int[] hashes, int[] lens, int[] quads, byte[][] keys, int count) {
        Snapshot() {
            this(new String[CAPACITY], new int[CAPACITY], new int[CAPACITY], new int[CAPACITY * MAX_INLINE_QUADS], new byte[CAPACITY][], 0);
        }

        Snapshot copy() {
            return new Snapshot(
                Arrays.copyOf(names, names.length),
                Arrays.copyOf(hashes, hashes.length),
                Arrays.copyOf(lens, lens.length),
                Arrays.copyOf(quads, quads.length),
                Arrays.copyOf(keys, keys.length),
                count
            );
        }
    }

    private final AtomicReference<Snapshot> shared = new AtomicReference<>(new Snapshot());

    /**
     * Creates a root {@link FieldNameTable}. One instance should be shared across all threads
     * that parse documents with the same (or overlapping) field name schemas.
     */
    public FieldNameTable() {}

    /**
     * Creates a thread-confined child that starts with the current parent snapshot.
     * The child must be used by a single thread and should call {@link Child#release()}
     * when done to merge any new entries back.
     */
    public Child makeChild() {
        return new Child(this, shared.get());
    }

    /**
     * Merges new entries from a child back into the shared snapshot. Uses CAS; if another
     * thread merged concurrently, this merge is silently dropped (the entries will be
     * re-discovered and re-merged by a future child — same strategy as Jackson).
     */
    void mergeChild(Snapshot childSnapshot, Snapshot parentSnapshot) {
        if (childSnapshot.count() <= parentSnapshot.count()) {
            return;
        }
        shared.compareAndSet(parentSnapshot, childSnapshot);
    }

    /**
     * Thread-confined child of a {@link FieldNameTable}. Lookups check the parent snapshot
     * first; new names are inserted into a local copy (copy-on-write). Call {@link #release()}
     * when done to merge new entries back to the parent.
     *
     * <p>Instances are designed to be reused across multiple documents on the same thread
     * (e.g. pooled in a {@code ThreadLocal}). Call {@link #release()} between batches to
     * share discoveries, then the child can be reused (it will pick up the latest snapshot
     * on the next {@link #lookupName} call after release).
     */
    public static final class Child {

        private final FieldNameTable parent;
        private Snapshot parentSnapshot;

        /** Direct array access for hot-path consumers (e.g. {@code SimdJsonDirectWalker}). */
        public final String[] names;
        public final int[] hashes;
        public final int[] lens;
        public final int[] quads;
        public final byte[][] keys;

        public int count;
        public boolean dirty;

        Child(FieldNameTable parent, Snapshot snapshot) {
            this.parent = parent;
            this.parentSnapshot = snapshot;
            this.names = Arrays.copyOf(snapshot.names(), snapshot.names().length);
            this.hashes = Arrays.copyOf(snapshot.hashes(), snapshot.hashes().length);
            this.lens = Arrays.copyOf(snapshot.lens(), snapshot.lens().length);
            this.quads = Arrays.copyOf(snapshot.quads(), snapshot.quads().length);
            this.keys = Arrays.copyOf(snapshot.keys(), snapshot.keys().length);
            this.count = snapshot.count();
            this.dirty = false;
        }

        /**
         * Refreshes the child's arrays from a newer parent snapshot, merging in any entries
         * that other threads have added since this child was last refreshed.
         */
        private void refreshFromSnapshot(Snapshot s) {
            if (s.count() > this.count) {
                System.arraycopy(s.names(), 0, names, 0, names.length);
                System.arraycopy(s.hashes(), 0, hashes, 0, hashes.length);
                System.arraycopy(s.lens(), 0, lens, 0, lens.length);
                System.arraycopy(s.quads(), 0, quads, 0, quads.length);
                System.arraycopy(s.keys(), 0, keys, 0, keys.length);
                this.count = s.count();
            }
            this.dirty = false;
        }

        /**
         * Returns the canonical {@link String} for the field name at {@code buf[off, off+len)},
         * consulting the cache. Cache hits return zero allocation; misses decode UTF-8 and
         * store the result.
         */
        public String lookupName(byte[] buf, int off, int len) {
            int h = hashName(buf, off, len);
            int slot = h & (CAPACITY - 1);
            for (int i = slot;; i = (i + 1) & (CAPACITY - 1)) {
                int sh = hashes[i];
                if (sh == 0) {
                    String s = new String(buf, off, len, StandardCharsets.UTF_8);
                    if (count < MAX_COUNT) {
                        dirty = true;
                        hashes[i] = h;
                        lens[i] = len;
                        names[i] = s;
                        if (len <= MAX_INLINE_BYTES) {
                            storeInlineQuads(i, buf, off, len);
                        } else {
                            keys[i] = Arrays.copyOfRange(buf, off, off + len);
                        }
                        count++;
                    }
                    return s;
                }
                if (sh == h && lens[i] == len && keysMatch(i, buf, off, len)) {
                    return names[i];
                }
            }
        }

        private boolean keysMatch(int i, byte[] buf, int off, int len) {
            if (len <= MAX_INLINE_BYTES) {
                int base = i * MAX_INLINE_QUADS;
                int fullQuads = len >>> 2;
                int tail = len & 3;
                for (int q = 0; q < fullQuads; q++) {
                    if (quads[base + q] != (int) INT_HANDLE.get(buf, off + q * Integer.BYTES)) {
                        return false;
                    }
                }
                int tailOff = off + fullQuads * Integer.BYTES;
                int storedTail = quads[base + fullQuads];
                return switch (tail) {
                    case 0 -> true;
                    case 1 -> (storedTail & 0xFF) == (buf[tailOff] & 0xFF);
                    case 2 -> (storedTail & 0xFFFF) == ((buf[tailOff] & 0xFF) | ((buf[tailOff + 1] & 0xFF) << 8));
                    case 3 -> storedTail == ((buf[tailOff] & 0xFF) | ((buf[tailOff + 1] & 0xFF) << 8) | ((buf[tailOff + 2] & 0xFF) << 16));
                    default -> throw new AssertionError();
                };
            }
            byte[] key = keys[i];
            return Arrays.equals(key, 0, key.length, buf, off, off + len);
        }

        private void storeInlineQuads(int i, byte[] buf, int off, int len) {
            int base = i * MAX_INLINE_QUADS;
            int fullQuads = len >>> 2;
            int tail = len & 3;
            for (int q = 0; q < fullQuads; q++) {
                quads[base + q] = (int) INT_HANDLE.get(buf, off + q * Integer.BYTES);
            }
            if (tail > 0) {
                int tailOff = off + fullQuads * Integer.BYTES;
                int t = buf[tailOff] & 0xFF;
                if (tail >= 2) t |= (buf[tailOff + 1] & 0xFF) << 8;
                if (tail == 3) t |= (buf[tailOff + 2] & 0xFF) << 16;
                quads[base + fullQuads] = t;
            }
        }

        /**
         * Merges any new entries back to the parent and refreshes from any entries other
         * threads may have merged. The child can be reused after this call.
         */
        public void release() {
            if (dirty) {
                Snapshot childSnap = new Snapshot(
                    Arrays.copyOf(names, names.length),
                    Arrays.copyOf(hashes, hashes.length),
                    Arrays.copyOf(lens, lens.length),
                    Arrays.copyOf(quads, quads.length),
                    Arrays.copyOf(keys, keys.length),
                    count
                );
                parent.mergeChild(childSnap, parentSnapshot);
                parentSnapshot = parent.shared.get();
                refreshFromSnapshot(parentSnapshot);
            } else {
                Snapshot latest = parent.shared.get();
                if (latest != parentSnapshot) {
                    parentSnapshot = latest;
                    refreshFromSnapshot(latest);
                }
            }
        }
    }

    private static final long WY_SECRET0 = 0xa0761d6478bd642fL;
    private static final long WY_SECRET1 = 0xe7037ed1a0b428dbL;
    private static final long WY_SECRET2 = 0x8ebc6af09c88c6e3L;

    private static long wymix(long a, long b) {
        long lo = a * b;
        long hi = Math.unsignedMultiplyHigh(a, b);
        return lo ^ hi;
    }

    private static long readLE8(byte[] buf, int off) {
        return (long) LONG_HANDLE.get(buf, off);
    }

    private static long readSmall(byte[] buf, int off, int len) {
        if (len >= 4) {
            long lo = Integer.toUnsignedLong((int) INT_HANDLE.get(buf, off));
            long hi = Integer.toUnsignedLong((int) INT_HANDLE.get(buf, off + len - 4));
            return lo | (hi << 32);
        }
        if (len > 0) {
            int a = buf[off] & 0xFF;
            int b = buf[off + (len >>> 1)] & 0xFF;
            int c = buf[off + len - 1] & 0xFF;
            return (a << 16) | (b << 8) | c;
        }
        return 0;
    }

    /**
     * Computes a 32-bit hash of {@code buf[off, off+len)} using a wyhash-style algorithm.
     * Uses {@link Math#unsignedMultiplyHigh} as the core mixing primitive — a single
     * instruction on x86-64 and AArch64. For field names ≤ 8 bytes (the common case),
     * only 1 multiply-mix is needed. Returns 1 instead of 0 so that 0 can serve as the
     * empty-slot sentinel.
     */
    public static int hashName(byte[] buf, int off, int len) {
        long seed = WY_SECRET0;
        long a, b;

        if (len <= 8) {
            a = readSmall(buf, off, len);
            b = 0;
        } else if (len <= 16) {
            a = readLE8(buf, off);
            b = readLE8(buf, off + len - 8);
        } else {
            int pos = off;
            int rem = len;
            a = 0;
            b = 0;
            while (rem > 16) {
                seed = wymix(readLE8(buf, pos) ^ WY_SECRET1, readLE8(buf, pos + 8) ^ seed);
                pos += 16;
                rem -= 16;
            }
            a = readLE8(buf, pos);
            b = readLE8(buf, off + len - 8);
        }

        long h = wymix(a ^ WY_SECRET1, b ^ seed) ^ WY_SECRET2 ^ len;
        h = wymix(h, h);
        int h32 = (int) (h ^ (h >>> 32));
        return h32 == 0 ? 1 : h32;
    }

    /**
     * Scans for the closing quote and computes the wyhash in a single pass. Each 8-byte word
     * is read once, checked for quote/backslash, and the same bytes are fed into
     * {@link #hashName} to avoid re-reading from memory.
     *
     * <p>Returns the hash in the upper 32 bits and the field name length in the lower 32 bits,
     * packed as a single long. Returns -1 if a backslash is found.
     *
     * @param buf      source buffer (must have at least 7 bytes of readable slack past the
     *                 closing quote — guaranteed by the SIMD padding in the batch buffer)
     * @param startIdx byte index of the first character after the opening quote
     * @return {@code ((long)hash << 32) | len}, or -1 if the name contains a backslash
     */
    public static long scanAndHash(byte[] buf, int startIdx) {
        int pos = startIdx;

        while (true) {
            long word = readLE8(buf, pos);
            long xq = word ^ 0x2222222222222222L;
            long xb = word ^ 0x5C5C5C5C5C5C5C5CL;
            long qh = (xq - 0x0101010101010101L) & ~xq & 0x8080808080808080L;
            long bh = (xb - 0x0101010101010101L) & ~xb & 0x8080808080808080L;

            if ((qh | bh) != 0) {
                if (bh != 0 && (qh == 0 || (Long.numberOfTrailingZeros(bh) <= Long.numberOfTrailingZeros(qh)))) {
                    return -1;
                }
                int len = (pos - startIdx) + (Long.numberOfTrailingZeros(qh) >>> 3);
                int h = hashName(buf, startIdx, len);
                return ((long) h << 32) | (len & 0xFFFFFFFFL);
            }
            pos += 8;
        }
    }
}
