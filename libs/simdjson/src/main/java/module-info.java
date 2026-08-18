/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

/**
 * SIMD-accelerated JSON structural indexer adapted from
 * <a href="https://github.com/simdjson/simdjson-java">simdjson-java</a>.
 *
 * <p>Provides {@link org.elasticsearch.sourcebatch.simdjson.StructuralIndexer} for stage 1
 * (SIMD structural identification), {@link org.elasticsearch.sourcebatch.simdjson.BitIndexes}
 * for the resulting structural index array, and utilities for field name canonicalization,
 * string parsing, and double parsing. The caller walks the structural indices directly
 * (fused stage 2 + token walk) rather than building an intermediate tape.
 *
 * <p>See {@link org.elasticsearch.sourcebatch.simdjson.SimdJsonSupport} for runtime
 * module-graph setup (the {@code jdk.incubator.vector} read-edge must be added
 * before any vector class is loaded).
 */
module org.elasticsearch.simdjson {
    exports org.elasticsearch.sourcebatch.simdjson;
}
