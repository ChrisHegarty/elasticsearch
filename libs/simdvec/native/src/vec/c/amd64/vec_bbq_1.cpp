/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

// This file contains implementations for BBQ vector operations,
// including support for "1st tier" vector capabilities; in the case of x64,
// this first tier include functions for processors supporting at least AVX2.

#include <stdint.h>
#include "vec.h"
#include "vec_common.h"
#include "amd64/amd64_vec_common.h"
#include "amd64/amd64_bbq_common.h"

// Mula popcount lookup table for AVX2
static const __m256i POPCOUNT_LOOKUP = _mm256_setr_epi8(
    /* 0 */ 0, /* 1 */ 1, /* 2 */ 1, /* 3 */ 2,
    /* 4 */ 1, /* 5 */ 2, /* 6 */ 2, /* 7 */ 3,
    /* 8 */ 1, /* 9 */ 2, /* a */ 2, /* b */ 3,
    /* c */ 2, /* d */ 3, /* e */ 3, /* f */ 4,
    /* 0 */ 0, /* 1 */ 1, /* 2 */ 1, /* 3 */ 2,
    /* 4 */ 1, /* 5 */ 2, /* 6 */ 2, /* 7 */ 3,
    /* 8 */ 1, /* 9 */ 2, /* a */ 2, /* b */ 3,
    /* c */ 2, /* d */ 3, /* e */ 3, /* f */ 4
);

// Fast AVX2 popcount, based on "Faster Population Counts Using AVX2 Instructions"
// See https://arxiv.org/abs/1611.07612 and https://github.com/WojciechMula/sse-popcount
static inline __m256i dot_bit_256(const __m256i a, const int8_t* b) {
    const __m256i lookup = _mm256_setr_epi8(
        /* 0 */ 0, /* 1 */ 1, /* 2 */ 1, /* 3 */ 2,
        /* 4 */ 1, /* 5 */ 2, /* 6 */ 2, /* 7 */ 3,
        /* 8 */ 1, /* 9 */ 2, /* a */ 2, /* b */ 3,
        /* c */ 2, /* d */ 3, /* e */ 3, /* f */ 4,

        /* 0 */ 0, /* 1 */ 1, /* 2 */ 1, /* 3 */ 2,
        /* 4 */ 1, /* 5 */ 2, /* 6 */ 2, /* 7 */ 3,
        /* 8 */ 1, /* 9 */ 2, /* a */ 2, /* b */ 3,
        /* c */ 2, /* d */ 3, /* e */ 3, /* f */ 4
    );

    const __m256i low_mask = _mm256_set1_epi8(0x0f);

    __m256i local = _mm256_setzero_si256();
    __m256i q0 = _mm256_loadu_si256((const __m256i_u*)b);
    __m256i vec = _mm256_and_si256(q0, a);

   const __m256i lo  = _mm256_and_si256(vec, low_mask);
   const __m256i hi  = _mm256_and_si256(_mm256_srli_epi16(vec, 4), low_mask);
   const __m256i popcnt1 = _mm256_shuffle_epi8(lookup, lo);
   const __m256i popcnt2 = _mm256_shuffle_epi8(lookup, hi);
   local = _mm256_add_epi8(local, popcnt1);
   local = _mm256_add_epi8(local, popcnt2);
   return local;
}

template<int query_bits>
static inline int64_t dotd1qN_inner(const int8_t* a, const int8_t* q, const int32_t length) {
    __m256i acc[query_bits];
    apply_indexed<query_bits>([&](auto I) {
        acc[I] = _mm256_setzero_si256();
    });

    int r = 0;
    int upperBound = length & ~(sizeof(__m256i) - 1);
    for (; r < upperBound; r += sizeof(__m256i)) {
        __m256i value = _mm256_loadu_si256((const __m256i_u*)(a + r));
        apply_indexed<query_bits>([&](auto I) {
            __m256i res = dot_bit_256(value, q + r + I * length);
            acc[I] = _mm256_add_epi64(acc[I], _mm256_sad_epu8(res, _mm256_setzero_si256()));
        });
    }

    int64_t bit_result[query_bits];
    apply_indexed<query_bits>([&](auto I) {
        bit_result[I] = mm256_reduce_epi64<_mm_add_epi64>(acc[I]);
    });

    // switch to 32-bit ops
    upperBound = length & ~(sizeof(int32_t) - 1);
    for (; r < upperBound; r += sizeof(int32_t)) {
        int32_t value = *((int32_t*)(a + r));
        apply_indexed<query_bits>([&](auto I) {
            int32_t bits = *((int32_t*)(q + r + I * length));
            bit_result[I] += __builtin_popcount(bits & value);
        });
    }
    // then single bytes
    for (; r < length; r++) {
        int8_t value = *(a + r);
        apply_indexed<query_bits>([&](auto I) {
            int32_t bits = *(q + r + I * length);
            bit_result[I] += __builtin_popcount(bits & value & 0xFF);
        });
    }
    int sum = 0;
    apply_indexed<query_bits>([&](auto I) {
        sum += (bit_result[I] << I);
    });
    return sum;
}

EXPORT int64_t vec_dotd1q4(
    const int8_t* a_ptr,
    const int8_t* query_ptr,
    const int32_t length
) {
    return dotd1qN_inner<4>(a_ptr, query_ptr, length);
}

EXPORT int64_t vec_dotd1q1(
    const int8_t* a_ptr,
    const int8_t* query_ptr,
    const int32_t length
) {
    return dotd1qN_inner<1>(a_ptr, query_ptr, length);
}

// Hand-inlined D1Q4 bulk: process 2 docs simultaneously for better ILP
template <typename TData, const int8_t*(*mapper)(const TData*, const int32_t, const int32_t*, const int32_t)>
static inline void dotd1q4_bulk_2doc(
    const TData* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results
) {
    const __m256i low_mask = _mm256_set1_epi8(0x0f);
    const __m256i zero = _mm256_setzero_si256();
    const int upperBound = length & ~(int)(sizeof(__m256i) - 1);

    int c = 0;
    for (; c + 1 < count; c += 2) {
        const int8_t* a0 = mapper(a, c, offsets, pitch);
        const int8_t* a1 = mapper(a, c + 1, offsets, pitch);

        // 8 accumulators: 2 docs × 4 query bits
        __m256i acc[8];
        for (int k = 0; k < 8; k++) acc[k] = zero;

        for (int r = 0; r < upperBound; r += (int)sizeof(__m256i)) {
            __m256i v0 = _mm256_loadu_si256((const __m256i_u*)(a0 + r));
            __m256i v1 = _mm256_loadu_si256((const __m256i_u*)(a1 + r));

            for (int qi = 0; qi < 4; qi++) {
                const int8_t* qp = query + r + qi * length;
                __m256i qv = _mm256_loadu_si256((const __m256i_u*)qp);

                // Doc 0
                __m256i and0 = _mm256_and_si256(v0, qv);
                __m256i lo0 = _mm256_and_si256(and0, low_mask);
                __m256i hi0 = _mm256_and_si256(_mm256_srli_epi16(and0, 4), low_mask);
                __m256i pc0 = _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo0),
                                               _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi0));
                acc[qi] = _mm256_add_epi64(acc[qi], _mm256_sad_epu8(pc0, zero));

                // Doc 1
                __m256i and1 = _mm256_and_si256(v1, qv);
                __m256i lo1 = _mm256_and_si256(and1, low_mask);
                __m256i hi1 = _mm256_and_si256(_mm256_srli_epi16(and1, 4), low_mask);
                __m256i pc1 = _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo1),
                                               _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi1));
                acc[4 + qi] = _mm256_add_epi64(acc[4 + qi], _mm256_sad_epu8(pc1, zero));
            }
        }

        // Reduce and compute weighted sums
        int64_t r0[4], r1[4];
        for (int qi = 0; qi < 4; qi++) {
            r0[qi] = mm256_reduce_epi64<_mm_add_epi64>(acc[qi]);
            r1[qi] = mm256_reduce_epi64<_mm_add_epi64>(acc[4 + qi]);
        }

        // Handle tail bytes
        for (int r = upperBound; r < length; r++) {
            int8_t va0 = *(a0 + r);
            int8_t va1 = *(a1 + r);
            for (int qi = 0; qi < 4; qi++) {
                int8_t qb = *(query + r + qi * length);
                r0[qi] += __builtin_popcount((uint8_t)(va0 & qb));
                r1[qi] += __builtin_popcount((uint8_t)(va1 & qb));
            }
        }

        results[c]     = (f32_t)(r0[0] + (r0[1] << 1) + (r0[2] << 2) + (r0[3] << 3));
        results[c + 1] = (f32_t)(r1[0] + (r1[1] << 1) + (r1[2] << 2) + (r1[3] << 3));
    }

    // Tail: remaining single doc
    for (; c < count; c++) {
        const int8_t* a0 = mapper(a, c, offsets, pitch);
        results[c] = (f32_t)dotd1qN_inner<4>(a0, query, length);
    }
}

// Hand-inlined D1Q4 bulk: process 4 docs simultaneously for maximum ILP
template <typename TData, const int8_t*(*mapper)(const TData*, const int32_t, const int32_t*, const int32_t)>
static inline void dotd1q4_bulk_4doc(
    const TData* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results
) {
    const __m256i low_mask = _mm256_set1_epi8(0x0f);
    const __m256i zero = _mm256_setzero_si256();
    const int upperBound = length & ~(int)(sizeof(__m256i) - 1);

    int c = 0;
    for (; c + 3 < count; c += 4) {
        const int8_t* a0 = mapper(a, c + 0, offsets, pitch);
        const int8_t* a1 = mapper(a, c + 1, offsets, pitch);
        const int8_t* a2 = mapper(a, c + 2, offsets, pitch);
        const int8_t* a3 = mapper(a, c + 3, offsets, pitch);

        // 4 accumulators — one per doc, pre-weighted by query bit position
        __m256i wacc0 = zero, wacc1 = zero, wacc2 = zero, wacc3 = zero;

        for (int r = 0; r < upperBound; r += (int)sizeof(__m256i)) {
            __m256i v0 = _mm256_loadu_si256((const __m256i_u*)(a0 + r));
            __m256i v1 = _mm256_loadu_si256((const __m256i_u*)(a1 + r));
            __m256i v2 = _mm256_loadu_si256((const __m256i_u*)(a2 + r));
            __m256i v3 = _mm256_loadu_si256((const __m256i_u*)(a3 + r));

            for (int qi = 0; qi < 4; qi++) {
                __m256i qv = _mm256_loadu_si256((const __m256i_u*)(query + r + qi * length));

                __m256i and0 = _mm256_and_si256(v0, qv);
                __m256i lo0 = _mm256_and_si256(and0, low_mask);
                __m256i hi0 = _mm256_and_si256(_mm256_srli_epi16(and0, 4), low_mask);
                __m256i sad0 = _mm256_sad_epu8(
                    _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo0),
                                     _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi0)), zero);
                wacc0 = _mm256_add_epi64(wacc0, _mm256_slli_epi64(sad0, qi));

                __m256i and1 = _mm256_and_si256(v1, qv);
                __m256i lo1 = _mm256_and_si256(and1, low_mask);
                __m256i hi1 = _mm256_and_si256(_mm256_srli_epi16(and1, 4), low_mask);
                __m256i sad1 = _mm256_sad_epu8(
                    _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo1),
                                     _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi1)), zero);
                wacc1 = _mm256_add_epi64(wacc1, _mm256_slli_epi64(sad1, qi));

                __m256i and2 = _mm256_and_si256(v2, qv);
                __m256i lo2 = _mm256_and_si256(and2, low_mask);
                __m256i hi2 = _mm256_and_si256(_mm256_srli_epi16(and2, 4), low_mask);
                __m256i sad2 = _mm256_sad_epu8(
                    _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo2),
                                     _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi2)), zero);
                wacc2 = _mm256_add_epi64(wacc2, _mm256_slli_epi64(sad2, qi));

                __m256i and3 = _mm256_and_si256(v3, qv);
                __m256i lo3 = _mm256_and_si256(and3, low_mask);
                __m256i hi3 = _mm256_and_si256(_mm256_srli_epi16(and3, 4), low_mask);
                __m256i sad3 = _mm256_sad_epu8(
                    _mm256_add_epi8(_mm256_shuffle_epi8(POPCOUNT_LOOKUP, lo3),
                                     _mm256_shuffle_epi8(POPCOUNT_LOOKUP, hi3)), zero);
                wacc3 = _mm256_add_epi64(wacc3, _mm256_slli_epi64(sad3, qi));
            }
        }

        int64_t sum0 = mm256_reduce_epi64<_mm_add_epi64>(wacc0);
        int64_t sum1 = mm256_reduce_epi64<_mm_add_epi64>(wacc1);
        int64_t sum2 = mm256_reduce_epi64<_mm_add_epi64>(wacc2);
        int64_t sum3 = mm256_reduce_epi64<_mm_add_epi64>(wacc3);

        // Handle tail bytes
        for (int r = upperBound; r < length; r++) {
            int8_t va0 = *(a0 + r), va1 = *(a1 + r), va2 = *(a2 + r), va3 = *(a3 + r);
            for (int qi = 0; qi < 4; qi++) {
                int8_t qb = *(query + r + qi * length);
                sum0 += (int64_t)__builtin_popcount((uint8_t)(va0 & qb)) << qi;
                sum1 += (int64_t)__builtin_popcount((uint8_t)(va1 & qb)) << qi;
                sum2 += (int64_t)__builtin_popcount((uint8_t)(va2 & qb)) << qi;
                sum3 += (int64_t)__builtin_popcount((uint8_t)(va3 & qb)) << qi;
            }
        }

        results[c]     = (f32_t)sum0;
        results[c + 1] = (f32_t)sum1;
        results[c + 2] = (f32_t)sum2;
        results[c + 3] = (f32_t)sum3;
    }

    // Tail: remaining docs (fewer than 4)
    for (; c < count; c++) {
        const int8_t* a0 = mapper(a, c, offsets, pitch);
        results[c] = (f32_t)dotd1qN_inner<4>(a0, query, length);
    }
}

EXPORT void vec_dotd1q4_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    if (length >= 64) {
        dotd1q4_bulk_4doc<int8_t, sequential_mapper>(a, query, length, length, NULL, count, results);
    } else {
        dotd1q_inner_bulk<int8_t, sequential_mapper, dotd1qN_inner<4>>(a, query, length, length, NULL, count, results);
    }
}

EXPORT void vec_dotd1q4_bulk_offsets(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results) {
    if (length >= 64) {
        dotd1q4_bulk_4doc<int8_t, offsets_mapper>(a, query, length, pitch, offsets, count, results);
    } else {
        dotd1q_inner_bulk<int8_t, offsets_mapper, dotd1qN_inner<4>>(a, query, length, pitch, offsets, count, results);
    }
}

EXPORT void vec_dotd1q4_bulk_sparse(
    const void* const* addresses,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    if (length >= 64) {
        dotd1q4_bulk_4doc<const int8_t*, sparse_mapper>((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
    } else {
        dotd1q_inner_bulk<const int8_t*, sparse_mapper, dotd1qN_inner<4>>
            ((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
    }
}

EXPORT void vec_dotd1q1_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd1q_inner_bulk<int8_t, sequential_mapper, dotd1qN_inner<1>>(a, query, length, length, NULL, count, results);
}

EXPORT void vec_dotd1q1_bulk_offsets(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results) {
    dotd1q_inner_bulk<int8_t, offsets_mapper, dotd1qN_inner<1>>(a, query, length, pitch, offsets, count, results);
}

EXPORT void vec_dotd1q1_bulk_sparse(
    const void* const* addresses,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd1q_inner_bulk<const int8_t*, sparse_mapper, dotd1qN_inner<1>>
        ((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
}

EXPORT int64_t vec_dotd2q2(
    const int8_t* a_ptr,
    const int8_t* query_ptr,
    const int32_t length
) {
    int64_t lower = dotd1qN_inner<2>(a_ptr, query_ptr, length/2);
    int64_t upper = dotd1qN_inner<2>(a_ptr + length/2, query_ptr, length/2);
    return lower + (upper << 1);
}

EXPORT void vec_dotd2q2_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<int8_t, sequential_mapper, dotd1qN_inner<2>>(a, query, length, length, NULL, count, results);
}

EXPORT void vec_dotd2q2_bulk_offsets(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<int8_t, offsets_mapper, dotd1qN_inner<2>>(a, query, length, pitch, offsets, count, results);
}

EXPORT void vec_dotd2q2_bulk_sparse(
    const void* const* addresses,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<const int8_t*, sparse_mapper, dotd1qN_inner<2>>
        ((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
}

EXPORT int64_t vec_dotd2q4(
    const int8_t* a_ptr,
    const int8_t* query_ptr,
    const int32_t length
) {
    int64_t lower = dotd1qN_inner<4>(a_ptr, query_ptr, length/2);
    int64_t upper = dotd1qN_inner<4>(a_ptr + length/2, query_ptr, length/2);
    return lower + (upper << 1);
}

EXPORT void vec_dotd2q4_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<int8_t, sequential_mapper, dotd1qN_inner<4>>(a, query, length, length, NULL, count, results);
}

EXPORT void vec_dotd2q4_bulk_offsets(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<int8_t, offsets_mapper, dotd1qN_inner<4>>(a, query, length, pitch, offsets, count, results);
}

EXPORT void vec_dotd2q4_bulk_sparse(
    const void* const* addresses,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results) {
    dotd2q_inner_bulk<const int8_t*, sparse_mapper, dotd1qN_inner<4>>
        ((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
}

EXPORT int64_t vec_dotd4q4(const int8_t* a, const int8_t* query, const int32_t length) {
    const int32_t bit_length = length / 4;
    int64_t p0 = dotd1qN_inner<4>(a + 0 * bit_length, query, bit_length);
    int64_t p1 = dotd1qN_inner<4>(a + 1 * bit_length, query, bit_length);
    int64_t p2 = dotd1qN_inner<4>(a + 2 * bit_length, query, bit_length);
    int64_t p3 = dotd1qN_inner<4>(a + 3 * bit_length, query, bit_length);
    return p0 + (p1 << 1) + (p2 << 2) + (p3 << 3);
}

EXPORT void vec_dotd4q4_bulk(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results
) {
    dotd4q4_inner_bulk<int8_t, sequential_mapper, dotd1qN_inner<4>>(a, query, length, length, NULL, count, results);
}

EXPORT void vec_dotd4q4_bulk_offsets(
    const int8_t* a,
    const int8_t* query,
    const int32_t length,
    const int32_t pitch,
    const int32_t* offsets,
    const int32_t count,
    f32_t* results
) {
    dotd4q4_inner_bulk<int8_t, offsets_mapper, dotd1qN_inner<4>>(a, query, length, pitch, offsets, count, results);
}

EXPORT void vec_dotd4q4_bulk_sparse(
    const void* const* addresses,
    const int8_t* query,
    const int32_t length,
    const int32_t count,
    f32_t* results
) {
    dotd4q4_inner_bulk<const int8_t*, sparse_mapper, dotd1qN_inner<4>>
        ((const int8_t* const*)addresses, query, length, 0, NULL, count, results);
}
