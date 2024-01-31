/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

const int STRIDE_BYTES_LEN = 32;

//int dot8s(int dims, long v1, long v2);
int dot8s(const void* a, const void* b, int dims);
