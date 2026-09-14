/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

/*
 * Thin C-linkage wrapper around simdjson's stage 1 (structural indexing + UTF-8
 * validation). The caller allocates a context once per thread, runs stage 1 on
 * each buffer, then reads the resulting uint32_t structural index array.
 *
 * simdjson auto-selects the best SIMD backend (AVX-512, AVX2, SSE4.2, NEON)
 * at runtime via its implementation-selection machinery.
 *
 * Pinned to simdjson v4.6.9 (amalgamated single-header distribution).
 */

#include "simdjson.h"

#include <cstdint>
#include <cstdlib>
#include <memory>

using namespace simdjson;

struct simdjson_stage1_ctx {
    std::unique_ptr<internal::dom_parser_implementation> impl;
};

extern "C" {

/*
 * Allocates a reusable stage 1 context sized for buffers up to `capacity`
 * bytes. Returns nullptr on allocation failure.
 */
simdjson_stage1_ctx* simdjson_stage1_create(uint32_t capacity) {
    auto ctx = new (std::nothrow) simdjson_stage1_ctx();
    if (!ctx) return nullptr;

    auto err = get_active_implementation()->create_dom_parser_implementation(
        capacity, 64, ctx->impl);
    if (err) {
        delete ctx;
        return nullptr;
    }
    return ctx;
}

/*
 * Frees the context. Safe to call with nullptr.
 */
void simdjson_stage1_destroy(simdjson_stage1_ctx* ctx) {
    delete ctx;
}

/*
 * Runs stage 1 over buf[offset..offset+len) and writes structural indices into
 * out_buf. Adds `offset` to each index so outputs are absolute positions within
 * the original buffer. Stage 1 copies its remainder block to a stack-local
 * buffer, so no readable padding past offset+len is required.
 */
int simdjson_stage1_run(simdjson_stage1_ctx* ctx,
              const uint8_t* buf, uint32_t offset, uint32_t len,
              int32_t* out_buf, uint32_t out_buf_capacity,
              uint32_t* out_count) {
    if (!ctx || !ctx->impl) return -1;

    if (len > ctx->impl->capacity()) {
        auto err = ctx->impl->set_capacity(len);
        if (err) return static_cast<int>(err);
    }

    auto err = ctx->impl->stage1(buf + offset, len, stage1_mode::regular);
    if (err) return static_cast<int>(err);

    uint32_t n = ctx->impl->n_structural_indexes;
    if (n > out_buf_capacity) return -2;

    const uint32_t* src = ctx->impl->structural_indexes.get();
    if (offset == 0) {
        __builtin_memcpy(out_buf, src, n * sizeof(uint32_t));
    } else {
        for (uint32_t i = 0; i < n; i++) {
            out_buf[i] = static_cast<int32_t>(src[i] + offset);
        }
    }
    *out_count = n;
    return 0;
}

/*
 * Returns a human-readable error message for the given error code returned by
 * simdjson_stage1_run. Returns a static string — the caller must not free it.
 * Unknown codes yield "UNEXPECTED_ERROR".
 */
const char* simdjson_stage1_error_message(int err) {
    if (err == -1) return "null or invalid context";
    if (err == -2) return "output buffer too small";
    if (err >= 0 && err < static_cast<int>(error_code::NUM_ERROR_CODES)) {
        return error_message(static_cast<error_code>(err));
    }
    return "UNEXPECTED_ERROR";
}

/*
 * H11 spike (see SIMDJSON_MAP_EVAL.md): batch-parses JSON numbers at the
 * given offsets in `buf`, one native call for the *whole batch*, to test
 * whether native arithmetic beats the already-ported-to-Java Eisel-Lemire
 * fast path / SWAR digit parsing in SimdJsonDirectWalker/DoubleParser for
 * the *same* algorithm - not whether native has a smarter algorithm (it
 * doesn't; both sides run the fast-path-only case here).
 *
 * Deliberately fast-path-only, mirroring DoubleParser's own fast/slow split:
 * <=19 significant digits and a decimal exponent in [-22, 22] (fits exactly
 * via a single double multiply/divide by a power of ten - see
 * https://www.exploringbinary.com/fast-path-decimal-to-floating-point-conversion/).
 * Anything outside that range reports NEEDS_FALLBACK so the Java caller
 * re-parses just that one number itself; this is correct, not an
 * approximation, since production documents essentially never need it and
 * this is purely a benchmarking spike, not shipped.
 *
 * out_types[i]: 0 = INT64, 1 = DOUBLE, 2 = NEEDS_FALLBACK
 * out_bits[i]:  INT64 -> the raw int64 value; DOUBLE -> raw IEEE-754 bits
 * out_lens[i]:  number of bytes consumed by the number token at offsets[i]
 */
struct es_number_result {
    int8_t type;
    int64_t bits;
    int32_t len;
};

static es_number_result es_parse_one_number(const uint8_t* buf, uint32_t len, uint32_t idx) {
    if (idx >= len) return { 2, 0, 0 };
    bool negative = buf[idx] == '-';
    uint32_t pos = negative ? idx + 1 : idx;
    uint64_t digits = 0;
    int digitCount = 0;
    while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
        if (digitCount < 19) {
            digits = digits * 10 + (buf[pos] - '0');
        }
        digitCount++;
        pos++;
    }
    if (digitCount == 0 || digitCount > 19) return { 2, 0, 0 };

    bool isFloat = pos < len && (buf[pos] == '.' || buf[pos] == 'e' || buf[pos] == 'E');
    if (!isFloat) {
        int64_t val = negative ? -static_cast<int64_t>(digits) : static_cast<int64_t>(digits);
        return { 0, val, static_cast<int32_t>(pos - idx) };
    }

    int64_t exp10 = 0;
    if (pos < len && buf[pos] == '.') {
        pos++;
        uint32_t fracStart = pos;
        while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
            if (digitCount >= 19) return { 2, 0, 0 }; // too many significant digits for the fast path
            digits = digits * 10 + (buf[pos] - '0');
            digitCount++;
            pos++;
        }
        exp10 = static_cast<int64_t>(fracStart) - static_cast<int64_t>(pos);
    }
    if (pos < len && (buf[pos] == 'e' || buf[pos] == 'E')) {
        pos++;
        bool expNeg = false;
        if (pos < len && buf[pos] == '-') {
            expNeg = true;
            pos++;
        } else if (pos < len && buf[pos] == '+') {
            pos++;
        }
        int64_t exp = 0;
        while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
            exp = exp * 10 + (buf[pos] - '0');
            pos++;
        }
        exp10 += expNeg ? -exp : exp;
    }

    static const double POW10[] = { 1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,  1e8,  1e9,  1e10, 1e11,
                                     1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22 };
    if (digits > (1ULL << 53) - 1 || exp10 < -22 || exp10 > 22) {
        return { 2, 0, 0 };
    }
    double result = static_cast<double>(digits);
    result = (exp10 < 0) ? result / POW10[-exp10] : result * POW10[exp10];
    if (negative) result = -result;
    int64_t bits;
    __builtin_memcpy(&bits, &result, sizeof(bits));
    return { 1, bits, static_cast<int32_t>(pos - idx) };
}

int simdjson_parse_numbers_batch(const uint8_t* buf, uint32_t len,
              const int32_t* number_offsets, uint32_t count,
              int8_t* out_types, int64_t* out_bits, int32_t* out_lens) {
    if (!buf || !number_offsets || !out_types || !out_bits || !out_lens) return -1;
    for (uint32_t i = 0; i < count; i++) {
        int32_t idx = number_offsets[i];
        es_number_result r = (idx >= 0) ? es_parse_one_number(buf, len, static_cast<uint32_t>(idx)) : es_number_result{ 2, 0, 0 };
        out_types[i] = r.type;
        out_bits[i] = r.bits;
        out_lens[i] = r.len;
    }
    return 0;
}

} /* extern "C" */
