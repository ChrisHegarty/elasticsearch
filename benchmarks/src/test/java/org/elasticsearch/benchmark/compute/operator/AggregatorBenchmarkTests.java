/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.operator;

import org.elasticsearch.test.ESTestCase;

public class AggregatorBenchmarkTests extends ESTestCase {
    public void test() {
        AggregatorBenchmark.selfTest();
    }

    public void testFoo() {
        String grouping = "longs";
        String op = "sum";
        String blockType = "vector_longs";
        String filter = "none";
        int OP_COUNT = 1024;

        AggregatorBenchmark.run(grouping, op, blockType, filter, OP_COUNT);
    }
}
