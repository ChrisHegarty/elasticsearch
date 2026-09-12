/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.simdjson.JsonDocumentParser;
import org.elasticsearch.simdjson.SimdJsonParser;
import org.elasticsearch.simdjson.SimdJsonParserPool;
import org.elasticsearch.simdjson.SimdJsonSupport;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Spike: is it worth using simdjson to build a generic {@code Map<String, Object>} the way
 * ingest processors, scripts, and similar callers do today via
 * {@code XContentHelper.convertToMap} / {@code AbstractXContentParser#map()} (Jackson-backed for
 * JSON)? simdjson's only production consumer today ({@code EscfDocumentHandler}) never
 * materializes an object graph - it walks straight into columnar storage - so this measures a use
 * case simdjson doesn't yet serve, using the throwaway {@link MapDocumentHandler}.
 *
 * <p>Reuses the same document shapes as {@code SimdJsonParserBenchmark} for a comparable read on
 * the fixed per-document costs (container allocation, boxing, String decoding) that this use case
 * adds on top of what that benchmark measures.
 *
 * <pre>{@code
 * ./gradlew :libs:simdjson:benchmark --args "MapBuildingBenchmark -rf json -rff build/jmh-map.json"
 * }</pre>
 */
@Fork(value = 1, jvmArgsAppend = { "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MapBuildingBenchmark {

    private static final int DOC_COUNT = 2000;
    /** Small batch size used by the cold-start experiments (H6) - see {@link #simdJsonToMapColdStart()}. */
    private static final int COLD_START_BATCH = 20;

    @Param({ "clickbench_flat", "otel_nested", "small_sparse", "wide_flat", "array_heavy" })
    private String shape;

    private byte[][] docs;
    private byte[][] coldStartDocs;
    /** H10: fixed corpora (independent of {@code @Param shape}) - see the H10 section below. */
    private byte[][] clickbenchDocs;
    private byte[][] otelDocs;
    private byte[][] largeBodyDocs;
    private SimdJsonParserPool simdPool;
    private MapDocumentHandler handler;
    private PresizedMapDocumentHandler presizedHandler;
    private InterningMapDocumentHandler interningHandler;
    private PooledMapDocumentHandler pooledHandler;
    private LazyMapDocumentHandler lazyHandler;

    private static final String[] CLICKBENCH_FEW_FIELDS = { "WatchID", "Title" };
    private static final String[] OTEL_FEW_FIELDS = { "severity_text", "trace_id" };
    private static final String[] LARGE_BODY_FEW_FIELDS = { "id", "level" };

    @Setup
    public void setUp() throws IOException {
        BenchmarkLogging.configure();
        if (SimdJsonSupport.isSupported() == false) {
            throw new IllegalStateException("simdjson is not supported on this JDK/platform - can't run this benchmark here");
        }
        Random random = new Random(42);
        docs = new byte[DOC_COUNT][];
        for (int i = 0; i < DOC_COUNT; i++) {
            docs[i] = generateDoc(random, shape, i).getBytes(UTF_8);
        }
        coldStartDocs = new byte[COLD_START_BATCH][];
        for (int i = 0; i < COLD_START_BATCH; i++) {
            coldStartDocs[i] = generateDoc(random, shape, i).getBytes(UTF_8);
        }
        // H10's fixed corpora - see the H10 section below for why these don't vary with `shape`.
        clickbenchDocs = new byte[DOC_COUNT][];
        otelDocs = new byte[DOC_COUNT][];
        largeBodyDocs = new byte[DOC_COUNT][];
        for (int i = 0; i < DOC_COUNT; i++) {
            clickbenchDocs[i] = generateClickBenchFlat(random).getBytes(UTF_8);
            otelDocs[i] = generateOtelNested(random).getBytes(UTF_8);
            largeBodyDocs[i] = generateLargeBody(random).getBytes(UTF_8);
        }
        // Independent pool/table rather than the shared default, so each fork measures a cold
        // field-name cache the same way every invocation does (no cross-invocation warmth to
        // account for) - matching how SimdJsonParserBenchmark isolates EscfEncoder state.
        simdPool = new SimdJsonParserPool(64 * 1024);
        handler = new MapDocumentHandler();
        presizedHandler = new PresizedMapDocumentHandler();
        interningHandler = new InterningMapDocumentHandler();
        pooledHandler = new PooledMapDocumentHandler();
        lazyHandler = new LazyMapDocumentHandler();
        selfCheck();
        System.out.printf(Locale.ROOT, "[setup] shape=%s docCount=%d%n", shape, DOC_COUNT);
    }

    /**
     * Sanity check that every handler variant (baseline + each hypothesis) actually produces the
     * same tree Jackson does, so the benchmark isn't quietly comparing against a broken handler.
     * Runs once per trial.
     */
    private void selfCheck() throws IOException {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        for (byte[] doc : docs) {
            Map<String, Object> jackson;
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, doc, 0, doc.length)) {
                jackson = parser.map();
            }
            assertMatches(jackson, doc, docParser, handler, handler.result(), "MapDocumentHandler");

            presizedHandler.reset();
            docParser.parseDocument(doc, doc.length, presizedHandler);
            assertEqualTrees(jackson, presizedHandler.result(), doc, "PresizedMapDocumentHandler");

            interningHandler.reset();
            docParser.parseDocument(doc, doc.length, interningHandler);
            assertEqualTrees(jackson, interningHandler.result(), doc, "InterningMapDocumentHandler");

            pooledHandler.reset();
            docParser.parseDocument(doc, doc.length, pooledHandler);
            assertEqualTrees(jackson, pooledHandler.result(), doc, "PooledMapDocumentHandler");

            lazyHandler.reset();
            docParser.parseDocument(doc, doc.length, lazyHandler);
            assertEqualTrees(jackson, lazyHandler.result(), doc, "LazyMapDocumentHandler");
        }
        for (byte[] doc : clickbenchDocs) {
            checkLazyAgainstJackson(docParser, doc, "clickbenchDocs");
        }
        for (byte[] doc : otelDocs) {
            checkLazyAgainstJackson(docParser, doc, "otelDocs");
        }
        for (byte[] doc : largeBodyDocs) {
            checkLazyAgainstJackson(docParser, doc, "largeBodyDocs");
        }
    }

    /** H10's fixed corpora aren't covered by the loop above whenever {@code shape} is something
     *  other than clickbench_flat/otel_nested, so check them unconditionally here too. */
    private void checkLazyAgainstJackson(JsonDocumentParser docParser, byte[] doc, String corpusName) throws IOException {
        Map<String, Object> jackson;
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, doc, 0, doc.length)) {
            jackson = parser.map();
        }
        lazyHandler.reset();
        docParser.parseDocument(doc, doc.length, lazyHandler);
        assertEqualTrees(jackson, lazyHandler.result(), doc, "LazyMapDocumentHandler(" + corpusName + ")");
    }

    private void assertMatches(
        Map<String, Object> jackson,
        byte[] doc,
        JsonDocumentParser docParser,
        MapDocumentHandler h,
        Map<String, Object> ignored,
        String name
    ) {
        h.reset();
        docParser.parseDocument(doc, doc.length, h);
        assertEqualTrees(jackson, h.result(), doc, name);
    }

    private static void assertEqualTrees(Map<String, Object> jackson, Map<String, Object> actual, byte[] doc, String handlerName) {
        if (jackson.equals(actual) == false) {
            throw new IllegalStateException(
                handlerName
                    + " diverged from Jackson for doc ["
                    + new String(doc, UTF_8)
                    + "]\njackson="
                    + jackson
                    + "\nactual="
                    + actual
            );
        }
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int jacksonToMap() throws IOException {
        int fieldCount = 0;
        for (byte[] doc : docs) {
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, doc, 0, doc.length)) {
                fieldCount += parser.map().size();
            }
        }
        return fieldCount;
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int simdJsonToMap() {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : docs) {
            handler.reset();
            docParser.parseDocument(doc, doc.length, handler);
            fieldCount += handler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    /** Same result as {@link #jacksonToMap()} but consumed via Blackhole for a GC-profiled run. */
    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public void jacksonToMapAlloc(Blackhole bh) throws IOException {
        for (byte[] doc : docs) {
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(XContentParserConfiguration.EMPTY, doc, 0, doc.length)) {
                bh.consume(parser.map());
            }
        }
    }

    /** Same result as {@link #simdJsonToMap()} but consumed via Blackhole for a GC-profiled run. */
    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public void simdJsonToMapAlloc(Blackhole bh) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        for (byte[] doc : docs) {
            handler.reset();
            docParser.parseDocument(doc, doc.length, handler);
            Map<String, Object> result = handler.result();
            bh.consume(result);
        }
        docParser.publishFieldNames();
    }

    // ------------------------------------------------------------------
    // H1: pre-sized containers
    // ------------------------------------------------------------------

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int simdJsonToMapPresized() {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : docs) {
            presizedHandler.reset();
            docParser.parseDocument(doc, doc.length, presizedHandler);
            fieldCount += presizedHandler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    // ------------------------------------------------------------------
    // H2: value-string interning
    // ------------------------------------------------------------------

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int simdJsonToMapInterned() {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : docs) {
            interningHandler.reset();
            docParser.parseDocument(doc, doc.length, interningHandler);
            fieldCount += interningHandler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    // ------------------------------------------------------------------
    // H3: pooled containers (see PooledMapDocumentHandler for the contract change this implies)
    // ------------------------------------------------------------------

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int simdJsonToMapPooled() {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : docs) {
            pooledHandler.reset();
            docParser.parseDocument(doc, doc.length, pooledHandler);
            fieldCount += pooledHandler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    // ------------------------------------------------------------------
    // H6: cold-start tax - a brand-new pool/table (as a fresh per-bulk EscfEncoder would use)
    // paying first-sight field-name-learning cost for every document in a small batch, vs.
    // simdJsonToMap's steady-state warm cache. Compare this ns/op against simdJsonToMap's to
    // see how much of a short bulk's per-doc cost is the one-time warm-up tax.
    // ------------------------------------------------------------------

    @Benchmark
    @OperationsPerInvocation(COLD_START_BATCH)
    public int simdJsonToMapColdStart() {
        SimdJsonParserPool freshPool = new SimdJsonParserPool(64 * 1024);
        MapDocumentHandler freshHandler = new MapDocumentHandler();
        JsonDocumentParser docParser = freshPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : coldStartDocs) {
            freshHandler.reset();
            docParser.parseDocument(doc, doc.length, freshHandler);
            fieldCount += freshHandler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    /** Same batch size as {@link #simdJsonToMapColdStart()}, but reusing the already-warm pool. */
    @Benchmark
    @OperationsPerInvocation(COLD_START_BATCH)
    public int simdJsonToMapWarmEquivalentBatch() {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : coldStartDocs) {
            handler.reset();
            docParser.parseDocument(doc, doc.length, handler);
            fieldCount += handler.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    // ------------------------------------------------------------------
    // H9: how much would a hypothetical native-driven stage 2 cost? es_simdjson.cpp exposes only
    // stage 1 (see native/src/es_simdjson.cpp) - stage 2 (walking + value materialization) is
    // pure Java specifically to avoid crossing the FFI boundary per token. Building a real native
    // stage 2 means new native entry points and a native rebuild across every platform
    // (elasticsearch.native-library-build's docker cross-toolchain) - out of scope for a
    // same-session spike. As a proxy, this isolates the fixed per-call FFI crossing cost of
    // SimdJsonParser#stage1 (already exactly one native call per document) by running it against
    // a minimal 2-byte document, so the timing is dominated by crossing overhead rather than
    // actual scan work. Multiplying that per-call cost by a per-token call count (e.g. ~100 for
    // clickbench_flat) estimates what a per-token-native design would cost.
    // ------------------------------------------------------------------

    private static final byte[] MINIMAL_DOC = "{}".getBytes(UTF_8);

    private SimdJsonParser ffiProbeParser;

    /**
     * Long-lived, matching how {@link SimdJsonParserPool} actually amortizes parser
     * construction (one per thread, reused). A fresh {@link SimdJsonParser} per call was tried
     * first and reliably OOM-killed the JVM within a few seconds at JMH's call rate - even
     * with try-with-resources releasing it every call, native-context churn at millions of
     * calls/sec outpaces cleanup. That in itself is informative: constructing a
     * {@code SimdJsonParser} is too heavyweight to do per-document, which is exactly why
     * {@code SimdJsonParserPool} exists in production and why H9's "native stage 2" idea would
     * also need a pooled/reused native context, not one created per call.
     */
    @Setup(Level.Trial)
    public void setUpFfiProbe() {
        ffiProbeParser = new SimdJsonParser(1024);
    }

    @Benchmark
    public void stage1FfiCrossingProbe(Blackhole bh) {
        ffiProbeParser.stage1(MINIMAL_DOC, MINIMAL_DOC.length);
        bh.consume(ffiProbeParser);
    }

    @org.openjdk.jmh.annotations.TearDown(Level.Trial)
    public void tearDownFfiProbe() {
        ffiProbeParser.close();
    }

    // ------------------------------------------------------------------
    // H10: lazy value materialization (see LazyValueMap/LazyMapDocumentHandler) - only decode a
    // String/BigInteger leaf the first time it's actually read via Map#get, instead of eagerly
    // for every field during the walk. Motivated by callers (ingest processors, scripts) that
    // read only a handful of known fields out of a much larger document.
    //
    // Uses its own fixed corpora rather than the @Param-ed `docs`, because the "few known
    // fields" access pattern needs field names that are stable across every generated document
    // of a shape - unlike small_sparse, whose field names vary by doc. Like stage1FfiCrossingProbe
    // (H9), these benchmarks don't depend on `shape` but still run once per shape permutation;
    // the six numbers on each side are the same regardless of which shape row they land on.
    //
    // clickbench_flat (all leaves, ~30 string fields out of ~100) is Tier-1 laziness's best case.
    // otel_nested (3 of its 6 top-level fields are nested objects, always built eagerly - see
    // LazyMapDocumentHandler) is close to its worst case: deferring only the 3 top-level leaf
    // strings can't touch the dominant nested-container-building cost.
    //
    // Three access patterns per corpus x handler:
    //   NoAccess   - build, then only call size() - never reads a value. Laziness's ceiling.
    //   FewFields  - build, then read the 2 fields in {CLICKBENCH,OTEL}_FEW_FIELDS. The pattern
    //                the user asked about: "only a small subset of values is ever read".
    //   AllFields  - build, then recursively read every value. Laziness's floor: eager should
    //                win here (or lazy should lose by roughly the sentinel-object + instanceof
    //                overhead), since nothing is actually skipped.
    // ------------------------------------------------------------------

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int clickbenchEagerNoAccess() {
        return buildOnly(handler, clickbenchDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int clickbenchLazyNoAccess() {
        return buildOnlyLazy(lazyHandler, clickbenchDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long clickbenchEagerFewFields() {
        return buildAndTouchFew(handler, clickbenchDocs, CLICKBENCH_FEW_FIELDS);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long clickbenchLazyFewFields() {
        return buildAndTouchFewLazy(lazyHandler, clickbenchDocs, CLICKBENCH_FEW_FIELDS);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long clickbenchEagerAllFields() {
        return buildAndTouchAll(handler, clickbenchDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long clickbenchLazyAllFields() {
        return buildAndTouchAllLazy(lazyHandler, clickbenchDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int otelEagerNoAccess() {
        return buildOnly(handler, otelDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int otelLazyNoAccess() {
        return buildOnlyLazy(lazyHandler, otelDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long otelEagerFewFields() {
        return buildAndTouchFew(handler, otelDocs, OTEL_FEW_FIELDS);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long otelLazyFewFields() {
        return buildAndTouchFewLazy(lazyHandler, otelDocs, OTEL_FEW_FIELDS);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long otelEagerAllFields() {
        return buildAndTouchAll(handler, otelDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long otelLazyAllFields() {
        return buildAndTouchAllLazy(lazyHandler, otelDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int largeBodyEagerNoAccess() {
        return buildOnly(handler, largeBodyDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public int largeBodyLazyNoAccess() {
        return buildOnlyLazy(lazyHandler, largeBodyDocs);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long largeBodyEagerFewFields() {
        return buildAndTouchFew(handler, largeBodyDocs, LARGE_BODY_FEW_FIELDS);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public long largeBodyLazyFewFields() {
        return buildAndTouchFewLazy(lazyHandler, largeBodyDocs, LARGE_BODY_FEW_FIELDS);
    }

    private int buildOnly(MapDocumentHandler h, byte[][] corpus) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            fieldCount += h.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    private int buildOnlyLazy(LazyMapDocumentHandler h, byte[][] corpus) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        int fieldCount = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            fieldCount += h.result().size();
        }
        docParser.publishFieldNames();
        return fieldCount;
    }

    private long buildAndTouchFew(MapDocumentHandler h, byte[][] corpus, String[] fields) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        long acc = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            acc += touchFew(h.result(), fields);
        }
        docParser.publishFieldNames();
        return acc;
    }

    private long buildAndTouchFewLazy(LazyMapDocumentHandler h, byte[][] corpus, String[] fields) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        long acc = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            acc += touchFew(h.result(), fields);
        }
        docParser.publishFieldNames();
        return acc;
    }

    private long buildAndTouchAll(MapDocumentHandler h, byte[][] corpus) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        long acc = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            acc += touchAll(h.result());
        }
        docParser.publishFieldNames();
        return acc;
    }

    private long buildAndTouchAllLazy(LazyMapDocumentHandler h, byte[][] corpus) {
        JsonDocumentParser docParser = simdPool.forCurrentThread();
        long acc = 0;
        for (byte[] doc : corpus) {
            h.reset();
            docParser.parseDocument(doc, doc.length, h);
            acc += touchAll(h.result());
        }
        docParser.publishFieldNames();
        return acc;
    }

    private static long touchFew(Map<String, Object> map, String[] fields) {
        long acc = 0;
        for (String field : fields) {
            Object v = map.get(field);
            if (v instanceof String s) {
                acc += s.length();
            } else if (v instanceof Number n) {
                acc += n.longValue();
            }
        }
        return acc;
    }

    /** Recursively reads every leaf value, forcing full materialization of any lazy map. */
    private static long touchAll(Object value) {
        long acc = 0;
        if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                acc += touchAll(v);
            }
        } else if (value instanceof java.util.List<?> l) {
            for (Object v : l) {
                acc += touchAll(v);
            }
        } else if (value instanceof String s) {
            acc += s.length();
        } else if (value instanceof Number n) {
            acc += n.longValue();
        }
        return acc;
    }

    // ------------------------------------------------------------------
    // Document generators (same shapes as SimdJsonParserBenchmark, plus wide_flat (H5) and
    // array_heavy (H7), added for this evaluation)
    // ------------------------------------------------------------------

    private static String generateDoc(Random random, String shape, int docIndex) {
        return switch (shape) {
            case "clickbench_flat" -> generateClickBenchFlat(random);
            case "otel_nested" -> generateOtelNested(random);
            case "small_sparse" -> generateSmallSparse(random, docIndex);
            case "wide_flat" -> generateWideFlat(random);
            case "array_heavy" -> generateArrayHeavy(random);
            default -> throw new IllegalArgumentException("unknown shape: " + shape);
        };
    }

    /** H5: a much wider flat document (500 fields) than clickbench_flat's ~100, to see whether
     *  the simdjson-vs-Jackson gap grows, shrinks, or holds steady as field count increases. */
    private static String generateWideFlat(Random random) {
        StringBuilder sb = new StringBuilder(500 * 24);
        sb.append('{');
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append("field_").append(i).append("\":");
            switch (i % 4) {
                case 0 -> sb.append(random.nextInt(100000));
                case 1 -> sb.append('"').append(randomWord(random)).append('"');
                case 2 -> sb.append(random.nextBoolean());
                default -> sb.append(String.format(Locale.ROOT, "%.3f", random.nextDouble() * 1000));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** H7: none of the original 3 shapes contain arrays; this exercises startArray/arrayElem*/
    /*  arrayElemStartObject, the one code path in MapDocumentHandler untested by the others. */
    private static String generateArrayHeavy(Random random) {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) tags.append(',');
            tags.append('"').append(randomWord(random)).append('"');
        }
        StringBuilder scores = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) scores.append(',');
            scores.append(random.nextInt(1000));
        }
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) nested.append(',');
            nested.append(
                String.format(Locale.ROOT, "{\"a\":%d,\"b\":\"%s\",\"c\":[%d,%d]}", random.nextInt(100), randomWord(random), i, i * 2)
            );
        }
        return String.format(
            Locale.ROOT,
            "{\"id\":%d,\"tags\":[%s],\"scores\":[%s],\"nested\":[%s]}",
            random.nextLong(),
            tags,
            scores,
            nested
        );
    }

    /**
     * H10 supplementary shape: a log-entry-like document with two small, always-read fields
     * ("id", "level") plus two large (~2KB) string fields ("body", "stacktrace") that are
     * realistic to skip entirely (e.g. an ingest pipeline that only routes on level/id). Unlike
     * clickbench_flat/otel_nested, whose string fields are all short fixed words (~5-8 chars -
     * too cheap to decode for skipping to matter), this isolates whether laziness pays off once
     * the skipped decode work is actually substantial.
     */
    private static String generateLargeBody(Random random) {
        return String.format(
            Locale.ROOT,
            "{\"id\":%d,\"level\":\"%s\",\"body\":\"%s\",\"stacktrace\":\"%s\"}",
            random.nextLong(),
            randomSeverity(random),
            randomLargeText(random, 2000),
            randomLargeText(random, 2000)
        );
    }

    private static String randomLargeText(Random random, int approxChars) {
        StringBuilder sb = new StringBuilder(approxChars + 16);
        while (sb.length() < approxChars) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(WORDS[random.nextInt(WORDS.length)]);
        }
        return sb.toString();
    }

    private static String generateClickBenchFlat(Random random) {
        return String.format(
            Locale.ROOT,
            """
                {
                  "WatchID": %d, "JavaEnable": %d, "Title": "%s",
                  "GoodEvent": %d, "EventTime": %d, "EventDate": %d,
                  "CounterID": %d, "ClientIP": %d, "ClientIP6": "%s",
                  "RegionID": %d, "UserID": %d,
                  "CounterClass": %d, "OS": %d, "UserAgent": %d,
                  "URL": "https://example.com/%s", "Referer": "https://ref.example.com/%s",
                  "URLDomain": "example.com", "RefererDomain": "ref.example.com",
                  "Refresh": %d, "IsRobot": %d, "RefererCategories": %d,
                  "URLCategories": %d, "URLRegions": %d, "RefererRegions": %d,
                  "ResolutionWidth": %d, "ResolutionHeight": %d, "ResolutionDepth": %d,
                  "FlashMajor": %d, "FlashMinor": %d, "FlashMinor2": "%d",
                  "NetMajor": %d, "NetMinor": %d, "UserAgentMajor": %d,
                  "UserAgentMinor": %d, "CookieEnable": %d, "JavascriptEnable": %d,
                  "IsMobile": %d, "MobilePhone": %d, "MobilePhoneModel": "%s",
                  "Params": "", "IPNetworkID": %d,
                  "TraficSourceID": %d, "SearchEngineID": %d,
                  "SearchPhrase": "%s",
                  "AdvEngineID": %d, "IsArtifical": %d, "WindowClientWidth": %d,
                  "WindowClientHeight": %d, "ClientTimeZone": %d,
                  "ClientEventTime": %d, "SilverlightVersion1": %d, "SilverlightVersion2": %d,
                  "SilverlightVersion3": %d, "SilverlightVersion4": %d,
                  "PageCharset": "UTF-8", "CodeVersion": %d, "IsLink": %d,
                  "IsDownload": %d, "IsNotBounce": %d, "FUniqID": %d,
                  "HID": %d, "IsOldCounter": %d, "IsEvent": %d,
                  "IsParameter": %d, "DontCountHits": %d, "WithHash": %d,
                  "HitColor": "W", "UTCEventTime": %d,
                  "Age": %d, "Sex": %d, "Income": %d,
                  "Interests": %d, "Robotness": %d, "GeneralInterests": %d,
                  "RemoteIP": %d, "RemoteIP6": "%s",
                  "WindowName": %d, "OpenerName": %d, "HistoryLength": %d,
                  "BrowserLanguage": "en", "BrowserCountry": "US",
                  "SocialNetwork": "", "SocialAction": "", "HTTPError": %d,
                  "SendTiming": %d, "DNSTiming": %d, "ConnectTiming": %d,
                  "ResponseStartTiming": %d, "ResponseEndTiming": %d,
                  "FetchTiming": %d, "RedirectTiming": %d, "DOMInteractiveTiming": %d,
                  "ContentLoadTiming": %d, "OnLoadTiming": %d,
                  "RequestNum": %d, "RequestTry": %d,
                  "NetErrorCode": %d, "SocialShareNetwork": "", "SocialSharePage": "",
                  "ParamPrice": %d, "ParamOrderID": "", "ParamCurrency": "USD",
                  "ParamCurrencyID": %d,
                  "GoalsReached": %d, "OpenstatServiceName": "", "OpenstatCampaignID": "",
                  "OpenstatAdID": "", "OpenstatSourceID": "",
                  "UTMSource": "", "UTMMedium": "", "UTMCampaign": "", "UTMContent": "", "UTMTerm": "",
                  "FromTag": "", "HasGCLID": %d, "RefererHash": %d, "URLHash": %d,
                  "CLID": %d, "YCLID": %d, "ShareService": "", "ShareURL": "", "ShareTitle": ""
                }""",
            random.nextLong(),
            random.nextInt(2),
            randomWord(random),
            random.nextInt(2),
            random.nextLong(),
            random.nextInt(19000),
            random.nextInt(100000),
            (long) (random.nextDouble() * 4_294_967_295L),
            "::1",
            random.nextInt(200000),
            random.nextLong(),
            random.nextInt(10),
            random.nextInt(255),
            random.nextInt(255),
            randomWord(random),
            randomWord(random),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(1000),
            random.nextInt(1000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(3840),
            random.nextInt(2160),
            random.nextInt(32),
            random.nextInt(33),
            random.nextInt(10),
            random.nextInt(10),
            random.nextInt(10),
            random.nextInt(10),
            random.nextInt(100),
            random.nextInt(100),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            randomWord(random),
            random.nextInt(1000000),
            random.nextInt(30),
            random.nextInt(100),
            randomWord(random),
            random.nextInt(10),
            random.nextInt(2),
            random.nextInt(3840),
            random.nextInt(2160),
            random.nextInt(720),
            random.nextLong(),
            random.nextInt(4),
            random.nextInt(4),
            random.nextInt(4000),
            random.nextInt(10000),
            random.nextInt(1000000),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            random.nextLong(),
            random.nextInt(1000000),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            random.nextInt(2),
            random.nextLong(),
            random.nextInt(90),
            random.nextInt(2),
            random.nextInt(5),
            random.nextInt(10000),
            random.nextInt(10),
            random.nextInt(1000),
            (long) (random.nextDouble() * 4_294_967_295L),
            "::1",
            random.nextInt(1000),
            random.nextInt(1000),
            random.nextInt(100),
            random.nextInt(1000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100000),
            random.nextInt(100),
            random.nextInt(10),
            random.nextInt(10),
            random.nextLong(),
            random.nextInt(1000),
            random.nextInt(10),
            random.nextInt(2),
            random.nextLong(),
            random.nextLong(),
            random.nextInt(100),
            random.nextLong(),
            random.nextLong(),
            random.nextInt(100),
            random.nextLong()
        );
    }

    private static String generateOtelNested(Random random) {
        return String.format(
            Locale.ROOT,
            """
                {
                  "@timestamp": "2025-09-23T%02d:%02d:%02dZ",
                  "resource": {
                    "service.name": "%s",
                    "service.version": "1.%d.0",
                    "host.name": "host-%d",
                    "deployment.environment": "%s"
                  },
                  "scope": {
                    "name": "%s-logger",
                    "version": "2.%d.0"
                  },
                  "severity_text": "%s",
                  "severity_number": %d,
                  "body": "%s",
                  "trace_id": "%s",
                  "span_id": "%s",
                  "trace_flags": %d,
                  "attributes": {
                    "http.method": "%s",
                    "http.status_code": %d,
                    "http.url": "https://api.example.com/%s",
                    "user.id": %d,
                    "db.system": "postgresql",
                    "db.statement": "SELECT * FROM %s WHERE id = %d"
                  }
                }""",
            random.nextInt(24),
            random.nextInt(60),
            random.nextInt(60),
            randomService(random),
            random.nextInt(10),
            random.nextInt(100),
            randomEnv(random),
            randomService(random),
            random.nextInt(5),
            randomSeverity(random),
            random.nextInt(25),
            randomMessage(random),
            randomHex(random, 32),
            randomHex(random, 16),
            random.nextInt(2),
            randomMethod(random),
            random.nextInt(599) + 100,
            randomWord(random),
            random.nextLong(),
            randomWord(random),
            random.nextInt(10000)
        );
    }

    private static String generateSmallSparse(Random random, int docIndex) {
        return switch (docIndex % 3) {
            case 0 -> String.format(
                Locale.ROOT,
                """
                    {"type":"A","id":%d,"ts":%d,"val":%.4f,"label":"%s","active":%b,"count":%d}""",
                random.nextLong(),
                random.nextLong(),
                random.nextDouble(),
                randomWord(random),
                random.nextBoolean(),
                random.nextInt(10000)
            );
            case 1 -> String.format(
                Locale.ROOT,
                """
                    {"type":"B","uid":"%s","score":%.3f,"tags":%d,"region":"%s","retries":%d}""",
                randomWord(random),
                random.nextDouble() * 100,
                random.nextInt(50),
                randomWord(random),
                random.nextInt(5)
            );
            default -> String.format(
                Locale.ROOT,
                """
                    {"type":"C","key":%d,"name":"%s","bytes":%d,"ok":%b,"lat":%.2f,"code":%d}""",
                random.nextLong(),
                randomWord(random),
                random.nextLong(),
                random.nextBoolean(),
                random.nextDouble() * 1000,
                random.nextInt(600)
            );
        };
    }

    // ------------------------------------------------------------------
    // Value generators
    // ------------------------------------------------------------------

    private static final String[] WORDS = {
        "alpha",
        "bravo",
        "charlie",
        "delta",
        "echo",
        "foxtrot",
        "golf",
        "hotel",
        "india",
        "juliet",
        "kilo",
        "lima",
        "mike",
        "november",
        "oscar",
        "papa" };
    private static final String[] SERVICES = { "frontend", "backend", "gateway", "worker", "scheduler" };
    private static final String[] ENVS = { "prod", "staging", "dev", "qa" };
    private static final String[] SEVERITIES = { "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL" };
    private static final String[] MESSAGES = {
        "Request processed",
        "Connection timeout",
        "Failed to place order",
        "Slow query detected",
        "Cache miss",
        "Auth succeeded" };
    private static final String[] METHODS = { "GET", "POST", "PUT", "DELETE", "PATCH" };

    private static String randomWord(Random r) {
        return WORDS[r.nextInt(WORDS.length)];
    }

    private static String randomService(Random r) {
        return SERVICES[r.nextInt(SERVICES.length)];
    }

    private static String randomEnv(Random r) {
        return ENVS[r.nextInt(ENVS.length)];
    }

    private static String randomSeverity(Random r) {
        return SEVERITIES[r.nextInt(SEVERITIES.length)];
    }

    private static String randomMessage(Random r) {
        return MESSAGES[r.nextInt(MESSAGES.length)];
    }

    private static String randomMethod(Random r) {
        return METHODS[r.nextInt(METHODS.length)];
    }

    private static String randomHex(Random r, int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(HEX_CHARS[r.nextInt(16)]);
        }
        return sb.toString();
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
}
