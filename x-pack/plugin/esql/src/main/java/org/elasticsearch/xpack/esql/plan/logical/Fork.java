/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

// HEGO: remove based on Lookup
public class Fork extends UnaryPlan implements SurrogateLogicalPlan{
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(LogicalPlan.class, "Fork", Fork::new);

    private final LogicalPlan first;
    private final LogicalPlan second;
    private List<Attribute> lazyOutput;

    public Fork(
        Source source,
        LogicalPlan child,
        LogicalPlan first,
        LogicalPlan second
    ) {
        super(source, child);
        this.first = first;
        this.second = second;
//        this.tableName = tableName;
//        this.matchFields = matchFields;
//        this.localRelation = localRelation;
    }

    public Fork(StreamInput in) throws IOException {
        super(Source.readFrom((PlanStreamInput) in), in.readNamedWriteable(LogicalPlan.class));
        this.first = null;
        this.second = null;
//        this.tableName = in.readNamedWriteable(Expression.class);
//        this.matchFields = in.readNamedWriteableCollectionAsList(Attribute.class);
//        this.localRelation = in.readBoolean() ? new LocalRelation(in) : null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
//        out.writeNamedWriteable(child());
//        out.writeNamedWriteable(tableName);
//        out.writeNamedWriteableCollection(matchFields);
//        if (localRelation == null) {
//            out.writeBoolean(false);
//        } else {
//            out.writeBoolean(true);
//            localRelation.writeTo(out);
//        }
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    public LogicalPlan surrogate() {
        // left join between the main relation and the local, lookup relation
        var left = first.replaceChildrenSameSize(List.of(child()));
        var right = second.replaceChildrenSameSize(List.of(child()));
        return new Merge(source(), left, right);
    }

    public LogicalPlan first() {
        return first;
    }

    public LogicalPlan second() {
        return second;
    }

    @Override
    public UnaryPlan replaceChild(LogicalPlan newChild) {
        return new Fork(source(), newChild, first, second);
    }

    @Override
    public String commandName() {
        return "FORK";
    }

    @Override
    public boolean expressionsResolved() {
        return first.expressionsResolved() && second.expressionsResolved();
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Fork::new, child(), first, second);
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            lazyOutput = first.output(); // assumes first and second are identical
        }
        return lazyOutput;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (super.equals(o) == false) {
            return false;
        }
        Fork other = (Fork) o;
        return Objects.equals(first, other.first)
            && Objects.equals(second, other.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), first, second);
    }
}
