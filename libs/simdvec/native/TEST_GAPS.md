# Native SIMD / FFI Test Gaps

Identified gaps in test coverage for the native vector library
(`libs/simdvec/native`), Panama FFI bindings (`libs/native`),
and Lucene integration (`libs/simdvec`).

Work through these one at a time. Mark each as DONE when
addressed.

---

## libs/native (FFI bindings → native code)

### Gap 1: Missing bulk bounds-check tests for INT8 and FLOAT32

INT7U, INT4, and BBQ all have `testBulkIllegalDims` that verify
IOOBE for bad count, negative dims, and undersized result
buffers. INT8 (`JDKVectorLibraryInt8Tests`) and FLOAT32
(`JDKVectorLibraryFloat32Tests`) do not have equivalent tests,
even though `checkBulk` and `checkBulkOffsets` exist for all
data types.

- [x] Add `testBulkIllegalDims` to `JDKVectorLibraryInt8Tests`
- [x] Add `testBulkIllegalDims` to `JDKVectorLibraryFloat32Tests`

### Gap 2: BULK_OFFSETS offset patterns are limited

All BULK_OFFSETS tests use random offsets into `[0, numVecs)`.
No tests cover:

- [x] Duplicate offsets (same vector scored twice in one call)
- [x] Out-of-range offsets — fixed `checkBulkOffsets` and
      `checkBBQBulkOffsets` to validate individual offset
      values; added `testBulkOffsetsOutOfRange`.
- [x] Identity offsets (`offsets[i] == i`, sequential case)

These could be added to any of the existing
`JDKVectorLibrary*Tests` classes.

### Gap 3: Pitch diversity is minimal

Every pitched BULK_OFFSETS test adds a single small padding
(`Float.BYTES` or `Integer.BYTES`). No coverage for:

- [ ] Large pitch (e.g. 2x or 4x the vector byte size)
- [ ] Pitch that isn't a multiple of element size (alignment)

### Gap 4: Large-segment (>2GB) coverage is narrow

`JDKVectorLibraryLargeSegmentTests` only covers INT8 (3
functions) and FLOAT32 (DOT only). Always `count=1`, tight
pitch, trivial offsets.

- [ ] Add INT7U large-segment BULK_OFFSETS test
- [ ] Add INT4 large-segment BULK_OFFSETS test
- [ ] Add pitched / multi-count large-segment variant

Low priority — these tests are slow and allocation-dependent.

### Gap 5: No `count=0` bulk test

No test calls a bulk operation with `count=0`. Behavior is
likely benign (no-op) but not verified.

- [ ] Add `count=0` bulk test to one representative type

### Gap 6: No tests for negative / invalid FFI arguments

No test verifies behavior for:

- [ ] Negative `pitch` or negative `count`
- [ ] `offsets` segment as `MemorySegment.NULL` for BULK_OFFSETS
- [ ] `dims` exceeding segment byte size but within `int` range

### Gap 7: FLOAT32 pitch byte-to-element conversion

The C++ `call_f32_bulk` now works in element counts (not bytes)
for pitch, with the Java→C++ call sites doing
`pitch / sizeof(f32_t)`. No test deliberately passes a pitch
that would expose an off-by-4x error if this conversion were
missing or wrong. A dedicated test with a non-trivial pitched
layout and explicit expected values would catch this.

- [ ] Add explicit FLOAT32 BULK_OFFSETS test with pitch that
      would give wrong results if pitch were not divided by 4

---

## libs/simdvec (Lucene integration)

### Gap 8: No bulk scoring test for ByteVectorScorerFactory

`ByteVectorScorerFactoryTests` tests `score()` per-ordinal
but never calls `bulkScore()`. All other scorer factory tests
(Float, Int7SQ, Int7uOSQ, Int4) include bulk scoring.

- [x] Add `testBulk` to `ByteVectorScorerFactoryTests`

### Gap 9: COSINE coverage is limited

- Native layer: COSINE only tested for INT8
- Simdvec layer: COSINE only tested for Int7SQ factory
- Byte vectors (INT7U) lack cosine in both layers

COSINE is not applicable for FLOAT32/INT4/BBQ by design, but
could be added for INT7U/byte if the native function exists.

- [ ] Evaluate whether INT7U COSINE native tests are needed
- [ ] Add COSINE to `ByteVectorScorerFactoryTests` if applicable

### Gap 10: Fixed-dimension SIMD boundary tests

Only Int7SQ (31/32/33), Int7uOSQ (31/32/33), and Int4
(30/32/34) use fixed dimensions at SIMD lane boundaries.
Byte and Float factory tests rely purely on
`randomIntBetween(1, 4096)`.

- [ ] Add fixed 31/32/33 dimension tests to
      `ByteVectorScorerFactoryTests`
- [ ] Add fixed 31/32/33 dimension tests to
      `FloatVectorScorerFactoryTests`

### Gap 11: Directory type variety

Most factory tests use MMapDirectory only. Int4 adds NIOFS.
Byte, Float, Int7SQ, Int7uOSQ do not exercise non-mmap
directory types.

- [ ] Evaluate whether NIOFS tests are worth adding for byte
      and float scorers (lower priority — MMap is the
      production path for SIMD scoring)

### Gap 12: Int7uOSQ `testBulk` similarity coverage

`testBulk` only asserts EUCLIDEAN, even though DOT_PRODUCT and
MAXIMUM_INNER_PRODUCT are supported. The separate
`testBulkWithDatasetGreaterThanChunkSize` covers all three.

- [ ] Extend `testBulk` in `Int7uOSQVectorScorerFactoryTests`
      to also cover DOT_PRODUCT and MAXIMUM_INNER_PRODUCT
