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
 * H1: like {@link MapDocumentHandler}, but sizes each new container from how big the container
 * at the same nesting depth turned out to be <em>last</em> time, instead of Java's default
 * capacity 16. Bulk-indexed documents from one mapping have near-identical shape doc-to-doc, so
 * "last time's size at this depth" is normally exact and the container never resizes.
 *
 * <p>Depth-keyed rather than field-name-keyed: cheap to maintain (one int array indexed by
 * nesting depth) and good enough when sibling objects at the same depth are similarly sized,
 * which holds for all three benchmark shapes.
 */
final class PresizedMapDocumentHandler implements JsonDocumentHandler {

    private static final int INITIAL_HINT = 16;

    private Map<String, Object> root;
    private final Deque<Object> stack = new ArrayDeque<>();

    // Observed container size at each nesting depth, from the previous document. Index 0 is the
    // root object; array containers share the same depth-indexed hint array as objects since a
    // given depth in a fixed document shape is consistently either object or array, never both.
    private int[] sizeHintByDepth = new int[8];
    // Size accumulated per depth for the *current* document, folded into sizeHintByDepth on endObject/endArray.
    private int depth = -1;

    PresizedMapDocumentHandler() {
        java.util.Arrays.fill(sizeHintByDepth, INITIAL_HINT);
        reset();
    }

    Map<String, Object> result() {
        return root;
    }

    void reset() {
        // The root object never gets an endObject callback (nothing brackets the top level), so
        // its size has to be captured here, right before it's replaced, rather than in endObject.
        if (root != null) {
            recordSize(0, root.size());
        }
        stack.clear();
        depth = 0;
        root = new HashMap<>(capacityFor(hintAt(0)));
        stack.push(root);
    }

    private int hintAt(int d) {
        if (d >= sizeHintByDepth.length) {
            sizeHintByDepth = java.util.Arrays.copyOf(sizeHintByDepth, d + 4);
            java.util.Arrays.fill(sizeHintByDepth, d, sizeHintByDepth.length, INITIAL_HINT);
        }
        return sizeHintByDepth[d];
    }

    /** HashMap/ArrayList only resize past 0.75 load factor, so ask for a bit of headroom. */
    private static int capacityFor(int observedSize) {
        return Math.max((int) (observedSize / 0.75f) + 1, 4);
    }

    private void recordSize(int d, int size) {
        if (d < sizeHintByDepth.length) {
            sizeHintByDepth[d] = size;
        }
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
        Map<String, Object> child = new HashMap<>(capacityFor(hintAt(depth)));
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endObject() {
        Map<String, Object> closed = currentMap();
        recordSize(depth, closed.size());
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
        List<Object> child = new ArrayList<>(capacityFor(hintAt(depth)));
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endArray() {
        List<Object> closed = currentList();
        recordSize(depth, closed.size());
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
        Map<String, Object> child = new HashMap<>(capacityFor(hintAt(depth)));
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndObject() {
        Map<String, Object> closed = currentMap();
        recordSize(depth, closed.size());
        depth--;
        stack.pop();
    }

    @Override
    public void arrayElemStartArray() {
        depth++;
        List<Object> child = new ArrayList<>(capacityFor(hintAt(depth)));
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndArray() {
        List<Object> closed = currentList();
        recordSize(depth, closed.size());
        depth--;
        stack.pop();
    }
}
