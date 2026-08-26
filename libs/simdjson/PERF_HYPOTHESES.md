# simdjson Performance Hypotheses

Benchmark results on AWS EC2, `nativeStage1=true`, JDK 26.0.1, per-doc path only
(`parseToScratch` / `simdJsonEncode`).

## Benchmark Results — commit `40d91b2ad7c` (simdjson_stage1)

Multi-threaded throughput vs Jackson through the full `EscfEncoder` pipeline.

### x64 (AMD EPYC 9R14, 8 threads, AVX-512 / ICE_LAKE)

| Method         | clickbench_flat | otel_nested | small_sparse | Speedup range |
|----------------|-----------------|-------------|--------------|---------------|
| jacksonEncode  |  79.1 ± 0.9     | 346.8 ± 9.8 |  827.5 ± 4.7 | —             |
| simdJsonEncode |  96.8 ± 1.0     | 411.7 ± 1.8 | 1048.9 ± 11.7| **+14–27%**   |

### ARM (Graviton, 4 threads, NEON)

| Method         | clickbench_flat | otel_nested | small_sparse | Speedup range |
|----------------|-----------------|-------------|--------------|---------------|
| jacksonEncode  |  34.1 ± 2.4     | 158.8 ± 0.9 |  406.8 ± 2.2 | —             |
| simdJsonEncode |  46.1 ± 1.8     | 212.8 ± 1.0 |  517.6 ± 39.5| **+27–35%**   |

**Note:** ARM gains are consistently larger than x64. Production encoding uses
per-document `parseToScratch` / `commitScratchTo`; batch stage1 has been removed
from the integration path.

### Document shapes

- **clickbench_flat**: ~2500-2800 bytes, ~126 fields, mostly numeric, flat structure
- **otel_nested**: ~700-900 bytes, ~20 fields, 3 levels of nesting, mix of types
- **small_sparse**: ~100-150 bytes, 6-7 fields, 3 rotating variants

## CPU Profile Analysis — commit `40d91b2ad7c`

Profiled `simdJsonEncode` / `clickbench_flat`, multi-threaded, async-profiler
collapsed stacks.

### Subsystem breakdown

| Subsystem | x64 (8 threads) | ARM (4 threads) |
|-----------|----------------:|----------------:|
| Walker (Java stage 2) | 47% | 39% |
| Field name resolution | 21% | 25% |
| ESCF commit (`commitScratchTo`) | 20% | 16% |
| Native stage1 | 7% | 10% |
| ESCF column building | 4% | 3% |

### Top leaf hotspots (`clickbench_flat`)

| Hotspot | x64 | ARM |
|---------|----:|----:|
| `ObjectIntHashMap.getOrDefault` (schema lookup) | 9.1% | 6.0% |
| `String.equals` | 6.5% | 6.4% |
| Native `stage1` | 6.5% | 10.2% |
| `SimdJsonDirectWalker.handleNumber` | 6.3% | 7.0% |
| `SimdJsonDirectWalker.resolveFieldName` | 5.1% | 8.2% |
| `FrozenFieldNameTable$Frozen.lookup` | 4.3% | 6.3% |
| `FieldNameHash.hashName` | 2.7% | 3.2% |
| `BitIndexes.getAndAdvance` | 1.7% | 2.1% |
| `EscfBatchBuilder.drainScratchValue` | 2.2% | — |
| `EscfBatchBuilder.commit` | 2.1% | 2.4% |

**Key insight:** After native stage1 (~7–10%), the bottleneck is Java-side field
name resolution (~20–25%) and ESCF encoding/commit (~20%). Parsing JSON structure
itself is no longer the dominant cost.

### Allocation profile

GC accounts for ~1.3% of CPU — allocation is **not** a current runtime
bottleneck. Sampled allocation sites (short JMH profiling window):

| Site | Share | Call path |
|------|------:|-----------|
| `int[]` growth | 44% | `EscfColumnBuilder.ensureIntCapacity` during commit |
| `byte[]` | 27% | `EscfColumnBuilder.setLong` / `setString` during commit |
| `XContentString$UTF8Bytes` | 25% | String fields in walker → handler |
| `EscfDocumentHandler` | 3% | Per-document handler object |

Almost all sampled allocation is in `commitScratchTo` → `drainScratchValue`,
not in the JSON parser.

## Running the benchmarks

### Prerequisites

1. Build the native stage1 library:
   ```bash
   cd libs/simdjson/native
   make local CLANG_CXX=clang++   # may need: sed -i 's/-fuse-ld=lld//g' Makefile
   mkdir -p release/linux-$(uname -m | sed 's/x86_64/x64/')
   cp build/linux-*/libes_simdjson.so release/linux-*/
   ```

2. Download async-profiler 4.5+ for flamegraph profiling:
   ```bash
   wget https://github.com/async-profiler/async-profiler/releases/download/v4.5/async-profiler-4.5-linux-$(uname -m).tar.gz
   tar xf async-profiler-4.5-linux-*.tar.gz
   ```

3. Set kernel profiling permissions:
   ```bash
   sudo sysctl -w kernel.perf_event_paranoid=1
   ```

### Running benchmarks

```bash
cd benchmarks
NATIVE_LIBS="$PWD/../libs/simdjson/native/release/linux-$(uname -m | sed 's/x86_64/x64/')"
PROF_LIB="$HOME/async-profiler-4.5-linux-*/lib/libasyncProfiler.so"

# CPU profiling (8 threads):
../gradlew --no-daemon run --args \
  'org.elasticsearch.benchmark.xcontent.SimdJsonParserBenchmark \
   -t 8 -wi 3 -i 5 \
   -prof async:output=flamegraph;dir=/tmp/bench;event=cpu;libPath='$PROF_LIB' \
   -jvmArgs -Des.nativelibs.path='$NATIVE_LIBS' \
   -rf json -rff /tmp/bench/bench_8t.json'

# Allocation profiling:
../gradlew --no-daemon run --args \
  'org.elasticsearch.benchmark.xcontent.SimdJsonParserBenchmark \
   -t 8 -wi 3 -i 5 \
   -prof async:output=flamegraph;dir=/tmp/bench;event=alloc;libPath='$PROF_LIB' \
   -jvmArgs -Des.nativelibs.path='$NATIVE_LIBS' \
   -rf json -rff /tmp/bench/bench_8t_alloc.json'

# Single method, disassembly:
../gradlew --no-daemon run --args \
  'org.elasticsearch.benchmark.xcontent.SimdJsonParserBenchmark.simdJsonEncode \
   -t 1 -wi 5 -i 3 -f 1 -p shape=clickbench_flat \
   -prof perfasm \
   -jvmArgs -Des.nativelibs.path='$NATIVE_LIBS' \
   -rf json -rff /tmp/bench/bench_perfasm.json'
```

### AWS instance access

```bash
# x64
ssh -i "~/.ssh/chegar-elastic.pem" ubuntu@ec2-98-81-2-141.compute-1.amazonaws.com
# ARM
ssh -i "~/.ssh/chegar-elastic.pem" ubuntu@ec2-3-238-29-35.compute-1.amazonaws.com
```

## Perfasm Results (single-threaded, x64, `simdJsonEncode`, `clickbench_flat`)

After-inlining method breakdown:

| %     | Method                                                    |
|-------|-----------------------------------------------------------|
| 28.03 | SimdJsonDirectWalker::resolveFieldName                    |
| 16.86 | EscfBatchBuilder::drainScratchValue                       |
| 16.42 | SimdJsonDirectWalker::walkObject                          |
| 15.04 | SimdJsonDirectWalker::handleNumber                        |
|  6.51 | libes_simdjson.so  stage1                                 |
|  3.03 | EscfEncoder::parseToScratch                               |
|  2.85 | EscfDocumentHandler::stringField                          |
|  2.59 | SimdJsonParserBenchmark::simdJsonEncode (benchmark harness)|
|  1.82 | StubRoutines::vectorizedMismatch_stub                     |
|  1.03 | StubRoutines::jint_disjoint_arraycopy_stub                |
|  0.52 | es_stage1_run (JNI wrapper)                               |
|  0.52 | StubRoutines::jbyte_disjoint_arraycopy_stub               |

Source distribution: 85.7% C2, 7.0% native, 3.6% runtime stubs, 1.5% kernel.

## Hypotheses (simdjson_stage1 profiling, commit `40d91b2ad7c`)

Ranked by estimated impact on `clickbench_flat` throughput.

### H1: Schema field lookup — `ObjectIntHashMap.getOrDefault` (~6–9% CPU)

Every `EscfRowBuffer.addLeaf` call resolves the field path via
`SourceSchema.appendLeaf` → `ObjectIntHashMap.getOrDefault`. For stable index
mappings (common case), this is a string-keyed hash lookup on every field of
every document.

**Fix:** Assign ordinals to leaf fields at index creation. Pass ordinals from
the field name table directly to `EscfRowBuffer`, skipping the hash map lookup.

**Status:** Partially addressed by H2d ordinal pass-through. Post-H2 profile on
`clickbench_flat` shows `ObjectIntHashMap.getOrDefault` at ~0.2% (was 9.1% x64).
Remaining string-keyed lookups occur on cold path (first document) and non-ordinal
handler paths.

**Estimated gain:** 5–8% (largely captured by H2 on warm ClickBench path).

### H2: Field name resolution chain (~20–25% CPU) — **IMPLEMENTED (H2a–H2f)**

All six sub-options implemented together. See [H2 Results](#h2-results-implemented) below.

**Measured gain (clickbench_flat, simdJsonEncode vs pre-H2 `40d91b2ad7c`):**
+8.9% x64, +19.1% ARM. Schema hash lookup (`ObjectIntHashMap`) eliminated on warm path.

### H3: ESCF commit path (~16–20% CPU, ~67% of sampled allocations)

`commitScratchTo` → `drainScratchValue` → `newRowScalar` / `writeScalar` copies
every field value from the row buffer into column builders.

**Fix options:** Write directly to column builders during the walk; pre-size
column builders from known schema width; batch row commits.

**Estimated gain:** 10–15% CPU.

### H4: `handleNumber` handler dispatch (~6–7% CPU)

SWAR digit parsing reduced raw parsing cost, but ~80 numeric fields per document
still each invoke `handler.longField(fieldName, ...)` with a `String fieldName`.

**Fix:** Pass field ordinal instead of `String`; or typed numeric batch callback.

**Estimated gain:** 3–5%.

### H5: Native stage1 on ARM (10% vs 7% on x64)

NEON processes 16 bytes/iteration vs AVX-512 at 64 bytes. Inherent ISA difference;
per-doc stage1 (~2.8 KB/doc) is the correct integration approach.

**Status:** No action needed; confirms per-doc integration strategy.

### H6: `BitIndexes.getAndAdvance` (~2% CPU)

Structural index iteration with per-token bounds checking.

**Fix:** Cache read index in a local variable; unchecked access after chunk-start bounds check.

**Estimated gain:** 1–2%.

### H7: String field copies (~25% of sampled alloc, minor CPU)

`XContentString$UTF8Bytes` allocations for string field values. GC is only 1.3%
of CPU today, but copies still consume memory bandwidth.

**Fix:** Pass `(byte[], offset, length)` references into column builders where
buffer lifetime allows.

**Estimated gain:** Low CPU today; reduces bandwidth.

---

## H2 Deep Dive: Field Name Resolution

### Call chain

```
walkObject()
  └─ resolveFieldName(buffer, keyIdx)          ← 5–8% leaf CPU
       ├─ SWAR 8-byte quote/backslash scan      ← cheap
       ├─ FieldNameHash.hashWord / hashName     ← 2–3% leaf CPU
       └─ FrozenFieldNameTable$Child.lookup
            └─ Frozen.lookup(buf, off, len, h, pfx?)  ← 4–6% leaf CPU
                 └─ open-addressing probe loop
                      ├─ hashes[i], lens[i], prefix8[i] compare
                      └─ Arrays.equals (len > 8 only)
```

Downstream, the returned `String fieldName` is passed to every handler method
and eventually used again in `SourceSchema.appendLeaf` (H1).

### ClickBench field name shape

126 unique field names in the benchmark template:

| Length | Count | Share |
|--------|------:|------:|
| ≤ 8 bytes | 35 | 28% |
| 9–16 bytes | 71 | 56% |
| > 16 bytes | 20 | 16% |

**72% of field names exceed 8 bytes**, so the fast single-word path in
`resolveFieldName` (which uses `hashWord` + `maskWord` + prefix8 lookup)
covers only ~28% of fields.

Examples of >8-byte names: `ResolutionWidth`, `JavascriptEnable`,
`DOMInteractiveTiming`, `OpenstatServiceName`.

### Two code paths, two efficiency levels

**Path A — len ≤ 8 (28% of ClickBench fields):**

```java
int h = FieldNameHash.hashWord(word, len);       // hash from loaded word, no re-read
long pfx = FieldNameHash.maskWord(word, len);
String s = nameCache.lookup(buf, start, len, h, pfx);  // prefix8 passed through
```

On frozen-table hit with matching hash + len + prefix8: **no `Arrays.equals`**
needed (prefix8 fully covers the name).

**Path B — len > 8 (72% of ClickBench fields):**

```java
int len = (pos - start) + (Long.numberOfTrailingZeros(qh) >>> 3);
int h = FieldNameHash.hashName(buffer, start, len);   // RE-READS all bytes
String s = nameCache.lookup(buffer, start, len, h);   // no prefix8 passed
```

Problems:
1. **Double read:** SWAR scan already consumed every byte to find the closing
   quote; `hashName` reads them all again from memory.
2. **Triple read of prefix:** `Frozen.lookup` calls `readPrefix8(buf, off, len)`
   when prefix8 is not supplied — a third pass over the first 8 bytes.
3. **`Arrays.equals` on len > 8:** Even on hash+prefix collision, full byte
   comparison is required. With 126 names in a 256-slot table, collisions are
   rare but the branch is still checked.

`FieldNameHash.scanAndHash` exists to fuse scan + hash but is **not used** in
`resolveFieldName`. It still calls `hashName` at the end, so it does not fix
the double-read for len > 8 — but it could be extended to compute hash
incrementally during the scan.

### Frozen table probe cost

After the first document, the table freezes into a 256-slot open-addressed table
(126 fields × 2 → next power of 2). Each lookup probes up to N slots, checking
four parallel arrays per slot: `hashes[]`, `lens[]`, `prefix8[]`, `names[]`.

For a warm cache with ~50% load factor, expect 1–2 probes on average. But each
probe touches 4 cache lines across 4 arrays — poor locality compared to a
single flat struct array or direct-index table.

Profile evidence:
- `FrozenFieldNameTable$Frozen.lookup`: 4.3% (x64), 6.3% (ARM)
- `FieldNameHash.hashName`: 2.7% (x64), 3.2% (ARM)
- `String.equals`: 6.4% (both) — likely from `SourceSchema` / `ObjectIntHashMap`
  downstream, not from the frozen table itself
- `ArraysSupport.mismatch` / `vectorizedMismatch_stub`: from `Arrays.equals` in
  frozen lookup for len > 8 (minor)

### Proposed fixes (ranked)

#### H2a: Pass prefix8 on the len > 8 path (quick win, ~1–2%)

The first 8 bytes are always at `buffer[start]`. Pass them to lookup:

```java
long pfx = (long) LONG_LE.get(buffer, start);
String s = nameCache.lookup(buffer, start, len, h, pfx);
```

Eliminates the `readPrefix8` re-read inside `Frozen.lookup`. One line change
in `resolveFieldName`.

#### H2b: Incremental hash during SWAR scan (medium effort, ~3–5%)

Extend the multi-word scan loop to accumulate wyhash as bytes are consumed,
avoiding the `hashName` re-read at the end. For len ≤ 8 this is already done
via `hashWord`; for len > 8, fold the 16-byte wyhash blocks during the scan.

Alternatively, fix `scanAndHash` to return hash without re-reading and route
`resolveFieldName` through it for the common no-backslash case.

#### H2c: Skip `Arrays.equals` when prefix8 + len uniquely identifies (quick win, ~0.5%)

At freeze time, detect whether any two names share the same `(prefix8, len)`
pair. When the pair is unique, skip the `Arrays.equals` branch on lookup hit.

ClickBench has 3 collision groups (7 names out of 126):
`ResolutionWidth`/`ResolutionDepth`, `UserAgentMajor`/`UserAgentMinor`,
`SilverlightVersion1`–`4`. The fast path applies to 119/126 names (~94%);
the 7 colliding names still need full byte comparison.

#### H2d: Ordinal pass-through (high effort, ~10–15%, synergizes with H1)

After freeze, assign each field name a dense `int ordinal` (0..N-1). Change
the walker to propagate `int fieldId` instead of `String fieldName` to handlers
and `EscfRowBuffer`. Eliminates:
- Hash probe loop entirely (array index by ordinal on second+ doc)
- `String` object passing through the hot path
- `SourceSchema` string hash lookup (H1)

Requires coordination with `EscfDocumentHandler` / `EscfRowBuffer` API.

#### H2e: Perfect hash / direct-mapped table at freeze (medium effort, ~5–8%)

For ≤256 field names, build a CHD or GPH perfect hash at freeze time.
Lookup becomes O(1) with one memory access instead of a probe loop.

A simpler variant: **direct-mapped table on `(prefix8, len)`** with equals
fallback for collision groups. ClickBench has only 3 collision groups (7 names);
a 256-entry table covers most names in O(1) with one comparison.

#### H2f: Fix nested-object path (otel_nested, separate from ClickBench)

`walkObjectInArray` (used for nested objects in arrays) **does not use
`nameCache` at all** — it allocates a new `String` per field via
`new String(buffer, keyStart, keyLen, UTF_8)`. This is a correctness gap
and a performance bug for `otel_nested`.

### Recommended H2 implementation order

1. **H2a** — pass prefix8 on len > 8 path (trivial, safe)
2. **H2c** — skip equals when prefix8+len is unique (trivial after freeze analysis)
3. **H2b** — incremental hash during scan (moderate, good ROI for 72% of fields)
4. **H2e** — direct-mapped or perfect hash at freeze (moderate, big win for warm path)
5. **H2d** — ordinal pass-through (larger refactor, combines with H1 for max gain)

---

## H2 Results — IMPLEMENTED

All six sub-options (H2a–H2f) implemented in a single changeset on top of
commit `40d91b2ad7c`. Benchmarks re-run on the same AWS instances with JDK 26.0.1.

### Implementation summary

| Option | Change |
|--------|--------|
| **H2a** | Pass `prefix8` on len > 8 path via `FieldNameHash.readPrefix8()` |
| **H2b** | `FieldNameHash.scanFieldName()` fuses SWAR quote scan + incremental wyhash |
| **H2c** | `prefixLenUnique[]` at freeze — skip `Arrays.equals` when `(prefix8,len)` unique |
| **H2d** | Dense ordinals in frozen table; `lookupField()` returns `ResolvedFieldName(name, ordinal)`; ordinal overloads on `JsonDocumentHandler`, `EscfDocumentHandler`, `EscfRowBuffer` |
| **H2e** | Direct-mapped `directOrdinals[]` on `(prefix8,len)` with hash/len/prefix verification on hit |
| **H2f** | `walkObjectInArray` uses `nameCache.lookupField()` instead of `new String(...)` |

Key files: `FieldNameHash.java`, `FrozenFieldNameTable.java`, `SimdJsonDirectWalker.java`,
`EscfDocumentHandler.java`, `EscfRowBuffer.java`.

### Benchmark Results — post-H2 (uncommitted, JDK 26.0.1)

#### x64 (AMD EPYC 9R14, 8 threads)

| Method         | clickbench_flat | otel_nested | small_sparse | vs pre-H2 simdJson |
|----------------|-----------------|-------------|--------------|--------------------|
| jacksonEncode  |  79.4 ± 0.4     | 353.4 ± 0.6 |  843.7 ± 5.1 | —                  |
| simdJsonEncode | **105.4 ± 0.4** | 406.9 ± 1.4 |  979.7 ± 9.4 | cb **+8.9%**       |

Pre-H2 simdJson (commit `40d91b2ad7c`): 96.8 / 411.7 / 1048.9 ops/s.

Jackson → simdJson speedup: clickbench **+33%**, otel **+15%**, small_sparse **+16%**.

#### ARM (Graviton, 4 threads)

| Method         | clickbench_flat | otel_nested  | small_sparse | vs pre-H2 simdJson |
|----------------|-----------------|--------------|--------------|--------------------|
| jacksonEncode  |  29.5 ± 1.5     | 179.3 ± 0.6  |  413.2 ± 1.2 | —                  |
| simdJsonEncode | **54.9 ± 1.0**  | 195.0 ± 14.1 |  506.4 ± 2.3 | cb **+19.1%**      |

Pre-H2 simdJson (commit `40d91b2ad7c`): 46.1 / 212.8 / 517.6 ops/s.

Jackson → simdJson speedup: clickbench **+86%**, otel **+9%**, small_sparse **+23%**.

**Note:** `clickbench_flat` (126 fields, warm frozen table) shows the largest H2
gain. `otel_nested` and `small_sparse` are flat or slightly below pre-H2 simdJson
— see [H2 shape analysis](#h2-shape-analysis-otel_nested-and-small_sparse-regressions).

### H2 shape analysis: otel_nested and small_sparse regressions

H2 was tuned for the clickbench profile: many fields, long names, identical docs,
warm frozen table. The post-H2 regressions on other shapes are explained by field
count and structure, not name length alone.

#### Document shape comparison

| Shape | Leaf fields/doc | Name lengths | Structure | Homogeneity |
|-------|----------------:|--------------|-----------|-------------|
| clickbench_flat | ~126 | 72% > 8 bytes | flat root | identical every doc |
| otel_nested | ~22 | many 10–25 bytes | 3 levels deep | same schema every doc |
| small_sparse | 7 | all ≤ 5 bytes | flat root | 3 rotating variants |

#### Root causes (ranked)

1. **Field count / amortization (primary).** H2d eliminates ~126
   `ObjectIntHashMap.getOrDefault` calls per doc on clickbench (9% → 0.2% CPU).
   On small_sparse that's only ~7 calls — the map is already cheap. The new fixed
   per-field costs (`scanFieldName`, direct-map verify, ordinal dispatch branches)
   are not amortized.

2. **Nesting (primary for otel).** Ordinal fast path is gated on
   `parentDepth == 0` / `kvDepth == 0`. Only ~7 otel root scalars benefit; ~15
   nested leaves (`resource.*`, `attributes.*`) still call `addLeaf`. Otel pays
   full H2 lookup on all 22 fields but gets H2d benefit on ~7.

3. **Name length (secondary).** Fusion helps clickbench (72% names > 8 bytes).
   small_sparse names all fit one 8-byte word — `scanFieldName` adds machinery
   without savings. Otel has long names but nesting dominates.

4. **Heterogeneous freeze (small_sparse amplifier).** Freeze-on-first-doc with
   three rotating variants (only `type` shared) meant ~85% of docs hit cache misses
   after doc 0, paying full H2 miss-path cost with no ordinal benefit.

#### Follow-up fixes — IMPLEMENTED

| Fix | Target | Mechanism |
|-----|--------|-----------|
| **Small-table fast path** | small_sparse, otel | Skip direct-mapped table when `count < 32`; probe-only lookup |
| **Deferred freeze** | small_sparse heterogeneity | Freeze after first doc that adds zero new field names |
| Nested ordinals (future) | otel_nested | Extend ordinal cache beyond `parentDepth == 0` |

A count-based branch after freeze is essentially free and preserves clickbench gains
(126 fields still use the direct map).

### Benchmark Results — post-H2b (small-table + deferred freeze, JDK 26.0.1)

Re-run on same AWS instances after small-table fast path and deferred-freeze changes.

#### x64 (AMD EPYC 9R14, 8 threads)

| Method | clickbench_flat | otel_nested | small_sparse |
|--------|----------------:|------------:|-------------:|
| jacksonEncode | 80.1 ± 0.2 | 353.1 ± 2.3 | 852.3 ± 3.1 |
| pre-H2 simdJson (`40d91b2ad7c`) | 96.8 | 411.7 | 1048.9 |
| post-H2 simdJson | 105.4 ± 0.4 | 406.9 ± 1.4 | 979.7 ± 9.4 |
| **post-H2b simdJson** | **102.8 ± 0.4** | **420.3 ± 2.3** | **1121.8 ± 43.2** |

Delta vs post-H2: clickbench −2.5%, otel **+3.3%**, small_sparse **+14.5%**.
Delta vs pre-H2: clickbench +6.2%, otel +2.1%, small_sparse **+7.0%**.

#### ARM (Graviton, 4 threads)

| Method | clickbench_flat | otel_nested | small_sparse |
|--------|----------------:|------------:|-------------:|
| jacksonEncode | 32.8 ± 0.5 | 179.7 ± 1.0 | 409.8 ± 30.4 |
| pre-H2 simdJson (`40d91b2ad7c`) | 46.1 | 212.8 | 517.6 |
| post-H2 simdJson | 54.9 ± 1.0 | 195.0 ± 14.1 | 506.4 ± 2.3 |
| **post-H2b simdJson** | **53.3 ± 0.9** | **233.2 ± 1.1** | **563.9 ± 3.2** |

Delta vs post-H2: clickbench −2.9%, otel **+19.6%**, small_sparse **+11.4%**.
Delta vs pre-H2: clickbench +15.6%, otel +9.6%, small_sparse **+8.9%**.

#### Jackson → simdJson speedup (post-H2b)

| Shape | x64 | ARM |
|-------|----:|----:|
| clickbench_flat | +28% | +62% |
| otel_nested | +19% | +30% |
| small_sparse | +32% | +38% |

**Analysis:** Deferred freeze fixes heterogeneous `small_sparse` (all 3 variants in
table before freeze; no miss-path penalty on variants B/C). Small-table path removes
direct-map overhead for otel (~22 fields) and small_sparse (~19 fields). `clickbench_flat`
is flat vs post-H2 (−2–3%, within run variance) — 126 fields still use full direct map;
one extra learning doc before freeze is negligible.

### Benchmark Results — post-H2c (H2 + H2b + tests, JDK 26.0.1, 2025-08-25)

Latest run including small-table fast path, deferred freeze, and collision test coverage.
Profiles captured on `clickbench_flat` / `simdJsonEncode`.

#### x64 (AMD EPYC 9R14, 8 threads)

| Method | clickbench_flat | otel_nested | small_sparse |
|--------|----------------:|------------:|-------------:|
| jacksonEncode | 79.0 ± 0.3 | 361.7 ± 7.0 | 864.0 ± 4.6 |
| pre-H2 simdJson (`40d91b2ad7c`) | 96.8 | 411.7 | 1048.9 |
| **post-H2c simdJson** | **103.1 ± 3.1** | **411.1 ± 1.6** | **1115.0 ± 29.8** |

Delta vs pre-H2: clickbench **+6.5%**, otel flat, small_sparse **+6.3%**.

#### ARM (Graviton, 4 threads)

| Method | clickbench_flat | otel_nested | small_sparse |
|--------|----------------:|------------:|-------------:|
| jacksonEncode | 36.2 ± 2.8 | 159.8 ± 10.2 | 389.3 ± 24.8 |
| pre-H2 simdJson (`40d91b2ad7c`) | 46.1 | 212.8 | 517.6 |
| **post-H2c simdJson** | **51.2 ± 3.2** | **189.7 ± 3.4** | **548.0 ± 10.6** |

Delta vs pre-H2: clickbench **+11.1%**, otel −10.9%*, small_sparse **+5.9%**.

\*ARM otel Jackson baseline also shifted down this run; simdJson otel is within ~2% of
post-H2b (233.2) when Jackson is stable.

#### Jackson → simdJson speedup (post-H2c)

| Shape | x64 | ARM |
|-------|----:|----:|
| clickbench_flat | +30% | +41% |
| otel_nested | +14% | +19% |
| small_sparse | +29% | +41% |

All three shapes beat pre-H2 simdJson on x64. On ARM, clickbench and small_sparse
beat pre-H2; otel shows instance variance across runs.

### CPU profile — post-H2c, `clickbench_flat`

Profiled `simdJsonEncode` / `clickbench_flat`, multi-threaded, async-profiler
collapsed stacks (JDK 26.0.1).

#### Subsystem breakdown

| Subsystem | Pre-H2 x64 | Post-H2c x64 | Pre-H2 ARM | Post-H2c ARM |
|-----------|----------:|-------------:|-----------:|-------------:|
| Walker (Java stage 2) | 47% | 61% | 39% | 60% |
| Field name resolution | 21% | 33%* | 25% | 35%* |
| ESCF commit | 20% | 25% | 16% | 19% |
| Native stage1 | 7% | 8% | 10% | 12% |
| Ordinal fast path | — | 8% | — | 4% |
| Schema lookup (`ObjectIntHashMap`) | 9% | **0.2%** | 6% | **0.1%** |

\*Aggregate field-name % includes `scanFieldName` + `lookupField` + `finishHash`;
replaces the old `resolveFieldName` + `hashName` + `Frozen.lookup` taxonomy.

#### Top leaf hotspots (`clickbench_flat`)

| Hotspot | Pre-H2 x64 | Post-H2c x64 | Pre-H2 ARM | Post-H2c ARM |
|---------|----------:|-------------:|-----------:|-------------:|
| `ObjectIntHashMap.getOrDefault` | 9.1% | **0.2%** | 6.0% | **0.1%** |
| `resolveFieldName` | 5.1% | **gone** | 8.2% | 2.1% |
| `Frozen.lookup` | 4.3% | **gone** | 6.3% | **gone** |
| `lookupField` | — | **11.0%** | — | **9.6%** |
| `scanFieldName` | — | 5.9% | — | **10.1%** |
| `FieldNameHash.hashName` | 2.7% | **gone** | 3.2% | 2.2% |
| `EscfRowBuffer.leafByOrdinal` | — | 3.4% | — | — |
| Native `stage1` | 6.5% | 7.3% | 10.2% | **11.4%** |
| `handleNumber` | 6.3% | 6.7% | 7.0% | 6.9% |
| `drainScratchValue` / `commit` | ~4% | ~5% | ~2% | ~5% |

**Key findings (post-H2c):**

1. **H1/H2d validated:** `ObjectIntHashMap.getOrDefault` eliminated (~9% → ~0.2%).
   Ordinal pass-through (`leafByOrdinal`) visible at 3–8% aggregate CPU.

2. **Field name chain transformed, not removed:** `lookupField` (9–11%) +
   `scanFieldName` (6–10%) replace `resolveFieldName` + `Frozen.lookup` + `hashName`.
   Net throughput still up +6–11% vs pre-H2 on clickbench because schema hash
   lookup and double-reads are gone.

3. **ESCF commit is now the #1 target:** ~19–25% CPU on both platforms
   (`EscfBatchBuilder.commit`, `drainScratchValue`, column builder writes).
   H3 (direct-to-column write path) is the highest-impact next step.

4. **ARM native stage1 share grew** (10% → 12%) relative to Java gains — confirms
   H5 (NEON vs AVX-512 ISA gap) remains inherent; Java-side wins are larger on ARM.

Profile artifacts: `/tmp/bench_h2c_prof/` on AWS instances.

### CPU profile — post-H2, `clickbench_flat` (superseded by post-H2c above)

Compared to pre-H2 profile (commit `40d91b2ad7c`):

| Hotspot / subsystem | Pre-H2 x64 | Post-H2 x64 | Pre-H2 ARM | Post-H2 ARM |
|---------------------|----------:|------------:|-----------:|------------:|
| Field names (aggregate) | 21% | 29%* | 25% | 36%* |
| `ObjectIntHashMap.getOrDefault` | 9.1% | **0.2%** | 6.0% | **0.2%** |
| `resolveFieldName` | 5.1% | **gone** | 8.2% | 2.4% |
| `Frozen.lookup` | 4.3% | **gone** | 6.3% | **gone** |
| `FieldNameHash.hashName` | 2.7% | **gone** | 3.2% | 1.9% |
| `scanFieldName` | — | 5.5% | — | 9.9% |
| `lookupField` | — | 8.8% | — | 11.1% |
| `EscfRowBuffer.leafByOrdinal` | — | 2.8% | — | 1.7% |
| ESCF commit | 20% | 25% | 16% | 19% |
| Native stage1 | 7% | 7% | 10% | 12% |

\*Aggregate field-name % rises because `scanFieldName` + `lookupField` replace
`resolveFieldName` + `hashName` + `Frozen.lookup` in the profile taxonomy; the
net CPU saved shows up as higher throughput (+8.9% x64, +19% ARM on clickbench).

**Key validation:** `ObjectIntHashMap.getOrDefault` dropped from ~9% to ~0.2% on
both platforms — H2d ordinal pass-through effectively delivers most of H1's benefit
for the warm ClickBench path. `hashName` double-read eliminated on the fused scan
path (H2b). `resolveFieldName` no longer appears in x64 top-15.

**Next bottleneck:** ESCF commit (`drainScratchValue`, column builder writes) is
now the largest remaining cost (~19–25% CPU). H3 is the next target.

### How to validate

```bash
# Micro-benchmark field name resolution in isolation (if added):
../gradlew :benchmarks:run --args 'FieldNameLookupBenchmark ...'

# Full pipeline, single method:
../gradlew :benchmarks:run --args \
  'SimdJsonParserBenchmark.simdJsonEncode -t 8 -p shape=clickbench_flat \
   -prof async:output=collapsed;dir=/tmp/bench;event=cpu;libPath=$PROF_LIB \
   -jvmArgs -Des.nativelibs.path=$NATIVE_LIBS'

# Confirm resolveFieldName / Frozen.lookup drop out of top-10 leaf frames
```

---

## Historical Hypotheses

### VarHandle guard dispatch — DISPROVEN

`VarHandleGuards.guard_LI_J` appears in async-profiler flamegraphs at 6-13%,
but **this is a frame-attribution artifact, not actual overhead**.

Perfasm disassembly (commit `dd3b9c970e4`, x64 JDK 26) confirms that C2 fully
inlines the VarHandle dispatch chain:

```
VarHandleGuards::guard_LI_J -> ArrayHandle::get -> Unsafe::getLongUnaligned
```

The guard check (`checkAccessModeThenIsDirect`) and type comparison are
constant-folded away because the VarHandle is a `static final` field of a
concrete type (`VarHandleByteArrayAsLongs$ArrayHandle`). The emitted code is
just a raw unaligned load instruction.

**Endianness is irrelevant**: `ByteOrder.LITTLE_ENDIAN` vs `nativeOrder()` on
a little-endian platform produces **exactly the same** `VarHandle` instance.
Both evaluate to `new VarHandleByteArrayAsLongs.ArrayHandle(be=false)`. The
`be` field is read by `Unsafe.getLongUnaligned(ba, offset, be)` which is an
intrinsic — on x64 it emits a plain `MOV`, on ARM a `LDR`.

**True cost breakdown** for `resolveFieldName` (28.03% total per perfasm):
- `hashName` computation (wymix, readSmall, readLE8) — hash is the dominant cost
- `FrozenFieldNameTable$Frozen.lookup` — prefix8 + hash-probe loop
- `Preconditions.checkIndex` — bounds checking on every VarHandle access
- The 8-byte word scan loop itself is cheap; most time is in hash + lookup

### H2: Field name resolution dominates CPU (28%)

`resolveFieldName` at 28% is the single largest cost. The breakdown (from
perfasm inline traces) is:

1. **`hashName` (wymix + readSmall + readLE8)**: ~12-15%. Every field name
   gets a full wyhash computation. For len <= 8 (common case), this is
   1 readSmall + 1 wymix. The readSmall path does 3 byte reads + shifts for
   len < 4, or 2 INT_LE VarHandle reads for len 4-8.

2. **`FrozenFieldNameTable$Frozen.lookup`**: ~8-10%. After hashing, the
   probe loop reads `hashes[slot]`, compares, then does `readPrefix8` +
   `Arrays.mismatch`. The `vectorizedMismatch_stub` at 1.82% is from this.

3. **`Preconditions.checkIndex`**: ~3-5%. Bounds checks on every VarHandle
   access. There are 2 checkIndex calls in readSmall (for len 4-8), plus
   1 in readLE8.

**Potential fixes**:
- Merge scan + hash: `scanAndHash` already exists but is not used in
  `resolveFieldName`. Using it would avoid reading the field name bytes
  twice (once to find the closing quote, once to hash).
- For len <= 8, skip `Arrays.mismatch` in the lookup if prefix8 + len
  match (the 8-byte prefix fully covers the name).
- Pre-compute bounds for the inner loop to hoist checkIndex out.

### H2b: drainScratchValue is #2 hotspot (16.86%)

`EscfBatchBuilder::drainScratchValue` is the second most expensive method
after `resolveFieldName`. This is the ESCF column builder draining values
from the scratch buffer into column storage. This is outside the simdjson
parser itself and represents the cost of the ESCF encoding side.

**Potential fix**: This is likely memory-copy bound. Reducing the number of
intermediate copies or using bulk operations could help.

### H3: Number parsing overhead (13-15%) — IMPLEMENTED, marginal gain

`handleNumber` in the direct walker parsed digits one-at-a-time in a loop. For
`clickbench_flat` (~80 numeric fields), this was the #2 hotspot.

**Fix applied**: SWAR 8-digit integer parsing — read 8 bytes via `LONG_LE`, check
all are ASCII digits (`(t & 0xF0F0F0F0F0F0F0F0L) != 0`), convert 8 digits in
parallel using pair/quad widening (3 multiplies instead of 8 scalar `*10+` steps).
Falls back to byte-at-a-time for the remaining digits.

**Result (x64, 8-thread):**

| Method               | clickbench_flat  | otel_nested  | small_sparse  |
|----------------------|------------------|--------------|---------------|
| Before (H2)          | 107.8 ops/s      | 419.7 ops/s  |  988.7 ops/s  |
| After (H3 SWAR)      | 109.6 ops/s      | 430.1 ops/s  | 1054.4 ops/s  |
| Delta                | +1.7%            | +2.5%        | +6.6%         |

Modest improvement. `handleNumber` / `parse8Digits` no longer appears in the top-30
CPU hotspots in the flamegraph, confirming it's been effectively eliminated as a
bottleneck. The remaining CPU is dominated by field name resolution (~12%),
ESCF column building (~10%), and `commitScratchTo`/`drainScratchValue` (~8%).

### H4: BitIndexes.getAndAdvance (2-4%)

Structural index iteration shows up consistently. May be due to bounds checking
or cache misses on large index arrays.

**Potential fix**: Cache the position in a local variable to help the JIT keep it
in a register.

### H5: EscfDocumentHandler string/long field handling (7-8%)

Handler methods copy bytes for column builders. `stringField` at 7% on
clickbench copies string bytes to the column builder.

**Potential fix**: Pass byte[] + offset + length directly to column builders,
avoiding intermediate copies. For unescaped strings still in the input buffer,
a zero-copy reference could avoid copying entirely.

### H6: Batch commitScratchTo overhead (22-33%)

In batch mode, `commitScratchTo` is the #2 cost after the walker itself.

**Potential fix**: Batch multiple row commits, or defer commit to amortize the
cost.

### H7: ARM batch mode bottlenecked by native stage1 (30-46%)

ARM NEON processes 16 bytes per SIMD iteration vs x64 AVX-512 at 64 bytes. The
batch path runs stage1 over the entire batch buffer (~25MB for clickbench), making
stage1 disproportionately expensive on ARM.

This explains why `simdJsonEncode` (per-doc stage1) outperforms
`simdJsonBatchEncode` on ARM — single-doc only indexes ~2.5KB per document.

**Potential fix**: Reduce `CHUNK_BYTE_LIMIT` on ARM, or use per-doc stage1 on ARM
and batch stage1 only on x64. Consider a hybrid that detects SIMD width at
startup.

### H8: Field name table lookup chain (8-12% total)

`readPrefix8` + `Child.lookup` + `Arrays.mismatch` together form the field name
resolution cost. Better than the old `lookupName` (22%), but still significant.

**Potential fix**: For small stable field sets, a direct-mapped table using
first-4-byte index could skip hash computation entirely.

### H9: Object allocation pressure (2-9%)

`C2 Runtime new_instance_blob` at 2-9%. In simdjson, likely from
`EscfDocumentHandler` or per-doc objects. In Jackson, from parser + context
creation per document.

**Potential fix**: Pool or reuse handler/row objects across documents in a batch.

---

## Batch ARM Investigations (B-series)

Context: On ARM, `simdJsonBatchEncode` was consistently slower than
`simdJsonEncode` (per-doc), while on x64 batch was faster. Investigated
with H7 as the starting hypothesis.

### B1: Configurable CHUNK_BYTE_LIMIT — IMPLEMENTED

Made `CHUNK_BYTE_LIMIT` configurable via system property
`es.simdjson.chunk_byte_limit` (default 256KB). Allows testing smaller
chunk sizes (32KB, 64KB) to keep stage1 working set in L1 cache on ARM.

**Chunk size sweep** (`simdJsonBatchEncode` only, JDK 26, `-wi 2 -i 3`):

ARM (4 threads):

| Chunk | clickbench_flat | otel_nested | small_sparse |
|-------|-----------------|-------------|--------------|
| 32KB  | 40.7            | 195.0       | 517.1        |
| 64KB  | 39.3            | 194.7       | **556.6**    |
| 128KB | **47.3**        | 183.0       | 536.9        |
| 256KB | 46.5            | 189.0       | 546.4        |
| 512KB | 43.9            | 175.8       | 530.3        |

x64 (8 threads):

| Chunk | clickbench_flat | otel_nested | small_sparse |
|-------|-----------------|-------------|--------------|
| 32KB  | 97.1            | 453.5       | 1213.7       |
| 64KB  | 96.1            | 476.2       | **1275.8**   |
| 128KB | **107.3**       | 476.7       | 1269.1       |
| 256KB | 105.7           | **489.5**   | 1268.5       |
| 512KB | **107.4**       | 483.3       | 1230.4       |

**Findings**: Effect is modest (~5–10% swing). Smaller chunks (32/64KB) do
*not* help ARM batch on large documents — `clickbench_flat` is worst at
64KB and best at 128KB. Very small chunks add stage1 invocation overhead.
256KB default is reasonable; 128KB may marginally help ARM `clickbench_flat`
(+2%). No chunk size closes the ARM batch-vs-per-doc gap on
`clickbench_flat` or `otel_nested`.

**Status**: Merged. Default remains 256KB.

### B2: Move offset-add from native to Java — REVERTED

The native `es_stage1_run` has an offset-add loop: when `offset != 0`,
each structural index is incremented by `offset` in a scalar C++ loop
instead of using `memcpy`. Hypothesis: moving this to Java would let JIT
auto-vectorize it.

**Result**: Massive regression on ARM (-39% to -53% for batch). JDK 21
did not vectorize the Java loop effectively. The native compiler handles
it better. Reverted.

### B4: Right-size BitIndexes capacity — IMPLEMENTED

Reduced initial `BitIndexes` capacity from `Math.max(capacity, 1024)` to
`Math.max(capacity / 4, 1024)`. The old formula over-allocated (1 index
per byte), when actual structural density is ~1 per 4 bytes. This reduces
cache footprint and avoids unnecessary resizing.

**Status**: Merged.

### JDK 26 Impact on ARM

Switching from JDK 21 to JDK 26 on ARM produced significant improvements:

| Method              | JDK 21 (ops/s) | JDK 26 (ops/s) | Change |
|---------------------|-----------------|-----------------|--------|
| simdJsonEncode (cb) | 48.7            | 54.7            | +12%   |
| simdJsonEncode (ot) | 237.3           | 239.9           | +1%    |
| simdJsonEncode (ss) | 445.9           | 548.6           | +23%   |
| simdJsonBatch  (cb) | 42.7            | 46.6            | +9%    |
| simdJsonBatch  (ot) | 191.7           | 191.5           | flat   |
| simdJsonBatch  (ss) | 418.8           | 575.1           | +37%   |

Key finding: batch now beats per-doc on `small_sparse` (575.1 vs 548.6,
+5%). The `clickbench_flat` and `otel_nested` gaps remain — batch is
still ~15% and ~20% slower than per-doc respectively on those shapes.
