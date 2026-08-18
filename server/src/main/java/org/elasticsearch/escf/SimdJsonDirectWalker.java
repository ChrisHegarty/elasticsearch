/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.escf;

import org.elasticsearch.sourcebatch.LeafSink;
import org.elasticsearch.sourcebatch.SourceBatchEncodeHelper;
import org.elasticsearch.sourcebatch.SourceValueType;
import org.elasticsearch.sourcebatch.simdjson.BitIndexes;
import org.elasticsearch.sourcebatch.simdjson.FieldNameTable;
import org.elasticsearch.sourcebatch.simdjson.JsonParsingException;
import org.elasticsearch.sourcebatch.simdjson.StringParser;
import org.elasticsearch.xcontent.XContentString;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.elasticsearch.sourcebatch.simdjson.CharacterUtils.isStructuralOrWhitespace;
import static org.elasticsearch.sourcebatch.simdjson.FieldNameTable.CAPACITY;
import static org.elasticsearch.sourcebatch.simdjson.FieldNameTable.MAX_COUNT;
import static org.elasticsearch.sourcebatch.simdjson.FieldNameTable.MAX_INLINE_BYTES;
import static org.elasticsearch.sourcebatch.simdjson.FieldNameTable.MAX_INLINE_QUADS;

/**
 * Fused stage-2 + token-walk that reads structural indices ({@link BitIndexes}) directly and
 * populates an {@link EscfRowBuffer} directly, without building an intermediate representation.
 *
 * <p>Strings go from the source buffer directly into the name cache or into {@code UTF8Bytes}
 * instances; numbers are parsed inline.
 *
 * <p>Field name lookup accesses the {@link FieldNameTable.Child}'s arrays directly to avoid
 * method call overhead on the hot path. Cross-thread sharing happens at batch boundaries
 * via {@link #releaseNames()}.
 *
 * <p><strong>Not thread-safe.</strong> Pool one instance per thread.
 */
final class SimdJsonDirectWalker {

    private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

    private static final int DEFAULT_MAX_DEPTH = 64;

    private final FieldNameTable.Child nameCache;
    private final StringParser stringParser = new StringParser();
    private final org.elasticsearch.sourcebatch.simdjson.DoubleParser doubleParser =
        new org.elasticsearch.sourcebatch.simdjson.DoubleParser();
    private final int maxDepth;
    private byte[] stringBuf = new byte[4096];
    private int currentDepth;

    SimdJsonDirectWalker(FieldNameTable.Child nameCache) {
        this(nameCache, DEFAULT_MAX_DEPTH);
    }

    SimdJsonDirectWalker(FieldNameTable.Child nameCache, int maxDepth) {
        this.nameCache = nameCache;
        this.maxDepth = maxDepth;
    }

    /**
     * Walks the structural indices for a single JSON object document located at
     * {@code buffer[docOffset..docOffset+docLen)}, populating {@code row} directly.
     *
     * <p>The {@code bitIndexes} read window must already be set to cover this document's
     * structural indices (as done by
     * {@link org.elasticsearch.sourcebatch.simdjson.SimdJsonBatchParser#prepareDocumentWindow}).
     *
     * @param rawTextMode if true, numbers and booleans are passed to the sink as raw text
     *                    via {@link LeafSink#onTextPrimitive} instead of typed callbacks
     */
    void walkDocument(
        byte[] buffer,
        int docLen,
        BitIndexes bitIndexes,
        EscfRowBuffer row,
        EscfBatchBuilder backend,
        LeafSink sink,
        boolean rawTextMode
    ) {
        if (bitIndexes.isEnd()) {
            throw new JsonParsingException("No structural element found.");
        }

        int idx = bitIndexes.getAndAdvance();
        if (buffer[idx] != '{') {
            throw new JsonParsingException("Expected document to start with '{' but got '" + (char) buffer[idx] + "'");
        }

        if (buffer[bitIndexes.peek()] == '}') {
            bitIndexes.advance();
            return;
        }

        currentDepth = 0;
        walkObject(buffer, bitIndexes, row, backend, sink, rawTextMode);
    }

    void walkDocument(byte[] buffer, int docLen, BitIndexes bitIndexes, EscfRowBuffer row, EscfBatchBuilder backend, LeafSink sink) {
        walkDocument(buffer, docLen, bitIndexes, row, backend, sink, false);
    }

    /**
     * Merges any newly discovered field names back to the shared parent table.
     * Should be called after processing a batch of documents.
     */
    void releaseNames() {
        nameCache.release();
    }

    private void walkObject(byte[] buffer, BitIndexes bi, EscfRowBuffer row, EscfBatchBuilder backend, LeafSink sink, boolean rawTextMode) {
        if (++currentDepth > maxDepth) {
            throw new JsonParsingException("Document exceeds maximum nesting depth of " + maxDepth);
        }
        final boolean firePathSink = sink != LeafSink.NO_OP;

        try {
            while (true) {
                int keyIdx = bi.getAndAdvance();
                if (buffer[keyIdx] == '}') {
                    return;
                }
                if (buffer[keyIdx] != '"') {
                    throw new JsonParsingException("Expected field name or '}' but got '" + (char) buffer[keyIdx] + "'");
                }

                String fieldName = resolveFieldName(buffer, keyIdx);

                int colonIdx = bi.getAndAdvance();
                if (buffer[colonIdx] != ':') {
                    throw new JsonParsingException("Missing colon after key in object");
                }

                int valIdx = bi.getAndAdvance();
                byte valByte = buffer[valIdx];

                switch (valByte) {
                    case '{' -> {
                        if (buffer[bi.peek()] == '}') {
                            bi.advance();
                            row.emptyObject(fieldName);
                        } else {
                            row.startObject(fieldName);
                            walkObject(buffer, bi, row, backend, sink, rawTextMode);
                            row.endObject();
                        }
                    }
                    case '[' -> {
                        handleArray(buffer, bi, row, fieldName);
                    }
                    case '"' -> {
                        int off = valIdx + 1;
                        int len = scalarStringLength(buffer, off);
                        boolean hasEscape = containsBackslash(buffer, off, len);
                        if (hasEscape) {
                            int parsed = stringParser.parseString(buffer, valIdx, ensureStringBuf(len));
                            byte[] copy = Arrays.copyOf(stringBuf, parsed);
                            int colIdx = row.stringField(fieldName, copy, 0, parsed);
                            if (firePathSink) {
                                sink.onTextPrimitive(
                                    colIdx,
                                    backend.columnPath(colIdx),
                                    SourceValueType.STRING,
                                    new XContentString.UTF8Bytes(copy, 0, parsed)
                                );
                            }
                        } else {
                            int colIdx = row.stringField(fieldName, buffer, off, len);
                            if (firePathSink) {
                                sink.onTextPrimitive(
                                    colIdx,
                                    backend.columnPath(colIdx),
                                    SourceValueType.STRING,
                                    new XContentString.UTF8Bytes(buffer, off, len)
                                );
                            }
                        }
                    }
                    case 't' -> {
                        validateTrue(buffer, valIdx);
                        int colIdx = row.booleanField(fieldName, true);
                        if (rawTextMode) {
                            sink.onTextPrimitive(
                                colIdx,
                                backend.columnPath(colIdx),
                                SourceValueType.TRUE,
                                new XContentString.UTF8Bytes(buffer, valIdx, 4)
                            );
                        } else if (firePathSink) {
                            sink.onBooleanPrimitive(colIdx, backend.columnPath(colIdx), true);
                        }
                    }
                    case 'f' -> {
                        validateFalse(buffer, valIdx);
                        int colIdx = row.booleanField(fieldName, false);
                        if (rawTextMode) {
                            sink.onTextPrimitive(
                                colIdx,
                                backend.columnPath(colIdx),
                                SourceValueType.FALSE,
                                new XContentString.UTF8Bytes(buffer, valIdx, 5)
                            );
                        } else if (firePathSink) {
                            sink.onBooleanPrimitive(colIdx, backend.columnPath(colIdx), false);
                        }
                    }
                    case 'n' -> {
                        validateNull(buffer, valIdx);
                        row.nullField(fieldName);
                    }
                    case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                        handleNumber(buffer, valIdx, fieldName, row, backend, sink, firePathSink, rawTextMode);
                    }
                    default -> throw new JsonParsingException("Unexpected value byte: " + (char) valByte);
                }

                int sep = bi.getAndAdvance();
                if (buffer[sep] == '}') {
                    return;
                }
                if (buffer[sep] != ',') {
                    throw new JsonParsingException("Expected ',' or '}' but got '" + (char) buffer[sep] + "'");
                }
            }
        } finally {
            currentDepth--;
        }
    }

    private static final long QUOTE_XOR = 0x2222222222222222L;
    private static final long BACKSLASH_XOR = 0x5C5C5C5C5C5C5C5CL;
    private static final long LO_BITS = 0x0101010101010101L;
    private static final long HI_BITS = 0x8080808080808080L;
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Resolves a field name by scanning for the closing quote using 8-byte word reads,
     * then computing the hash — all inline to avoid cross-method call overhead.
     */
    private String resolveFieldName(byte[] buffer, int quoteIdx) {
        int start = quoteIdx + 1;
        int pos = start;

        while (true) {
            long word = (long) LONG_LE.get(buffer, pos);
            long xq = word ^ QUOTE_XOR;
            long xb = word ^ BACKSLASH_XOR;
            long qh = (xq - LO_BITS) & ~xq & HI_BITS;
            long bh = (xb - LO_BITS) & ~xb & HI_BITS;

            if ((qh | bh) != 0) {
                if (bh != 0 && (qh == 0 || (Long.numberOfTrailingZeros(bh) <= Long.numberOfTrailingZeros(qh)))) {
                    // Backslash found — fall back to full string parse
                    int end = start;
                    while (buffer[end] != '"') {
                        if (buffer[end] == '\\') end += 2;
                        else end++;
                    }
                    int parsed = stringParser.parseString(buffer, quoteIdx, ensureStringBuf(end - start));
                    return lookupNameWithHash(stringBuf, 0, parsed, FieldNameTable.hashName(stringBuf, 0, parsed));
                }
                int len = (pos - start) + (Long.numberOfTrailingZeros(qh) >>> 3);
                int h = FieldNameTable.hashName(buffer, start, len);
                return lookupNameWithHash(buffer, start, len, h);
            }
            pos += 8;
        }
    }

    /**
     * Name cache lookup with a pre-computed hash. Accesses the child's arrays directly
     * to avoid cross-module method call overhead on the hot path.
     */
    private String lookupNameWithHash(byte[] buf, int off, int len, int h) {
        final int[] hashes = nameCache.hashes;
        final int[] lens = nameCache.lens;
        final String[] names = nameCache.names;

        int slot = h & (CAPACITY - 1);
        for (int i = slot;; i = (i + 1) & (CAPACITY - 1)) {
            int sh = hashes[i];
            if (sh == 0) {
                String s = new String(buf, off, len, StandardCharsets.UTF_8);
                if (nameCache.count < MAX_COUNT) {
                    nameCache.dirty = true;
                    hashes[i] = h;
                    lens[i] = len;
                    names[i] = s;
                    if (len <= MAX_INLINE_BYTES) {
                        storeInlineQuads(i, buf, off, len);
                    } else {
                        nameCache.keys[i] = Arrays.copyOfRange(buf, off, off + len);
                    }
                    nameCache.count++;
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
                if (nameCache.quads[base + q] != (int) INT_HANDLE.get(buf, off + q * Integer.BYTES)) {
                    return false;
                }
            }
            int tailOff = off + fullQuads * Integer.BYTES;
            int storedTail = nameCache.quads[base + fullQuads];
            return switch (tail) {
                case 0 -> true;
                case 1 -> (storedTail & 0xFF) == (buf[tailOff] & 0xFF);
                case 2 -> (storedTail & 0xFFFF) == ((buf[tailOff] & 0xFF) | ((buf[tailOff + 1] & 0xFF) << 8));
                case 3 -> storedTail == ((buf[tailOff] & 0xFF) | ((buf[tailOff + 1] & 0xFF) << 8) | ((buf[tailOff + 2] & 0xFF) << 16));
                default -> throw new AssertionError();
            };
        }
        byte[] key = nameCache.keys[i];
        return Arrays.equals(key, 0, key.length, buf, off, off + len);
    }

    private void storeInlineQuads(int i, byte[] buf, int off, int len) {
        int base = i * MAX_INLINE_QUADS;
        int fullQuads = len >>> 2;
        int tail = len & 3;
        for (int q = 0; q < fullQuads; q++) {
            nameCache.quads[base + q] = (int) INT_HANDLE.get(buf, off + q * Integer.BYTES);
        }
        if (tail > 0) {
            int tailOff = off + fullQuads * Integer.BYTES;
            int t = buf[tailOff] & 0xFF;
            if (tail >= 2) t |= (buf[tailOff + 1] & 0xFF) << 8;
            if (tail == 3) t |= (buf[tailOff + 2] & 0xFF) << 16;
            nameCache.quads[base + fullQuads] = t;
        }
    }

    private void handleNumber(
        byte[] buffer,
        int idx,
        String fieldName,
        EscfRowBuffer row,
        EscfBatchBuilder backend,
        LeafSink sink,
        boolean firePathSink,
        boolean rawTextMode
    ) {
        boolean negative = buffer[idx] == '-';
        int pos = negative ? idx + 1 : idx;

        long digits = 0;
        int digitStart = pos;
        byte ch = buffer[pos];
        while (ch >= '0' && ch <= '9') {
            digits = digits * 10 + (ch - '0');
            ch = buffer[++pos];
        }

        if (ch == '.' || ch == 'e' || ch == 'E') {
            handleFloatingPoint(buffer, idx, negative, digits, pos, fieldName, row, backend, sink, firePathSink, rawTextMode);
            return;
        }

        int digitCount = pos - digitStart;
        if (digitCount == 0) {
            throw new JsonParsingException("Invalid number at " + idx);
        }
        if (digitCount >= 19) {
            if (digitCount > 19 || (negative ? digits == Long.MIN_VALUE ? false : digits < 0 : digits < 0)) {
                throw new JsonParsingException("Number value is out of long range.");
            }
        }

        long val = negative ? -digits : digits;
        byte type = (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) ? SourceValueType.INT : SourceValueType.LONG;
        int colIdx = row.longField(fieldName, val);
        if (rawTextMode) {
            sink.onTextPrimitive(colIdx, backend.columnPath(colIdx), type, new XContentString.UTF8Bytes(buffer, idx, pos - idx));
        } else if (firePathSink) {
            sink.onLongPrimitive(colIdx, backend.columnPath(colIdx), type, val);
        }
    }

    private void handleFloatingPoint(
        byte[] buffer,
        int startIdx,
        boolean negative,
        long intDigits,
        int pos,
        String fieldName,
        EscfRowBuffer row,
        EscfBatchBuilder backend,
        LeafSink sink,
        boolean firePathSink,
        boolean rawTextMode
    ) {
        int digitsStartIdx = negative ? startIdx + 1 : startIdx;
        long digits = intDigits;
        long exponent = 0;
        int digitCountEnd = pos;

        if (buffer[pos] == '.') {
            pos++;
            int fracStart = pos;
            byte ch = buffer[pos];
            while (ch >= '0' && ch <= '9') {
                digits = digits * 10 + (ch - '0');
                ch = buffer[++pos];
            }
            exponent = fracStart - pos;
            digitCountEnd = pos;
        }

        if (buffer[pos] == 'e' || buffer[pos] == 'E') {
            pos++;
            boolean expNeg = false;
            if (buffer[pos] == '-') {
                expNeg = true;
                pos++;
            } else if (buffer[pos] == '+') {
                pos++;
            }
            long exp = 0;
            byte ch = buffer[pos];
            while (ch >= '0' && ch <= '9') {
                exp = exp * 10 + (ch - '0');
                ch = buffer[++pos];
            }
            exponent += expNeg ? -exp : exp;
        }

        int digitCount = digitCountEnd - digitsStartIdx;
        double val = doubleParser.parse(buffer, startIdx, negative, digitsStartIdx, digitCount, digits, exponent);

        int len = pos - startIdx;
        float fval = (float) val;
        byte type = ((double) fval == val) ? SourceValueType.FLOAT : SourceValueType.DOUBLE;
        int colIdx = row.doubleField(fieldName, val);
        if (rawTextMode) {
            sink.onTextPrimitive(colIdx, backend.columnPath(colIdx), type, new XContentString.UTF8Bytes(buffer, startIdx, len));
        } else if (firePathSink) {
            sink.onDoublePrimitive(colIdx, backend.columnPath(colIdx), type, val);
        }
    }

    private static final int ARRAY_INIT_CAP = 16;
    private int arrayDepth;

    private void handleArray(byte[] buffer, BitIndexes bi, EscfRowBuffer row, String fieldName) {
        SourceBatchEncodeHelper.PackedArray packed = parseArrayDirect(buffer, bi);
        row.arrayField(fieldName, packed.arrayType(), packed.packed());
    }

    private SourceBatchEncodeHelper.PackedArray parseArrayDirect(byte[] buffer, BitIndexes bi) {
        byte[] types = new byte[ARRAY_INIT_CAP];
        long[] numeric = new long[ARRAY_INIT_CAP];
        Object[] var = new Object[ARRAY_INIT_CAP];
        int count = 0;
        boolean forceUnion = false;

        while (true) {
            int idx = bi.getAndAdvance();
            byte b = buffer[idx];

            if (b == ']') break;

            if (count >= types.length) {
                int newCap = types.length * 2;
                types = Arrays.copyOf(types, newCap);
                numeric = Arrays.copyOf(numeric, newCap);
                var = Arrays.copyOf(var, newCap);
            }

            switch (b) {
                case '"' -> {
                    int off = idx + 1;
                    int len = scalarStringLength(buffer, off);
                    boolean hasEscape = containsBackslash(buffer, off, len);
                    if (hasEscape) {
                        int parsed = stringParser.parseString(buffer, idx, ensureStringBuf(len));
                        types[count] = SourceValueType.STRING;
                        var[count] = new XContentString.UTF8Bytes(Arrays.copyOf(stringBuf, parsed), 0, parsed);
                    } else {
                        types[count] = SourceValueType.STRING;
                        var[count] = new XContentString.UTF8Bytes(buffer, off, len);
                    }
                }
                case 't' -> {
                    validateTrue(buffer, idx);
                    types[count] = SourceValueType.TRUE;
                }
                case 'f' -> {
                    validateFalse(buffer, idx);
                    types[count] = SourceValueType.FALSE;
                }
                case 'n' -> {
                    validateNull(buffer, idx);
                    types[count] = SourceValueType.NULL;
                }
                case '{' -> {
                    types[count] = SourceValueType.KEY_VALUE;
                    var[count] = serializeKeyValueDirect(buffer, bi);
                    forceUnion = true;
                }
                case '[' -> {
                    SourceBatchEncodeHelper.PackedArray nested = parseArrayDirect(buffer, bi);
                    types[count] = nested.arrayType();
                    var[count] = nested.packed();
                    forceUnion = true;
                }
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    parseArrayNumber(buffer, idx, count, types, numeric);
                }
                default -> throw new JsonParsingException("Unexpected byte in array: " + (char) b);
            }
            count++;

            int sep = bi.getAndAdvance();
            if (buffer[sep] == ']') break;
            if (buffer[sep] != ',') {
                throw new JsonParsingException("Expected ',' or ']' in array but got '" + (char) buffer[sep] + "'");
            }
        }

        boolean useFixed = false;
        byte sharedType = 0;
        if (!forceUnion && count > 0) {
            sharedType = types[0];
            useFixed = true;
            for (int i = 1; i < count; i++) {
                if (types[i] != sharedType) {
                    useFixed = false;
                    break;
                }
            }
            if (useFixed && SourceValueType.elemDataSize(sharedType) == 0) {
                useFixed = false;
            }
        }

        byte[] packed;
        byte arrayType;
        if (useFixed) {
            packed = SourceBatchEncodeHelper.packFixedArray(sharedType, numeric, var, count);
            arrayType = SourceValueType.FIXED_ARRAY;
        } else {
            packed = SourceBatchEncodeHelper.packUnionArray(types, numeric, var, count);
            arrayType = SourceValueType.UNION_ARRAY;
        }
        Arrays.fill(var, 0, count, null);
        return new SourceBatchEncodeHelper.PackedArray(arrayType, packed);
    }

    private void parseArrayNumber(byte[] buffer, int idx, int elemIdx, byte[] types, long[] numeric) {
        boolean negative = buffer[idx] == '-';
        int pos = negative ? idx + 1 : idx;

        long digits = 0;
        int digitStart = pos;
        byte ch = buffer[pos];
        while (ch >= '0' && ch <= '9') {
            digits = digits * 10 + (ch - '0');
            ch = buffer[++pos];
        }

        if (ch == '.' || ch == 'e' || ch == 'E') {
            long exponent = 0;
            int digitCountEnd = pos;

            if (buffer[pos] == '.') {
                pos++;
                int fracStart = pos;
                ch = buffer[pos];
                while (ch >= '0' && ch <= '9') {
                    digits = digits * 10 + (ch - '0');
                    ch = buffer[++pos];
                }
                exponent = fracStart - pos;
                digitCountEnd = pos;
            }

            if (buffer[pos] == 'e' || buffer[pos] == 'E') {
                pos++;
                boolean expNeg = false;
                if (buffer[pos] == '-') {
                    expNeg = true;
                    pos++;
                } else if (buffer[pos] == '+') {
                    pos++;
                }
                long exp = 0;
                ch = buffer[pos];
                while (ch >= '0' && ch <= '9') {
                    exp = exp * 10 + (ch - '0');
                    ch = buffer[++pos];
                }
                exponent += expNeg ? -exp : exp;
            }

            int digitCount = digitCountEnd - digitStart;
            double val = doubleParser.parse(buffer, idx, negative, digitStart, digitCount, digits, exponent);
            float fval = (float) val;
            if ((double) fval == val) {
                types[elemIdx] = SourceValueType.FLOAT;
                numeric[elemIdx] = Float.floatToRawIntBits(fval);
            } else {
                types[elemIdx] = SourceValueType.DOUBLE;
                numeric[elemIdx] = Double.doubleToRawLongBits(val);
            }
        } else {
            long val = negative ? -digits : digits;
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                types[elemIdx] = SourceValueType.INT;
                numeric[elemIdx] = val;
            } else {
                types[elemIdx] = SourceValueType.LONG;
                numeric[elemIdx] = val;
            }
        }
    }

    /**
     * Serializes an object from the buffer into KEY_VALUE binary format (used inside arrays).
     * The buffer is positioned just after the opening '{'.
     */
    private byte[] serializeKeyValueDirect(byte[] buffer, BitIndexes bi) {
        org.elasticsearch.common.io.stream.BytesStreamOutput out = new org.elasticsearch.common.io.stream.BytesStreamOutput(64);
        try {
            if (buffer[bi.peek()] == '}') {
                bi.advance();
                return org.elasticsearch.common.bytes.BytesReference.toBytes(out.bytes());
            }
            while (true) {
                int keyIdx = bi.getAndAdvance();
                if (buffer[keyIdx] == '}') break;
                if (buffer[keyIdx] != '"') {
                    throw new JsonParsingException("Expected field name in nested object");
                }
                int keyStart = keyIdx + 1;
                int keyLen = scalarStringLength(buffer, keyStart);
                boolean keyEscaped = containsBackslash(buffer, keyStart, keyLen);
                byte[] keyBytes;
                if (keyEscaped) {
                    int parsed = stringParser.parseString(buffer, keyIdx, ensureStringBuf(keyLen));
                    keyBytes = Arrays.copyOf(stringBuf, parsed);
                } else {
                    keyBytes = Arrays.copyOfRange(buffer, keyStart, keyStart + keyLen);
                }

                int colonIdx = bi.getAndAdvance();
                if (buffer[colonIdx] != ':') {
                    throw new JsonParsingException("Missing colon in nested object");
                }

                out.writeIntLE(keyBytes.length);
                out.writeBytes(keyBytes, 0, keyBytes.length);

                int valIdx = bi.getAndAdvance();
                writeKvValue(out, buffer, valIdx, bi);

                int sep = bi.getAndAdvance();
                if (buffer[sep] == '}') break;
                if (buffer[sep] != ',') {
                    throw new JsonParsingException("Expected ',' or '}' in nested object");
                }
            }
            return org.elasticsearch.common.bytes.BytesReference.toBytes(out.bytes());
        } catch (java.io.IOException e) {
            throw new JsonParsingException("IO error serializing key-value: " + e.getMessage());
        }
    }

    private void writeKvValue(org.elasticsearch.common.io.stream.BytesStreamOutput out, byte[] buffer, int idx, BitIndexes bi)
        throws java.io.IOException {
        byte b = buffer[idx];
        switch (b) {
            case '"' -> {
                int off = idx + 1;
                int len = scalarStringLength(buffer, off);
                boolean escaped = containsBackslash(buffer, off, len);
                out.writeByte(SourceValueType.STRING);
                if (escaped) {
                    int parsed = stringParser.parseString(buffer, idx, ensureStringBuf(len));
                    out.writeIntLE(parsed);
                    out.writeBytes(stringBuf, 0, parsed);
                } else {
                    out.writeIntLE(len);
                    out.writeBytes(buffer, off, len);
                }
            }
            case 't' -> {
                validateTrue(buffer, idx);
                out.writeByte(SourceValueType.TRUE);
            }
            case 'f' -> {
                validateFalse(buffer, idx);
                out.writeByte(SourceValueType.FALSE);
            }
            case 'n' -> {
                validateNull(buffer, idx);
                out.writeByte(SourceValueType.NULL);
            }
            case '{' -> {
                byte[] nested = serializeKeyValueDirect(buffer, bi);
                out.writeByte(SourceValueType.KEY_VALUE);
                out.writeIntLE(nested.length);
                out.writeBytes(nested, 0, nested.length);
            }
            case '[' -> {
                SourceBatchEncodeHelper.PackedArray arr = parseArrayDirect(buffer, bi);
                out.writeByte(arr.arrayType());
                out.writeIntLE(arr.packed().length);
                out.writeBytes(arr.packed(), 0, arr.packed().length);
            }
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                writeKvNumber(out, buffer, idx);
            }
            default -> throw new JsonParsingException("Unexpected byte in nested value: " + (char) b);
        }
    }

    private void writeKvNumber(org.elasticsearch.common.io.stream.BytesStreamOutput out, byte[] buffer, int idx)
        throws java.io.IOException {
        boolean negative = buffer[idx] == '-';
        int pos = negative ? idx + 1 : idx;

        long digits = 0;
        int digitStart = pos;
        byte ch = buffer[pos];
        while (ch >= '0' && ch <= '9') {
            digits = digits * 10 + (ch - '0');
            ch = buffer[++pos];
        }

        if (ch == '.' || ch == 'e' || ch == 'E') {
            long exponent = 0;
            int digitCountEnd = pos;

            if (buffer[pos] == '.') {
                pos++;
                int fracStart = pos;
                ch = buffer[pos];
                while (ch >= '0' && ch <= '9') {
                    digits = digits * 10 + (ch - '0');
                    ch = buffer[++pos];
                }
                exponent = fracStart - pos;
                digitCountEnd = pos;
            }

            if (buffer[pos] == 'e' || buffer[pos] == 'E') {
                pos++;
                boolean expNeg = false;
                if (buffer[pos] == '-') {
                    expNeg = true;
                    pos++;
                } else if (buffer[pos] == '+') {
                    pos++;
                }
                long exp = 0;
                ch = buffer[pos];
                while (ch >= '0' && ch <= '9') {
                    exp = exp * 10 + (ch - '0');
                    ch = buffer[++pos];
                }
                exponent += expNeg ? -exp : exp;
            }

            int digitCount = digitCountEnd - digitStart;
            double val = doubleParser.parse(buffer, idx, negative, digitStart, digitCount, digits, exponent);
            float fval = (float) val;
            if ((double) fval == val) {
                out.writeByte(SourceValueType.FLOAT);
                out.writeIntLE(Float.floatToRawIntBits(fval));
            } else {
                out.writeByte(SourceValueType.DOUBLE);
                out.writeLongLE(Double.doubleToRawLongBits(val));
            }
        } else {
            long val = negative ? -digits : digits;
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                out.writeByte(SourceValueType.INT);
                out.writeIntLE((int) val);
            } else {
                out.writeByte(SourceValueType.LONG);
                out.writeLongLE(val);
            }
        }
    }

    // ------------------------------------------------------------------
    // String helpers
    // ------------------------------------------------------------------

    private static int scalarStringLength(byte[] buffer, int start) {
        int i = start;
        while (buffer[i] != '"') {
            if (buffer[i] == '\\') i += 2;
            else i++;
        }
        return i - start;
    }

    private static boolean containsBackslash(byte[] buffer, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (buffer[i] == '\\') return true;
        }
        return false;
    }

    private byte[] ensureStringBuf(int minLen) {
        if (stringBuf.length < minLen + 64) {
            stringBuf = new byte[minLen + 64];
        }
        return stringBuf;
    }

    // ------------------------------------------------------------------
    // Atom validation
    // ------------------------------------------------------------------

    private static void validateTrue(byte[] buffer, int idx) {
        if (buffer[idx] != 't'
            || buffer[idx + 1] != 'r'
            || buffer[idx + 2] != 'u'
            || buffer[idx + 3] != 'e'
            || !isStructuralOrWhitespace(buffer[idx + 4])) {
            throw new JsonParsingException("Invalid value at " + idx + ". Expected 'true'.");
        }
    }

    private static void validateFalse(byte[] buffer, int idx) {
        if (buffer[idx] != 'f'
            || buffer[idx + 1] != 'a'
            || buffer[idx + 2] != 'l'
            || buffer[idx + 3] != 's'
            || buffer[idx + 4] != 'e'
            || !isStructuralOrWhitespace(buffer[idx + 5])) {
            throw new JsonParsingException("Invalid value at " + idx + ". Expected 'false'.");
        }
    }

    private static void validateNull(byte[] buffer, int idx) {
        if (buffer[idx] != 'n'
            || buffer[idx + 1] != 'u'
            || buffer[idx + 2] != 'l'
            || buffer[idx + 3] != 'l'
            || !isStructuralOrWhitespace(buffer[idx + 4])) {
            throw new JsonParsingException("Invalid value at " + idx + ". Expected 'null'.");
        }
    }
}
