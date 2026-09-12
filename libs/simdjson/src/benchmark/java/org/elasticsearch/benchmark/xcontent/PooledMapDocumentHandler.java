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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H3: like {@link MapDocumentHandler}, but {@code Map}/{@code List} containers are checked out
 * of a per-depth arena and reused (via {@code clear()}) across documents instead of freshly
 * allocated every time. Containers at the same depth are checked out in traversal order and
 * indexed by an arena "used" counter that resets to 0 each document, so sibling containers at
 * the same depth (e.g. two nested objects at depth 1) each get a distinct, stable slot rather
 * than aliasing one shared instance.
 *
 * <p><strong>Contract this changes vs. {@link MapDocumentHandler}:</strong> the tree returned by
 * {@link #result()} is only valid until the <em>next</em> {@link #reset()} — reusing a slot calls
 * {@code clear()} on it lazily, on next checkout. A caller must fully consume (or deep-copy) one
 * document's result before parsing the next. That's a real constraint on production use (e.g. it
 * would not be safe for {@code IngestDocument}, which keeps its source map alive across an entire
 * pipeline), included here to measure the upper bound of what pooling could recover, not as a
 * drop-in replacement.
 */
final class PooledMapDocumentHandler implements JsonDocumentHandler {

    private final Deque<Object> stack = new ArrayDeque<>();

    private List<Map<String, Object>>[] mapArenaByDepth = newMapArenaArray(8);
    private List<List<Object>>[] listArenaByDepth = newListArenaArray(8);
    private int[] mapUsedByDepth = new int[8];
    private int[] listUsedByDepth = new int[8];
    private int depth = -1;
    private Map<String, Object> root;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<Map<String, Object>>[] newMapArenaArray(int size) {
        List[] a = new List[size];
        for (int i = 0; i < size; i++) {
            a[i] = new ArrayList<>();
        }
        return (List<Map<String, Object>>[]) a;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<List<Object>>[] newListArenaArray(int size) {
        List[] a = new List[size];
        for (int i = 0; i < size; i++) {
            a[i] = new ArrayList<>();
        }
        return (List<List<Object>>[]) a;
    }

    PooledMapDocumentHandler() {
        reset();
    }

    Map<String, Object> result() {
        return root;
    }

    void reset() {
        stack.clear();
        depth = 0;
        java.util.Arrays.fill(mapUsedByDepth, 0);
        java.util.Arrays.fill(listUsedByDepth, 0);
        root = checkoutMap(0);
        stack.push(root);
    }

    private void ensureDepth(int d) {
        if (d >= mapArenaByDepth.length) {
            int newCap = Math.max(d + 1, mapArenaByDepth.length * 2);
            List<Map<String, Object>>[] newMapArena = newMapArenaArray(newCap);
            List<List<Object>>[] newListArena = newListArenaArray(newCap);
            System.arraycopy(mapArenaByDepth, 0, newMapArena, 0, mapArenaByDepth.length);
            System.arraycopy(listArenaByDepth, 0, newListArena, 0, listArenaByDepth.length);
            mapArenaByDepth = newMapArena;
            listArenaByDepth = newListArena;
            mapUsedByDepth = java.util.Arrays.copyOf(mapUsedByDepth, newCap);
            listUsedByDepth = java.util.Arrays.copyOf(listUsedByDepth, newCap);
        }
    }

    private Map<String, Object> checkoutMap(int d) {
        ensureDepth(d);
        List<Map<String, Object>> arena = mapArenaByDepth[d];
        int idx = mapUsedByDepth[d]++;
        if (idx >= arena.size()) {
            Map<String, Object> m = new HashMap<>();
            arena.add(m);
            return m;
        }
        Map<String, Object> m = arena.get(idx);
        m.clear();
        return m;
    }

    private List<Object> checkoutList(int d) {
        ensureDepth(d);
        List<List<Object>> arena = listArenaByDepth[d];
        int idx = listUsedByDepth[d]++;
        if (idx >= arena.size()) {
            List<Object> l = new ArrayList<>();
            arena.add(l);
            return l;
        }
        List<Object> l = arena.get(idx);
        l.clear();
        return l;
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
        depth++;
        Map<String, Object> child = checkoutMap(depth);
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endObject() {
        depth--;
        stack.pop();
    }

    @Override
    public void emptyObject(String fieldName) {
        currentMap().put(fieldName, new HashMap<>());
    }

    @Override
    public void stringField(String fieldName, byte[] buf, int off, int len) {
        currentMap().put(fieldName, new String(buf, off, len, StandardCharsets.UTF_8));
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
        depth++;
        List<Object> child = checkoutList(depth);
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endArray() {
        depth--;
        stack.pop();
    }

    @Override
    public void arrayElemString(byte[] buf, int off, int len) {
        currentList().add(new String(buf, off, len, StandardCharsets.UTF_8));
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
        depth++;
        Map<String, Object> child = checkoutMap(depth);
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndObject() {
        depth--;
        stack.pop();
    }

    @Override
    public void arrayElemStartArray() {
        depth++;
        List<Object> child = checkoutList(depth);
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndArray() {
        depth--;
        stack.pop();
    }
}
