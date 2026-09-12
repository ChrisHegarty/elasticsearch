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
 * H10 variant of {@link MapDocumentHandler}: builds the same {@code Map}/{@code List} tree, but
 * every object level (root and nested) is exposed to callers as a {@link LazyValueMap}. Its
 * {@code String}/{@code BigInteger} leaf values are stored as unmaterialized {@link
 * LazyValueMap.LazyString}/{@link LazyValueMap.LazyBigInteger} holders instead of being decoded
 * during the walk; each is decoded the first time it's actually read via {@code Map#get} and
 * cached from then on. See {@link LazyValueMap} for why this is worth trying and what it does
 * and doesn't defer.
 *
 * <p>Numbers/booleans/nulls are still decoded eagerly here too - see {@link LazyValueMap}.
 * Arrays and their elements are also still eager, matching {@link MapDocumentHandler}: {@link
 * JsonDocumentHandler}'s SAX event model hands this handler a byte range for every scalar leaf
 * field ({@link #stringField}, {@link #longField}, etc.), but not for {@link #startObject}/
 * {@link #startArray} - so a nested object/array's *walk* can't be skipped or deferred
 * independently of the top-level walk, only the per-leaf decode/box step within it can be.
 * Extending laziness to array elements, or to skipping a whole unread nested subtree's walk
 * (not just its decode), would need either a walker change (exposing byte ranges for {@code
 * startObject}/{@code startArray}) or a distinct "record events, replay on demand" tape
 * representation in place of eagerly recursing into a real container; out of scope for this
 * spike - see {@code SIMDJSON_MAP_EVAL.md} H10.
 *
 * <p><strong>Not thread-safe.</strong> One instance per thread, matching {@link
 * JsonDocumentHandler}.
 */
final class LazyMapDocumentHandler implements JsonDocumentHandler {

    private final LazyValueMap root;
    private final Deque<Object> stack = new ArrayDeque<>();

    LazyMapDocumentHandler() {
        root = new LazyValueMap(new HashMap<>());
        stack.push(root.backing());
    }

    /** The document just walked. Valid until the next {@link #reset()}. */
    LazyValueMap result() {
        return root;
    }

    /** Clears accumulated state so this handler can walk another document. */
    void reset() {
        root.backing().clear();
        stack.clear();
        stack.push(root.backing());
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
        Map<String, Object> childBacking = new HashMap<>();
        currentMap().put(fieldName, new LazyValueMap(childBacking));
        stack.push(childBacking);
    }

    @Override
    public void endObject() {
        stack.pop();
    }

    @Override
    public void emptyObject(String fieldName) {
        currentMap().put(fieldName, new LazyValueMap(new HashMap<>()));
    }

    @Override
    public void stringField(String fieldName, byte[] buf, int off, int len) {
        currentMap().put(fieldName, new LazyValueMap.LazyString(buf, off, len));
    }

    @Override
    public void longField(String fieldName, long value, boolean fitsInt, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, fitsInt ? (Object) (int) value : (Object) value);
    }

    @Override
    public void bigIntegerField(String fieldName, BigInteger value, byte[] srcBuf, int srcOff, int srcLen) {
        currentMap().put(fieldName, new LazyValueMap.LazyBigInteger(srcBuf, srcOff, srcLen));
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
        List<Object> child = new ArrayList<>();
        currentMap().put(fieldName, child);
        stack.push(child);
    }

    @Override
    public void endArray() {
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
        List<Object> child = new ArrayList<>();
        currentList().add(child);
        stack.push(child);
    }

    @Override
    public void arrayElemEndArray() {
        stack.pop();
    }
}
