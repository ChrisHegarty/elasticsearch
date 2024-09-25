/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.elasticsearch.common.Strings;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.FloatVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.search.sort.SortAndFormats;
import org.elasticsearch.search.sort.SortBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@link LuceneTopNSourceOperator} that provides scores.
 */
public final class ScoringLuceneTopNSourceOperator extends LuceneTopNSourceOperator {

    public static class Factory extends LuceneTopNSourceOperator.Factory {

        public Factory(
            List<? extends ShardContext> contexts,
            Function<ShardContext, Query> queryFunction,
            DataPartitioning dataPartitioning,
            int taskConcurrency,
            int maxPageSize,
            int limit,
            List<SortBuilder<?>> sorts
        ) {
            super(contexts, queryFunction, dataPartitioning, taskConcurrency, maxPageSize, limit, sorts, ScoreMode.TOP_DOCS_WITH_SCORES);
        }

        @Override
        public SourceOperator get(DriverContext driverContext) {
            return new ScoringLuceneTopNSourceOperator(driverContext.blockFactory(), maxPageSize, sorts, limit, sliceQueue);
        }

        @Override
        public String describe() {
            String notPrettySorts = sorts.stream().map(Strings::toString).collect(Collectors.joining(","));
            return "ScoringLuceneTopNSourceOperator[dataPartitioning = "
                + dataPartitioning
                + ", maxPageSize = "
                + maxPageSize
                + ", limit = "
                + limit
                + ", sorts = ["
                + notPrettySorts
                + "]]";
        }
    }

    public ScoringLuceneTopNSourceOperator(
        BlockFactory blockFactory,
        int maxPageSize,
        List<SortBuilder<?>> sorts,
        int limit,
        LuceneSliceQueue sliceQueue
    ) {
        super(blockFactory, maxPageSize, sorts, limit, sliceQueue);
    }

    @Override
    protected FloatVector.Builder scoreVectorOrNull(int size) {
        return blockFactory.newFloatVectorFixedBuilder(size);
    }

    @Override
    protected void consumeScore(ScoreDoc scoreDoc, FloatVector.Builder currentScoresBuilder) {
        if (currentScoresBuilder != null) {
            float score = getScore(scoreDoc);
            currentScoresBuilder.appendFloat(score);
        }
    }

    @Override
    protected Page maybeAppendScore(Page page, FloatVector.Builder currentScoresBuilder) {
        return page.appendBlocks(new Block[] { currentScoresBuilder.build().asBlock() });
    }

    float getScore(ScoreDoc scoreDoc) {
        FieldDoc fieldDoc = (FieldDoc) scoreDoc;
        if (Float.isNaN(fieldDoc.score)) {
            return (float) fieldDoc.fields[0];
        } else {
            return fieldDoc.score;
        }
    }

    @Override
    PerShardCollector newPerShardCollector(ShardContext shardContext, List<SortBuilder<?>> sorts, int limit) throws IOException {
        Optional<SortAndFormats> sortAndFormats = shardContext.buildSort(sorts);
        Sort sort;
        if (sortAndFormats.isPresent()) {
            var l = new ArrayList<SortField>();
            l.add(SortField.FIELD_SCORE);
            l.addAll(Arrays.asList(sortAndFormats.get().sort.getSort()));
            sort = new Sort(l.toArray(SortField[]::new));
        } else {
            sort = Sort.RELEVANCE;
        }
        return new ScoringPerShardCollector(shardContext, sort, limit);
    }

    static class ScoringPerShardCollector extends PerShardCollector {
        ScoringPerShardCollector(ShardContext shardContext, Sort sort, int limit) {
            this.shardContext = shardContext;
            // TODO: numHits should be configurable
            this.topFieldCollector = new TopFieldCollectorManager(sort, 100, limit).newCollector();
        }
    }
}
