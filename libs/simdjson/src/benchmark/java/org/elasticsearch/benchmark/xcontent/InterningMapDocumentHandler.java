/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.simdjson.JsonDocumentHandler;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H2: like {@link MapDocumentHandler}, but short string <em>values</em> (not just field names,
 * which simdjson already canonicalizes via {@code FrozenFieldNameTable}) are looked up in a
 * small direct-mapped byte-hash cache before allocating a new {@link String}. Repeated
 * low-cardinality values (enum-like fields such as {@code severity_text}, {@code db.system},
 * {@code type}) hit the cache and skip both the {@code String} and backing byte-decode
 * allocation entirely.
 *
 * <p>Deliberately simple: fixed-size, direct-mapped (one slot per hash bucket, newer value
 * evicts older on collision), no synchronization (one instance per thread, matching
 * {@link JsonDocumentHandler}'s contract). Only applied to values up to
 * {@link #MAX_INTERNED_LEN} bytes — long values are unlikely to repeat and aren't worth the
 * hash/compare cost.
 */
final class InterningMapDocumentHandler implements JsonDocumentHandler {

    private static final int MAX_INTERNED_LEN = 24;
    private static final int CACHE_SLOTS = 128; // power of 2
    private static final int CACHE_MASK = CACHE_SLOTS - 1;

    private final long[] cacheHash = new long[CACHE_SLOTS];
    private final byte[][] cacheBuf = new byte[CACHE_SLOTS][];
    private final int[] cacheOff = new int[CACHE_SLOTS];
    private final int[] cacheLen = new int[CACHE_SLOTS];
    private final String[] cacheVal = new String[CACHE_SLOTS];

    private final Map<String, Object> root = new HashMap<>();
    private final Deque<Object> stack = new ArrayDeque<>();

    InterningMapDocumentHandler() {
        stack.push(root);
    }

    Map<String, Object> result() {
        return root;
    }

    void reset() {
        root.clear();
        stack.clear();
        stack.push(root);
    }

    private String stringOf(byte[] buf, int off, int len) {
        if (len == 0 || len > MAX_INTERNED_LEN) {
            return new String(buf, off, len, StandardCharsets.UTF_8);
        }
        long h = fnv1a(buf, off, len);
        int slot = (int) (h & CACHE_MASK);
        if (cacheVal[slot] != null && cacheHash[slot] == h && regionEquals(buf, off, len, cacheBuf[slot], cacheOff[slot], cacheLen[slot])) {
            return cacheVal[slot];
        }
        String s = new String(buf, off, len, StandardCharsets.UTF_8);
        cacheHash[slot] = h;
        cacheBuf[slot] = buf;
        cacheOff[slot] = off;
        cacheLen[slot] = len;
        cacheVal[slot] = s;
        return s;
    }

    private static long fnv1a(byte[] buf, int off, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++) {
            h ^= (buf[off + i] & 0xffL);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static boolean regionEquals(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
        if (aLen != bLen || b == null) {
            return false;
        }
        return Arrays.equals(a, aOff, aOff + aLen, b, bOff, bOff + bLen);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentMap() {
        return (Map<String, Object>) stack.peek();
    }

    @SuppressWarnings("unchecked")
    private List<Object> currentList() {
        return (List<Object>) stack.peek();
    }

    @Override
    public void startObject(String fieldName) {
        Map<String, Object> child = new HashMap<>();
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endObject() {
        stack.pop();
    }

    @Override
    public void emptyObject(String fieldName) {
        currentMap().put(fieldName, new HashMap<>());
    }

    @Override
    public void stringField(String fieldName, byte[] buf, int off, int len) {
        currentMap().put(fieldName, stringOf(buf, off, len));
    }

    @Override
    public void longField(String fieldName, long value, boolean fitsInt, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, fitsInt ? (Object) (int) value : (Object) value);
    }

    @Override
    public void bigIntegerField(String fieldName, BigInteger value, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, value);
    }

    @Override
    public void doubleField(String fieldName, double value, boolean fitsFloat, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, value);
    }

    @Override
    public void booleanField(String fieldName, boolean value, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, value);
    }

    @Override
    public void nullField(String fieldName) {
        currentMap().put(fieldName, null);
    }

    @Override
    public void startArray(String fieldName) {
        List<Object> child = new java.util.ArrayList<>();
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endArray() {
        stack.pop();
    }

    @Override
    public void arrayElemString(byte[] buf, int off, int len) {
        currentList().add(stringOf(buf, off, len));
    }

    @Override
    public void arrayElemLong(long value, boolean fitsInt) {
        currentList().add(fitsInt ? (Object) (int) value : (Object) value);
    }

    @Override
    public void arrayElemBigInteger(BigInteger value, byte[] srcBuf, int srcOff, int srcLen) {
        currentList().add(value);
    }

    @Override
    public void arrayElemDouble(double value, boolean fitsFloat) {
        currentList().add(value);
    }

    @Override
    public void arrayElemBoolean(boolean value) {
        currentList().add(value);
    }

    @Override
    public void arrayElemNull() {
        currentList().add(null);
    }

    @Override
    public void arrayElemStartObject() {
        Map<String, Object> child = new HashMap<>();
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndObject() {
        stack.pop();
    }

    @Override
    public void arrayElemStartArray() {
        List<Object> child = new java.util.ArrayList<>();
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndArray() {
        stack.pop();
    }
}
