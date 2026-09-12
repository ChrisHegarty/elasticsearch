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
 * Spike: a {@link JsonDocumentHandler} that materializes a full {@code Map<String, Object>} /
 * {@code List<Object>} tree, the same shape {@code AbstractXContentParser#map()} builds from a
 * Jackson-backed parser. Exists only to benchmark simdjson against Jackson for the "read a whole
 * document into a generic Map" use case (ingest processors, script params, etc.), which today
 * always goes through Jackson - simdjson's only production consumer walks straight into columnar
 * storage ({@code EscfDocumentHandler}) and never materializes an object graph.
 *
 * <p>Value types mirror {@code AbstractXContentParser}'s conventions closely enough for a fair
 * comparison: strings as {@link String}, integral values as {@link Integer}/{@link Long}/
 * {@link BigInteger}, floating point as {@link Double} (Jackson's {@code getNumberValue()}
 * default for {@code VALUE_NUMBER_FLOAT}), booleans as {@link Boolean}, nulls as {@code null},
 * nested objects/arrays as {@link Map}/{@link List}.
 *
 * <p>Reused across documents via {@link #reset()} rather than reallocated, mirroring how a real
 * caller would pool this the way {@code EscfDocumentHandler} is pooled per parser.
 *
 * <p><strong>Not thread-safe.</strong> One instance per thread, matching {@link JsonDocumentHandler}.
 */
final class MapDocumentHandler implements JsonDocumentHandler {

    private final Map<String, Object> root = new HashMap<>();
    private final Deque<Object> stack = new ArrayDeque<>();

    MapDocumentHandler() {
        stack.push(root);
    }

    /** The document just walked. Valid until the next {@link #reset()}. */
    Map<String, Object> result() {
        return root;
    }

    /** Clears accumulated state so this handler can walk another document. */
    void reset() {
        root.clear();
        stack.clear();
        stack.push(root);
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
