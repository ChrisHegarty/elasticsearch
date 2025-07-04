/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.Randomness;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.compute.operator.TupleLongLongBlockSourceOperator;
import org.elasticsearch.core.Tuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.elasticsearch.compute.aggregation.MedianAbsoluteDeviationDoubleGroupingAggregatorFunctionTests.medianAbsoluteDeviation;
import static org.hamcrest.Matchers.equalTo;

public class MedianAbsoluteDeviationLongGroupingAggregatorFunctionTests extends GroupingAggregatorFunctionTestCase {

    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int end) {
        long[][] samples = new long[][] {
            { 12, 125, 20, 20, 43, 60, 90 },
            { 1, 15, 20, 30, 40, 75, 1000 },
            { 2, 175, 20, 25 },
            { 5, 30, 30, 30, 43 },
            { 7, 15, 30 } };
        List<Tuple<Long, Long>> values = new ArrayList<>();
        for (int i = 0; i < samples.length; i++) {
            List<Long> list = Arrays.stream(samples[i]).boxed().collect(Collectors.toList());
            Randomness.shuffle(list);
            for (long v : list) {
                values.add(Tuple.tuple((long) i, v));
            }
        }
        return new TupleLongLongBlockSourceOperator(blockFactory, values.subList(0, Math.min(values.size(), end)));
    }

    @Override
    protected AggregatorFunctionSupplier aggregatorFunction() {
        return new MedianAbsoluteDeviationLongAggregatorFunctionSupplier();
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "median_absolute_deviation of longs";
    }

    @Override
    protected void assertSimpleGroup(List<Page> input, Block result, int position, Long group) {
        assertThat(
            ((DoubleBlock) result).getDouble(position),
            equalTo(medianAbsoluteDeviation(input.stream().flatMapToLong(p -> allLongs(p, group)).asDoubleStream()))
        );
    }
}
