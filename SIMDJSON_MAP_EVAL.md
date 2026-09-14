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
| H9 | Could a native-driven stage 2 (parsing further inside libsimdjson, closer to its own DOM/On-Demand API, instead of the current Java-side fused walker) be faster? | Whether staying in native code longer beats crossing back into Java per token |
| H10 | Added after Step 3, prompted by a follow-up question: if only a small subset of a document's fields is ever read, does deferring `String`/`BigInteger` decoding until first `Map#get(key)` access (instead of eagerly during the walk) beat eager materialization? | Whether the container/boxing cost is avoidable when it's *not needed at all*, as opposed to H1-H3's attempts to make the (always fully-needed) eager cost itself cheaper |
| H11 | Added after H10, prompted by a follow-up question: H9 rejected a native stage 2 wholesale via a proxy (FFI-crossing cost). Revisited with a real (if narrow) native prototype: should numerics be treated differently from strings — and from each other — when considering what to fold into native code, one call per *batch* of numbers rather than per document/token? | Whether native arithmetic beats the already-ported-to-Java fast-path algorithms for numbers specifically, decomposed by number kind (int vs. float) |

Each will be benchmarked independently (isolate one change at a time against
the Step 1 baseline) and profiled with async-profiler (CPU + allocation) on
the two AWS benchmark boxes to guide which, if any, are worth pursuing.

## Step 3: experiment results

All 9 experiments were run on both AWS boxes: JMH `-wi 2 -i 3 -w 3s -r 3s -f 1`,
JDK 26.0.1, single fork, single thread unless noted. Correctness self-check
(every handler variant vs. Jackson, every doc, every shape) passed on both
hosts before any number below was trusted.

### Environment

| | Host 1 | Host 2 |
|---|---|---|
| Arch | aarch64 (Graviton) | x86_64 |
| Cores | 4 | 8 |
| RAM | 7.6 GiB | 15 GiB |
| JDK | 26.0.1 | 26.0.1 |
| async-profiler | 4.1 | 4.5 |

Code: branch [`simdjson-xcontent-map-eval`](https://github.com/ChrisHegarty/elasticsearch/tree/simdjson-xcontent-map-eval),
worktrees at `~/wt-map-eval` on both hosts, run via
`./gradlew :libs:simdjson:benchmark --args "MapBuildingBenchmark ..."`.

### Baseline confirmation on real hardware (both hosts, all 5 shapes)

| shape | host1 (arm, avgt ns/op) jackson → simd | speedup | host2 (x86, avgt ns/op) jackson → simd | speedup |
|---|---|---|---|---|
| clickbench_flat | 9481 → 6127 | **1.55x** | 9058 → 5244 | **1.73x** |
| otel_nested | 1842 → 1481 | **1.24x** | 1683 → 1227 | **1.37x** |
| small_sparse | 631 → 375 | **1.68x** | 534 → 347 | **1.54x** |
| wide_flat (H5) | 39440 → 27995 | **1.41x** | 38954 → 24726 | **1.58x** |
| array_heavy (H7) | 2364 → 1624 | **1.46x** | 2153 → 1482 | **1.45x** |

Confirms the original ~25–40% win from the macOS prototype, on real target
hardware, on both architectures, and now including previously-untested wide
(500-field) and array-bearing shapes. **H5 and H7 conclusion: the win holds
steady (1.4–1.7x) across document width and across the array code path — it
doesn't concentrate in or disappear for any particular shape family.**

### H1 — pre-sized containers: no measurable win

| shape | host1 Δ vs baseline | host2 Δ vs baseline |
|---|---|---|
| clickbench_flat (100 fields) | +0.2% | −0.6% |
| otel_nested | +2.7% | +4.6% |
| small_sparse | +1.7% | +1.1% |
| wide_flat (500 fields) | −1.2% | +0.7% |
| array_heavy | +2.9% | +1.4% |

**Rejected.** Even on `wide_flat` (500 fields, ~6 avoided `HashMap` resizes),
presizing is a wash at best and often slightly *worse* — the per-depth
size-hint bookkeeping (an extra array read/write per container open/close)
costs about as much as the resizes it avoids. `HashMap`'s incremental resize
is evidently cheap enough on modern JITs that this isn't a profitable trade
for JSON-shaped (i.e. not enormous) maps. Would likely need a much wider
document (thousands of fields) before this tips positive.

### H2 — value-string interning: consistently worse

| shape | host1 Δ vs baseline | host2 Δ vs baseline |
|---|---|---|
| clickbench_flat | +2.3% | +2.2% |
| otel_nested | +12.6% | +13.8% |
| small_sparse | +4.8% | +0.4% |
| wide_flat | +4.0% | +4.1% |
| array_heavy | +6.0% | +7.5% |

**Rejected, clearly.** Slower on *every* shape on *both* hosts, worst on
`otel_nested` (+13–14%) which has the most genuinely-repeating short values
(`severity_text`, `db.system`, HTTP methods). The FNV-1a hash + region-compare
on every short string value costs more than the JVM's already-fast
compact-string decode saves on a cache hit, and `wide_flat`/`clickbench_flat`'s
mostly-unique values pay the hash cost on every miss with no payoff at all.
Field-name interning (already done via `FrozenFieldNameTable`) works because
field names are a *closed, small* set discovered once; arbitrary values are
open-ended and this benchmark's shapes don't have enough repetition to
justify the lookup cost.

### H3 — pooled containers: no measurable win

| shape | host1 Δ vs baseline | host2 Δ vs baseline |
|---|---|---|
| clickbench_flat | +0.6% | +0.2% |
| otel_nested | +1.0% | +4.0% |
| small_sparse | +1.4% | +1.5% |
| wide_flat | −0.6% | +0.7% |
| array_heavy | +2.3% | −0.5% |

**Rejected.** Arena checkout/clear bookkeeping costs about as much as it
saves, even for `array_heavy` (many small nested containers per document,
where pooling should help most if it were going to). Consistent with modern
generational GC making short-lived small-object allocation cheap enough
(TLAB bump-pointer alloc) that avoiding it isn't worth the extra
indexing/`clear()` overhead — and it comes with a real safety cost (see the
contract note in `PooledMapDocumentHandler`'s Javadoc: the result is only
valid until the next `reset()`).

### H4 — multi-threaded scaling: holds up to 4 threads, softens by 8

Throughput mode (`ops/ns`), `clickbench_flat` and `small_sparse`, same JVM
flags as the single-threaded runs:

| threads | host1 (4 cores) simd/jackson ratio | host2 (8 cores) simd/jackson ratio |
|---|---|---|
| 1 | clickbench 1.57x · small_sparse 1.81x | clickbench 1.75x · small_sparse 1.55x |
| 2 | clickbench 1.53x · small_sparse 1.62x | clickbench 1.71x · small_sparse 1.55x |
| 4 | clickbench 1.64x · small_sparse 1.50x | clickbench 1.70x · small_sparse 1.56x |
| 8 | n/a (4 cores) | clickbench 1.51x · small_sparse 1.40x |

**Mild contention, not a dealbreaker.** Both paths scale close to linearly
through 4 threads (host1: 1x→4x cores ≈ 3.8–4x throughput for both; host2
similar). At 8 threads on host2 the simdjson/Jackson ratio softens
(1.75x→1.51x on clickbench_flat, 1.55x→1.40x on small_sparse) — consistent
with the shared `FrozenFieldNameTable`'s CAS-based field-name learning
introducing a little more contention than Jackson's per-parser symbol table,
though simdjson stays faster than Jackson at every thread count tested.

### H6 — cold-start tax: real, and shape-dependent

`simdJsonToMapColdStart` (fresh `SimdJsonParserPool`, 20-doc batch) vs.
`simdJsonToMapWarmEquivalentBatch` (same 20 docs, already-warm pool):

| shape | host1 (cold/warm) | host2 (cold/warm) |
|---|---|---|
| clickbench_flat | 1.25x | 1.37x |
| otel_nested | OOM-killed* | 1.67x |
| small_sparse | OOM-killed* | 3.30x |
| wide_flat | 1.54x | 2.04x |
| array_heavy | OOM-killed* | 1.57x |

\* On host1 (7.6 GiB RAM), repeatedly constructing a `SimdJsonParserPool` at
JMH's invocation rate intermittently exhausted native memory and got the JVM
SIGKILL'd (exit 137) before the measurement iteration finished — it
completed fine on host2 (15 GiB). This is itself a useful finding, not just
noise: **`SimdJsonParserPool`/`SimdJsonParser` have no explicit `close()`
path exposed through the pool** (only the underlying `SimdJsonParser` itself
is `AutoCloseable`), so their native `StructuralIndexer` buffers are
reclaimed via GC-driven cleanup, not deterministically. Creating a fresh pool
per request/small-bulk at high request rates is a real native-memory-pressure
risk, independent of the CPU-time cold-start tax below.

**Confirmed and non-trivial.** A cold pool costs 1.25x–3.3x a warm one for
the *same* 20 documents — worst for `small_sparse` (3.3x on host2), because
the fixed per-thread setup cost (native context creation, first-sight
field-name learning) is amortized over the fewest bytes/fields for that
shape. For a system that creates one parser pool per bulk request (matching
how the reviewed field-name-merge PR frames the problem), short bulks pay a
real, shape-dependent tax that this benchmark's steady-state numbers above
don't capture at all.

### H9 — hypothetical native stage 2: quantified as a net loss

`es_simdjson.cpp` exposes only stage 1 (structural indexing); stage 2 (token
walk + value materialization) is deliberately pure Java, one native call per
*document* rather than per *token*, precisely to avoid FFI-crossing overhead.
Building a genuine native stage 2 needs new native entry points and a
native rebuild across every platform (`elasticsearch.native-library-build`'s
docker cross-toolchain) — out of scope for a same-session spike. As a proxy,
`stage1FfiCrossingProbe` isolates the fixed per-call native-crossing cost
using a reused `SimdJsonParser` against a minimal 2-byte document:

| host | per-call FFI crossing cost |
|---|---|
| host1 (arm) | 45.5 ns |
| host2 (x86) | 52.9 ns |

`clickbench_flat` has ~100 fields. A hypothetical "one native call per
token/field" stage 2 would add **~4,500–5,300 ns** of pure crossing overhead
per document from that alone — more than the *entire* current
`simdJsonToMap` budget (5,244–6,127 ns) on both hosts. **Rejected outright,
quantitatively**: per-token native calls would erase the win and likely make
simdjson-to-Map slower than Jackson. This confirms the existing
one-native-call-per-document design (stage 1 native, stage 2 fused Java) is
the right architecture, not an accidental performance-neutral choice.

### H8 — CPU profiling: where the time actually goes

CPU flamegraphs (async-profiler, 25s @ 100 Hz, `clickbench_flat`,
steady-state) for `jacksonToMap` and `simdJsonToMap` on both hosts are
committed under [`profiles/`](profiles/) in this branch — open the `.html`
files directly in a browser for the interactive view (search box, zoom).

Qualitatively, both flamegraphs land in the same place for the *second half*
of the work: `HashMap.put`, `String` decode, and (for `clickbench_flat`'s
mixed numeric/string/boolean fields) autoboxing dominate a large, roughly
equal-sized share of both profiles — consistent with `jacksonToMap` and
`jacksonToMapAlloc` (and `simdJsonToMap`/`simdJsonToMapAlloc`) always scoring
within noise of each other in every sweep above: whether the built `Map` is
returned-and-measured or blackholed doesn't change the cost, because nothing
about *building* it is skippable at the JIT level. The difference between the
two profiles is concentrated in the *first half*: Jackson's incremental
token-pull (`ESUTF8StreamJsonParser` / `JsonXContentParser` number/string
parsing) vs. simdjson's SIMD structural scan + fused walker — exactly the
segment the H1/H2/H3 hypotheses (which all target the *second, shared* half)
correctly predicted they couldn't move much.

### H10 — lazy value materialization: a real, but conditional, win

Prompted by a follow-up question: many real callers (ingest processors,
scripts) read only a handful of known fields out of a much larger document.
Could a `Map<String, Object>` defer decoding a field's value until the first
time it's actually read via `get(key)`, instead of eagerly decoding every
field during the walk?

**Feasibility, given the existing `JsonDocumentHandler` API: partial.**
Every scalar leaf method (`stringField`, `longField`, `bigIntegerField`,
`doubleField`, `booleanField`) hands the handler the raw source byte range
even when a value is already parsed (see the interface Javadoc), so a leaf
value's decode/box step *can* be deferred cheaply — just stash
`(buf, off, len)` and decode on first `get()`. But `startObject`/`startArray`
carry no byte range at all, so a nested object/array's *walk* can't be
skipped or deferred independently of the top-level walk — the SAX walker
always delivers every descendant event for a nested subtree, whether or not
the handler ends up doing anything with them. So laziness here can only
defer *materialization* (decode a `String`, parse a `BigInteger`), not the
walk itself. Skipping whole unread subtrees would need either a walker
change (byte ranges on `startObject`/`startArray`) or a "record events as a
flat tape, replay on demand" representation instead of eagerly recursing
into a real container — out of scope for this spike.

**Implementation:** [`LazyValueMap`](libs/simdjson/src/benchmark/java/org/elasticsearch/benchmark/xcontent/LazyValueMap.java)
(`AbstractMap<String, Object>` wrapping a plain `HashMap`) +
[`LazyMapDocumentHandler`](libs/simdjson/src/benchmark/java/org/elasticsearch/benchmark/xcontent/LazyMapDocumentHandler.java).
Every object level (root and nested) is exposed as a `LazyValueMap`.
`stringField`/`bigIntegerField` store an unmaterialized `LazyString`/
`LazyBigInteger` holder (raw bytes, no allocation of the real value);
`get(key)` decodes on first access and caches the result back into the
backing map. `size()`/`containsKey()`/`keySet()` don't force
materialization; `entrySet()` (and anything `AbstractMap` builds from it —
`equals`, `toString`, `values()`) does, since those need every value
regardless. Numbers/booleans/nulls stay eager (H1-H3 already showed boxing
them is too cheap to bother deferring); arrays stay eager too (same
byte-range limitation as nested objects).

**Benchmark design:** three fixed corpora, each read via up to three access
patterns, `MapDocumentHandler` (eager) vs `LazyMapDocumentHandler` (lazy):
- `NoAccess` — build, call only `size()`. Laziness's ceiling (nothing read).
- `FewFields` — build, read 2 known fields. The pattern actually asked about.
- `AllFields` — build, recursively read every value. Laziness's floor
  (nothing is actually skipped).

Corpora: `clickbench_flat` (~100 fields, ~30 short/empty string constants —
worst case for "expensive to decode"), `otel_nested` (nested, 3 of 6
top-level fields are objects — worst case for the "walk can't be skipped"
limitation), and a new `largeBodyDocs` (`id`/`level`/2×~2 KB text fields —
the realistic "skip a large log body/stacktrace" case that neither existing
shape covers, since both use a small fixed word list for every string
field). `AllFields` wasn't run for `largeBody` — the mechanism is already
unambiguous from the other two corpora.

| corpus | pattern | host1 eager → lazy | Δ | host2 eager → lazy | Δ |
|---|---|---|---|---|---|
| clickbench_flat | NoAccess | 6489 → 6184 ns | **−4.7%** | 5349 → 5207 ns | **−2.7%** |
| clickbench_flat | FewFields | 6476 → 6439 ns | −0.6% | 5329 → 5298 ns | −0.6% |
| clickbench_flat | AllFields | 6804 → 8020 ns | **+17.9%** | 5887 → 7319 ns | **+24.3%** |
| otel_nested | NoAccess | 1856 → 1735 ns | **−6.5%** | 1594 → 1465 ns | **−8.1%** |
| otel_nested | FewFields | 1873 → 1776 ns | −5.2% | 1615 → 1563 ns | −3.2% |
| otel_nested | AllFields | 2132 → 2609 ns | **+22.4%** | 1879 → 2403 ns | **+27.9%** |
| largeBody (2×~2KB fields) | NoAccess | 7231 → 6717 ns | **−7.1%** | 5406 → 5056 ns | **−6.5%** |
| largeBody (2×~2KB fields) | FewFields | 7244 → 6764 ns | **−6.6%** | 5450 → 5123 ns | **−6.0%** |

(host1 = 4-core aarch64, host2 = 8-core x86_64; avgt ns/op, 3 warmup + 5
measurement × 10s, both machines otherwise idle. Same correctness self-check
as every other handler — `LazyMapDocumentHandler`'s output `.equals()`
Jackson's for every doc in every corpus, including nested `LazyValueMap`s
compared recursively via `AbstractMap`'s default `equals()` — passed before
any number above was trusted.)

**Accepted, conditionally — the first hypothesis in this evaluation with a
genuine, positive, reproducible effect, but it cuts both ways:**

- **`NoAccess` wins on every shape, including `clickbench_flat`'s trivially
  cheap short/empty strings.** This was initially surprising — the
  hypothesis going in was that laziness only pays off for *expensive*
  decodes — but it holds even for a 0-8 character string, because a
  `LazyString` holder is a 3-field record referencing the *existing*
  document byte array (no copy), while eagerly decoding is
  `new String(buf, off, len, UTF_8)` (UTF-8 validate + copy + allocate) even
  when `len` is tiny. Deferring is cheaper than doing, independent of size,
  whenever the result is never asked for.
- **`FewFields` (2 of ~6-100 fields) is a small win on `clickbench_flat`/
  `otel_nested` (their skipped strings are cheap to begin with) and a real
  ~6-7% win on `largeBody` (skipped strings are ~2 KB) — consistent across
  both architectures.** This is the pattern the original question was about
  (ingest processors/scripts reading a handful of known fields), and it's a
  genuine, if modest, win.
- **`AllFields` is a clear, consistent loss (+18-28%).** Once every value
  ends up read, laziness has paid for *two* allocations per string field
  (the `LazyString` holder, then the real `String` on first `get`) plus the
  `AbstractMap`/`get()`/`instanceof` indirection, against eager's *one*
  allocation. This is the mirror image of H1-H3: bookkeeping that pays off
  when it avoids real work becomes pure overhead when it doesn't.
- **The break-even point wasn't pinned down precisely** (only "2 fields" and
  "every field" access patterns were tested), but the direction is clear:
  laziness is a bet that most fields *won't* be read, and it pays off
  smoothly as that bet gets more true (fewer fields read, and/or the unread
  fields are individually more expensive to decode) and loses smoothly as it
  gets less true.
- Unlike H1-H9, this hypothesis's outcome depends on the **caller's access
  pattern**, not just the document shape — a property any production
  decision here would need to reflect. It's a plausible win for a call site
  known to touch a small, fixed subset of fields (an ingest processor or
  script with an explicit field list), and a regression for one that ends up
  touching most/all fields (e.g. `XContentHelper.convertToMap` used to
  reconstruct `_source` for return/re-indexing).
- Not profiled separately with async-profiler — the `NoAccess`/`FewFields`/
  `AllFields` comparison already isolates the mechanism (allocation count
  and type per field) more precisely than a flamegraph would, and H8's
  profiling already established that `HashMap.put`/`String` decode/boxing
  is the dominant "second half" cost this hypothesis is trying to move.

### H11 — folding stage1+stage2 DOM-style, and numerics vs. strings on a tape

Follow-up question: the native/Java split still runs stage 1 (native) and
stage 2 (Java) separately. Could the two be folded together, DOM-style —
and should numerics be treated differently from strings for the purpose of
building a tape?

**Folding strings/structure into a native DOM tape: rejected on
architectural grounds, no prototype needed.** The vendored `simdjson.h`
already has the DOM API we don't use: `dom_parser_implementation::parse()`
(fused stage1+stage2) produces a `dom::document` with a `tape` (`uint64_t[]`)
and a `string_buf` (`uint8_t[]`). Its `tape_type`/`tape_ref` encoding
(`internal/tape_type.h`, `tape_ref::get_string_view()`) shows *every* string —
escaped or not — is unconditionally copied+unescaped into `string_buf`, with
the tape word holding an offset into it. Compare that with what
`SimdJsonDirectWalker` already does: for the escape-free case (the common
one), the handler gets `(buffer, off, len)` pointing directly at the
*original* document bytes — zero copies before the final `new String(...)`,
which is the theoretical floor for producing a `java.lang.String` at all (it
must own its backing array; no API lets it alias off-heap/`MemorySegment`
memory). Routing through the native tape instead would mean: one
unconditional native copy into `string_buf`, *plus* a second copy out of
off-heap memory into a heap `byte[]` for `new String(...)` to consume (no
API constructs a `String` directly from a `MemorySegment`). That's strictly
more copies than today for the common case — a regression, not a win — so
this alone rules out DOM-style folding for strings/objects/arrays without
needing to build anything to measure it. (This isn't an FFI-crossing
argument, unlike H9 — folding stage 2 in would still be one native call per
document, same granularity as stage 1 today. The problem is the tape's data
layout, not crossing count.)

**Numerics are a genuinely different case, and *worth* measuring.** The
tape's numeric encoding packs the parsed value inline — `tape[i]` is a type
tag only, `tape[i+1]` is the raw `int64`/`double` bits, `memcpy`'d directly
(`tape_ref::next_tape_value`) — free to read, no format tax either way. But
`DoubleParser` and the SWAR integer-digit-widening loop in
`SimdJsonDirectWalker.handleNumber`/`parse8Digits` are already faithful Java
ports of simdjson's own algorithms (Eisel-Lemire, the same 8-digits-at-once
trick), so there's no *algorithmic* gap for native to close — only a
JIT-vs-AOT codegen question on scalar bit-twiddling arithmetic, which needed
measuring, not assuming.

**Prototype:** [`simdjson_parse_numbers_batch`](libs/simdjson/native/src/es_simdjson.cpp)
— a new native function, added alongside (not replacing) the existing
stage-1-only entry points, that parses many numbers in **one call for the
whole batch** (matching the per-document, not per-token, discipline H9
established as essential — see the FFI-crossing-cost numbers there).
Deliberately fast-path-only (≤19 significant digits, decimal exponent in
[-22, 22] — mirrors `DoubleParser`'s own fast/slow split); anything outside
that reports `NEEDS_FALLBACK` and the caller re-parses that one number in
Java, so correctness never depends on the fast path's coverage. Exposed to
Java via [`NumberBatchParser`](libs/simdjson/src/main/java/org/elasticsearch/simdjson/NumberBatchParser.java)
(a new `@Critical` FFM binding on `SimdJsonLibrary`), correctness-checked
against `Long`/`Double.parseDouble` and against a verbatim copy of the real
`SimdJsonDirectWalker` number-parsing path
([`JavaNumberParser`](libs/simdjson/src/benchmark/java/org/elasticsearch/benchmark/xcontent/JavaNumberParser.java))
before any benchmark number was trusted. Built locally via
`SIMDJSON_NATIVE_BUILD=host` (`make local-install` — no docker
cross-toolchain needed for a same-machine spike; see the `nativeLibraryBuild`
block in `libs/simdjson/build.gradle`), so — unlike H9 — this one *was*
prototyped, not just estimated.

**Benchmark:** [`NumberBatchParsingBenchmark`](libs/simdjson/src/benchmark/java/org/elasticsearch/benchmark/xcontent/NumberBatchParsingBenchmark.java)
parses 5000 numbers per invocation, one number at a time in Java
(`JavaNumberParser`, the real production algorithm) vs. one native call for
all 5000, across three shapes: `ints` (random 0–999,999,999), `doubles`
(random few-decimal values, e.g. prices/percentages/metrics — realistic
JSON float shapes, all within the native fast path by construction), and
`mixed` (50/50). No strings, no structure, no `Map` building — isolates just
the parsing arithmetic.

| shape | Java (real path) | native (batched) | Δ |
|---|---|---|---|
| ints | 5.63 ± 0.11 ns/op | 7.79 ± 0.42 ns/op | **+38% (native slower)** |
| doubles | 14.85 ± 0.60 ns/op | 8.49 ± 0.09 ns/op | **−43% (native faster)** |
| mixed | 15.53 ± 0.48 ns/op | 10.95 ± 0.43 ns/op | **−29% (native faster)** |

(macOS, Apple M-series aarch64, JDK 26, 2 forks × 3 warmup + 5 measurement ×
3s — a laptop, not the dedicated AWS boxes used for H1-H10, since those
instances were unreachable when this was run; error bars are tight and
non-overlapping between Java/native for every shape, so the direction is
trustworthy even if absolute ns/op wouldn't be comparable to the rest of
this document. Revisit on the AWS hosts before relying on this for a
production decision.)

**Accepted for doubles, rejected for ints — numerics need splitting
further, not just numbers-vs-strings:**

- **Integers: Java already wins, cleanly.** The SWAR 8-digits-at-once loop
  is cheap enough that even a single batched native call's fixed overhead
  (array pinning, the `@Critical` transition) isn't recovered by anything
  faster happening on the native side — there's nothing left to win for
  pure integer parsing.
- **Doubles: native wins substantially (~43%).** `DoubleParser.parse()`
  first runs `shouldBeHandledBySlowPath` (a digit scan) before it even
  reaches the cheap `computeDouble` fast-path branch, and `computeDouble`
  itself carries the full Eisel-Lemire machinery (128-bit multiply tables)
  as a fallback path even when the simple multiply-by-power-of-ten branch
  is taken. The native prototype only implements that simple branch (by
  design — see the fast-path scope above), so this comparison is really
  "real dispatch-heavy Java vs. a lean native fast-path-only routine doing
  the same simple arithmetic" — and the lean routine wins clearly.
- **This refines the original question's framing**: it's not just
  "numerics vs. strings" (strings are a clear no per above) — it's
  "integers vs. floats" *within* numerics. A hybrid design folding only
  float parsing into a batched native call (leaving integers, strings, and
  structure exactly as they are today) is the one concretely promising
  direction this evaluation found for going further into native code.
- Not integrated into `SimdJsonDirectWalker`/any document-walking pipeline —
  this isolates the arithmetic only. Wiring a float-only native fast path
  into the real walker (matched up by position with structural indices, one
  batched call before or during the walk) would need its own follow-up
  measurement of the full document-shaped cost, not just the isolated
  per-number cost above.

#### Why is Java's double fast path ~43% slower, given it's "the same algorithm"?

Dug into this with `-XX:+PrintInlining`/`-XX:+PrintCompilation` and JIT
disassembly (`-XX:+PrintAssembly` + [hsdis](https://chriswhocodes.com/hsdis/),
sha256-verified, loaded from a scratch dir — not installed into any shared
JDK). Two distinct, additive causes, both concrete and fixable in principle,
neither an algorithm gap:

**1. `DoubleParser.computeDouble` (435 bytes of bytecode) doesn't inline.**
`-XX:+PrintInlining` shows it consistently as `failed to inline: hot method
too big` into `DoubleParser.parse` → `JavaNumberParser.parseFloatingPoint`,
even at C2/tier 4 — it exceeds HotSpot's default hot-method inline budget
(`-XX:FreqInlineSize`, 325 bytes on this JDK/platform). Every double parsed
pays a real call+return (frame setup, register spills across the call
boundary) that a single small native function — always inlined at `-O3`, or
at worst a cheap intra-TU sibling call — doesn't. Confirmed causally: raising
the budget (`-XX:FreqInlineSize=500`, letting it inline) drops `javaParse`
(doubles) from 14.85 ns/op to **13.12 ns/op** — recovers about a third of the
gap to native, by itself, with no other change.

**2. The (non-inlined) `computeDouble` body pays several fixed JIT-method
costs the native fast path structurally cannot.** Comparing the disassembly
of the fast-path branch (`abs(exp10) < 23 && significand fits in 53 bits` —
the branch this benchmark's data always takes) side by side:

| | Java (`DoubleParser.computeDouble`, C2) | Native (`simdjson_parse_numbers_batch`, clang -O3) |
|---|---|---|
| nmethod entry barrier | `ldr`+`cmp`+`b.ne` on every call (GC/class-redefinition safety) | none — not a managed runtime |
| `POWERS_OF_TEN`/`POW10` table address | 3 instructions (`mov`+`movk`+`movk`) materializing a full 48-bit absolute heap address, **on every call** | `adrp`+`add` computed **once before the whole 5000-number loop**, reused via one register for every number |
| bounds check on the table load | explicit `cmp`+`b.cs`→uncommon-trap, even though the same value's range was just checked moments earlier (JIT couldn't prove the freshly-negated register was the same value, so range-check elimination didn't fire) | none — pointer arithmetic (`sub x17, x14, x7, lsl #3` for the negative-exponent case) needs no separate bounds check at all |
| return path | safepoint poll (`ldr`+`cmp`+`b.hi`→safepoint blob) before `ret` | plain `ret`, no polling |

None of these are the arithmetic itself (`fmul`/`fdiv`/`fneg`/`fcsel` are
identical either way, and identically cheap) — they're the fixed tax of
being a safely-managed, GC-relocatable, safepoint-able JIT method call,
which a native leaf function simply doesn't owe. The table-address point is
also *structural*, not incidental: because the native routine is one
function looping over the whole batch, the compiler hoists the
loop-invariant table address out of the loop entirely (paid once for 5000
numbers); Java's `computeDouble` is a fresh call every time with no
cross-call state, so it re-materializes that address on every single
invocation. A batched call isn't just amortizing FFI-crossing cost (H9's
point) — for this specific piece, it's also amortizing work an unrolled/
inlined-into-a-loop native routine gets to hoist that a per-value JIT method
call structurally cannot.

**Not itself a reason to change anything in `DoubleParser`** — this was a
diagnostic dig to answer *why*, not a proposal to raise `FreqInlineSize`
process-wide (a global JIT tuning flag, with its own tradeoffs, is out of
scope for one call site) or to hand-inline `computeDouble` into its callers
(README-worthy but separate work, and would need its own before/after
measurement in the real `SimdJsonDirectWalker` path, not just this isolated
benchmark).

## Conclusion

- The baseline ~25–45% win (real hardware, both architectures, 5 shapes
  including wide and array-bearing ones) is genuine and holds broadly across
  document shapes — **H5/H7 confirmed the win generalizes**, it doesn't
  depend on the original 3 shapes being cherry-picked.
- Three concrete "make it faster" hypotheses aimed at the shared
  Map/List/boxing half of the cost (**H1** pre-sizing, **H2** value
  interning, **H3** container pooling) all came back **negative or flat** on
  both hosts, across every shape tested — the profiler (H8) explains why:
  that half of the cost is identical, JIT-optimized allocation/put/decode
  work that isn't profitably short-circuited by these techniques at this
  scale. This is a well-supported negative result, not an inconclusive one.
- **H9 quantitatively rules out** a deeper architectural change (native
  stage 2) using the codebase's own FFI-crossing cost — a useful, cheap
  proxy measurement given a full native rebuild wasn't in scope.
- **H4** (concurrency) and **H6** (cold start) surfaced real *operational*
  characteristics worth carrying into any production design: the win softens
  mildly under heavy concurrency (shared field-name-table contention), and a
  freshly-constructed parser pool is meaningfully slower *and* a native-memory
  risk until it's warm — so a production integration should reuse pools
  across requests/bulks, not create one per bulk.
- **H10** (lazy value materialization) is the one hypothesis that found real
  headroom — but, tellingly, only by doing exactly what the original
  conclusion below predicted would be required: **changing what gets
  built** (a `LazyValueMap` that defers `String`/`BigInteger` decoding,
  not a plain `HashMap`). It's a genuine ~3–8% win when the caller only
  reads a handful of fields (bigger for large skipped values), and a clear
  ~18–28% *regression* when the caller reads everything — so it's
  conditional on the access pattern, not a strict improvement, and would
  need to be opt-in per call site rather than a drop-in replacement for
  `MapDocumentHandler`.
- **H11 revisited H9's rejection of native stage 2 with an actual prototype**
  (not just the FFI-crossing proxy) and found the honest answer is
  "it depends which part of stage 2": folding strings/structure into a
  native DOM tape is a clear regression (the tape format forces an extra
  copy the current zero-copy-on-no-escape Java path already avoids), but
  folding *float* parsing into one batched native call is a real ~43% win
  over the real Java path, while *integer* parsing is better left in Java
  (native is ~38% slower there). So "numerics vs. strings" was half right —
  the more precise split this evaluation found is strings (no), integers
  (no), floats (yes, conditionally on doing it as a batched call).
- **Bottom line:** the simdjson-backed `Map` builder is a solid, consistent,
  bounded win (~1.4–1.7x) for this use case, driven entirely by its faster
  tokenizer. Of the improvement hypotheses aimed at the shared
  container/boxing half of the cost, H1-H3 (all targeting allocation
  avoidance without changing the target shape) found nothing; H10 (changing
  the target shape) and H11 (moving float parsing to native) each found a
  real, if conditional and narrow, win. The ceiling for a drop-in,
  access-pattern-agnostic `Map` builder is the shared container/boxing
  cost, not the tokenizer, and that ceiling is hard to move without either
  changing what gets built (H10), narrowing which numbers get parsed where
  (H11), or accepting a narrower, access-pattern-specific contract.
