/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.io.IOException;
import java.util.List;

public class Merge extends BinaryPlan {

    protected Merge(Source source, LogicalPlan left, LogicalPlan right) {
        super(source, left, right);
    }

    @Override
    public BinaryPlan replaceChildren(LogicalPlan left, LogicalPlan right) {
        return new Merge(source(), left, right);
    }

    @Override
    public String commandName() {
        return "MERGE";
    }

    @Override
    public boolean expressionsResolved() {
        return left().expressionsResolved();
    }

    @Override
    public List<Attribute> output() {
        return left().output(); // TODO: add the _fork attr
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Merge::new, left(), right());
    }

    @Override
    public String getWriteableName() {
        return "";
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {

    }
}
