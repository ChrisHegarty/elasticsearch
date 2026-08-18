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
 * Pinned to simdjson v4.6.5 (amalgamated single-header distribution).
 */

#include "simdjson.h"

#include <cstdint>
#include <cstdlib>
#include <memory>

using namespace simdjson;

struct es_stage1_ctx {
    std::unique_ptr<internal::dom_parser_implementation> impl;
};

extern "C" {

/*
 * Allocates a reusable stage 1 context sized for buffers up to `capacity`
 * bytes. Returns nullptr on allocation failure.
 */
es_stage1_ctx* es_stage1_create(uint32_t capacity) {
    auto ctx = new (std::nothrow) es_stage1_ctx();
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
void es_stage1_destroy(es_stage1_ctx* ctx) {
    delete ctx;
}

/*
 * Runs stage 1 over buf[0..len). The buffer must have at least
 * SIMDJSON_PADDING (typically 64) bytes of readable space past `len`.
 *
 * On success returns 0 and sets *out_indexes to the internal structural_indexes
 * array and *out_count to the number of structural indices found. The pointers
 * remain valid until the next call to es_stage1_run on the same context.
 *
 * On failure (invalid UTF-8, capacity exceeded, etc.) returns a non-zero
 * simdjson error code.
 */
int es_stage1_run(es_stage1_ctx* ctx,
                  const uint8_t* buf, uint32_t len,
                  const uint32_t** out_indexes, uint32_t* out_count) {
    if (!ctx || !ctx->impl) return -1;

    if (len > ctx->impl->capacity()) {
        auto err = ctx->impl->set_capacity(len);
        if (err) return static_cast<int>(err);
    }

    auto err = ctx->impl->stage1(buf, len, stage1_mode::regular);
    if (err) return static_cast<int>(err);

    *out_indexes = ctx->impl->structural_indexes.get();
    *out_count = ctx->impl->n_structural_indexes;
    return 0;
}

/*
 * Runs stage 1 and copies the resulting structural indices into a caller-owned
 * int32_t buffer, avoiding the need for the Java side to dereference a native
 * pointer and perform a second copy.
 *
 * `out_buf` must have space for at least `out_buf_capacity` int32_t entries.
 * On success returns 0 and sets *out_count. If the result exceeds the output
 * capacity, returns -2 without writing.
 */
int es_stage1_run_into(es_stage1_ctx* ctx,
                       const uint8_t* buf, uint32_t len,
                       int32_t* out_buf, uint32_t out_buf_capacity,
                       uint32_t* out_count) {
    if (!ctx || !ctx->impl) return -1;

    if (len > ctx->impl->capacity()) {
        auto err = ctx->impl->set_capacity(len);
        if (err) return static_cast<int>(err);
    }

    auto err = ctx->impl->stage1(buf, len, stage1_mode::regular);
    if (err) return static_cast<int>(err);

    uint32_t n = ctx->impl->n_structural_indexes;
    if (n > out_buf_capacity) return -2;

    const uint32_t* src = ctx->impl->structural_indexes.get();
    __builtin_memcpy(out_buf, src, n * sizeof(uint32_t));
    *out_count = n;
    return 0;
}

/*
 * Returns the SIMDJSON_PADDING constant so the Java side knows how much
 * readable slack to provide past the document end.
 */
uint32_t es_stage1_padding(void) {
    return simdjson::SIMDJSON_PADDING;
}

} /* extern "C" */
