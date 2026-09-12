# simdjson for XContent → `Map<String, Object>`: evaluation log

**Branch:** `simdjson-xcontent-map-eval` (pushed to `chegar` fork, based off `upstream/main`)
**Status:** exploratory spike, not intended to ship as-is.

## Goal

Elasticsearch has an existing, widely-used code path that reads a whole JSON
document into a generic `Map<String, Object>` / `List<Object>` tree (used by
ingest processors, scripts, `SourceLookup`, etc.). It's Jackson-backed today.
`libs/simdjson` is a SIMD-accelerated JSON parser already in the codebase, but
its only production consumer (`EscfDocumentHandler`) walks straight into
columnar storage — it never materializes a generic object graph.

Question: could simdjson do the "parse whole doc to generic Map" job faster
than Jackson, and is it worth building?

## Baseline: how this is done today (Jackson)

`XContentHelper.convertToMap(...)` → `XContentParser#map()` →
`AbstractXContentParser.readMapEntries` / `readValueUnsafe`
(`libs/x-content/src/main/java/org/elasticsearch/xcontent/support/AbstractXContentParser.java`),
which pulls tokens one at a time from Jackson's `ESUTF8StreamJsonParser` and
builds `HashMap` / `ArrayList` / boxed values (`Integer`/`Long`/`BigInteger`/
`Double`/`String`/`Boolean`).

## What simdjson offers today

`JsonDocumentHandler` (`libs/simdjson/src/main/java/org/elasticsearch/simdjson/JsonDocumentHandler.java`)
is a general SAX-style event interface (`startObject`/`endObject`,
`stringField`/`longField`/`doubleField`/`booleanField`/`nullField`,
`startArray`/`endArray`, unnamed `arrayElem*` variants). Nothing about it is
tied to columnar storage — a new implementation can build any target shape,
including a `Map`/`List` tree.

Constraints inherent to the library as it exists:
- Gated by `SimdJsonSupport.isSupported()` — requires the native lib +
  incubating `jdk.incubator.vector` module. Not available on every
  JDK/platform combo, so a Jackson fallback is always required for
  correctness.
- Per-document size cap (`SimdJsonSupport.maxDocBytes()`, default 16 KiB,
  configurable). Documents larger than that also need a Jackson fallback.

## Step 1: prototype + benchmark

Built in a disposable worktree (`/tmp/es-map-eval`, this branch), reusing the
document generators from `SimdJsonParserBenchmark` for a fair comparison:

- **`MapDocumentHandler`** — a `JsonDocumentHandler` that builds
  `Map<String,Object>`/`List<Object>` with a small container stack (push on
  `startObject`/`startArray`/`arrayElemStartObject`/`arrayElemStartArray`, pop
  on the matching end). Value conventions mirror Jackson's
  `getNumberValue()` defaults: `Integer`/`Long`/`BigInteger` for integrals,
  `Double` for floating point, `String`/`Boolean`/`null` otherwise.
- **`MapBuildingBenchmark`** (JMH) — `jacksonToMap` vs `simdJsonToMap`,
  `AverageTime` mode, `@OperationsPerInvocation(2000)`, over the same 3
  document shapes `SimdJsonParserBenchmark` defines:
  - `clickbench_flat` — ~100 flat fields
  - `otel_nested` — nested, ~20 fields across 3 levels
  - `small_sparse` — ~6 fields, 3 rotating shapes
- **Correctness self-check** — added inside `@Setup`: for every doc in the
  corpus, asserts `simdJsonMap.equals(jacksonMap)`. Passed for all 6000 docs
  (3 shapes × 2000 docs) before any numbers were trusted.

### Baseline results (single-threaded, JDK 26 aarch64, macOS, 3 warmup + 5
measurement iterations × 10s)

| shape | Jackson → Map | simdjson → Map | speedup |
|---|---|---|---|
| `clickbench_flat` (~100 flat fields) | 7204 ns/op | 5388 ns/op | **1.34x** |
| `otel_nested` (nested, ~20 fields) | 1432 ns/op | 1130 ns/op | **1.27x** |
| `small_sparse` (~6 fields) | 397 ns/op | 280 ns/op | **1.42x** |

Consistent **~25–40% faster**, not transformative. The saving comes from
simdjson's SIMD tokenizing / number-parsing / field-name canonicalization —
the `Map`/`List` container allocation and value boxing cost is identical in
both paths, since both build the exact same container types. For
`clickbench_flat`, ~5-6 µs of the 7.2 µs total is container/boxing overhead
common to both paths; only ~1.8 µs of the ~1.9 µs improvement is attributable
to the tokenizer swap.

### Known gaps in this first pass

- No arrays in any of the 3 shapes — `arrayElem*` handling untested for
  performance (though exercised for correctness by other simdjson tests).
- Single-threaded only.
- Numeric edge cases (e.g. Jackson's rare `BigDecimal` return for some float
  representations) not exhaustively cross-checked, only the common case.
- Only one hardware/JDK combination measured so far.

## Step 2: hypotheses for further improvement

The baseline win is real but modest, and it's *entirely* explained by the
faster tokenizer — nothing here yet tries to shrink the (identical) container
allocation cost that dominates the total. The experiments below try to
identify where the remaining time actually goes (async-profiler) and whether
either side of the comparison can be pushed further.

| # | Hypothesis | What it tests |
|---|---|---|
| H1 | Pre-sizing the top-level `HashMap` (and nested containers) to the right capacity avoids incremental resize/rehash passes, and should show up as a measurable win on wide documents (`clickbench_flat`, ~100 fields → several resizes at default capacity 16). | Container allocation cost |
| H2 | Interning low-cardinality **string values** (not just field names, which simdjson already canonicalizes) — e.g. `severity_text`, `db.system`, `type` — avoids repeat `String` allocation for repeated values within a batch. | Value allocation cost |
| H3 | Pooling/reusing `Map`/`List` instances across documents (arena-style, caller clears before reuse) instead of allocating fresh containers per document. | Container allocation cost (upper bound of what's recoverable) |
| H4 | Multi-threaded scaling: does the ~25-40% single-threaded edge hold, shrink, or grow at 2/4/8/16 concurrent threads? (Shared `FrozenFieldNameTable` CAS retry loop and native stage-1 context are candidate contention points.) | Concurrency / scalability |
| H5 | Document size/width scaling: does the win grow, shrink, or stay flat as documents get wider (500+ fields) or approach the 16 KiB simdjson cap? | Scaling with doc size |
| H6 | Cold-start cost: small bulks (`docCount=100`) vs large (`docCount=10000`) — does the per-thread field-name-learning warm-up tax (which Jackson has no equivalent of) erode the win for realistic, frequently-recycled bulk sizes? | Per-batch fixed costs |
| H7 | Array-heavy shape (untested by the original 3): does the `arrayElem*` path hold the same ~25-40% edge, or does array accumulation change the picture? | Array path performance |
| H8 | Value boxing cost in isolation: how much of the remaining (non-tokenizer) time is `Integer`/`Long`/`Double` boxing vs `HashMap.put` overhead vs `String` decoding? (async-profiler allocation/CPU profiling on both paths side by side.) | Attributing the "identical" container cost |

Each will be benchmarked independently (isolate one change at a time against
the Step 1 baseline) and profiled with async-profiler (CPU + allocation) on
the two AWS benchmark boxes to guide which, if any, are worth pursuing.

## Step 3: experiment results

_(pending — filled in as each experiment completes)_

### Environment

- Host 1: `ec2-44-197-249-182.compute-1.amazonaws.com` — TBD (spec, JDK)
- Host 2: `ec2-54-172-49-195.compute-1.amazonaws.com` — TBD (spec, JDK)
- Profiler: async-profiler, TBD version

### H1 — pre-sized containers

_TBD_

### H2 — value-string interning

_TBD_

### H3 — container pooling

_TBD_

### H4 — multi-threaded scaling

_TBD_

### H5 — document width/size scaling

_TBD_

### H6 — cold-start / small-bulk overhead

_TBD_

### H7 — array-heavy shape

_TBD_

### H8 — boxing/allocation attribution

_TBD_

## Conclusion

_TBD — filled in once experiments are complete._
