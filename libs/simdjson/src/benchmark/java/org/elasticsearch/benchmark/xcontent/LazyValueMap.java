/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * H10: a {@code Map<String, Object>} view over a backing map whose {@link String}/
 * {@link BigInteger} leaf values may be stored as unmaterialized {@link LazyString}/
 * {@link LazyBigInteger} holders (raw source bytes, not yet decoded/parsed). Decoding happens
 * lazily, on first {@link #get} of that key, and the decoded value is cached back into the
 * backing map so repeat access is free.
 *
 * <p>Motivation: ingest processors, scripts, and similar callers of {@code
 * AbstractXContentParser#map()} often read only a handful of known fields out of a much larger
 * document. Eagerly {@code String}-decoding every field (as {@link MapDocumentHandler} and
 * Jackson's own {@code parser.map()} both do) pays for fields that are never read. See
 * {@code SIMDJSON_MAP_EVAL.md} H10 for the measurements this is built to support.
 *
 * <p>{@link #size()}, {@link #containsKey}, and {@link #keySet()} do NOT force materialization -
 * key presence/count is already known without decoding any value, so callers that only check
 * "does this document have field X" or "how many fields" keep the full benefit. {@link
 * #entrySet()} (and anything {@link AbstractMap} builds from it - {@code equals}, {@code
 * toString}, {@code values()}, {@code forEach}) forces full materialization, since those
 * operations need every value regardless.
 *
 * <p>Numbers/booleans/nulls are stored eagerly, never lazily: boxing an already-parsed
 * {@code long}/{@code double}/{@code boolean} is cheap enough that H1-H3 (container pre-sizing,
 * pooling) already found no measurable win from touching this path, so there is nothing worth
 * deferring there. Nested objects are also always eagerly built - see {@link
 * LazyMapDocumentHandler} for why the {@code JsonDocumentHandler} SAX event model can't cheaply
 * skip a nested subtree's *walk*, only defer materializing individual leaf values within it.
 *
 * <p><strong>Not thread-safe.</strong>
 */
final class LazyValueMap extends AbstractMap<String, Object> {

    /** Unmaterialized string value: raw UTF-8 source bytes, not yet decoded. */
    record LazyString(byte[] buf, int off, int len) {}

    /** Unmaterialized big-integer value: raw source JSON text, not yet parsed. */
    record LazyBigInteger(byte[] buf, int off, int len) {}

    private final Map<String, Object> backing;

    LazyValueMap(Map<String, Object> backing) {
        this.backing = backing;
    }

    /** The raw backing map, for {@link LazyMapDocumentHandler} to populate directly. */
    Map<String, Object> backing() {
        return backing;
    }

    @Override
    public Object get(Object key) {
        return materialize((String) key, backing.get(key));
    }

    @Override
    public boolean containsKey(Object key) {
        return backing.containsKey(key);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public Set<String> keySet() {
        return backing.keySet();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        // Copy the key list first: materialize() mutates backing (caching decoded values) as we
        // go, and iterating a map while replacing its values is asking for trouble.
        for (String key : new ArrayList<>(backing.keySet())) {
            materialize(key, backing.get(key));
        }
        return backing.entrySet();
    }

    private Object materialize(String key, Object raw) {
        if (raw instanceof LazyString s) {
            String decoded = new String(s.buf(), s.off(), s.len(), StandardCharsets.UTF_8);
            backing.put(key, decoded);
            return decoded;
        }
        if (raw instanceof LazyBigInteger b) {
            BigInteger decoded = new BigInteger(new String(b.buf(), b.off(), b.len(), StandardCharsets.US_ASCII));
            backing.put(key, decoded);
            return decoded;
        }
        return raw;
    }
}
