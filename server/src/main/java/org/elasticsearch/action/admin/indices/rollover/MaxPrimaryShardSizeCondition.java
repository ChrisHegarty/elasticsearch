/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices.rollover;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

/**
 * A size-based condition for the primary shards within an index.
 * Evaluates to <code>true</code> if the size of the largest primary shard is at least {@link #value}.
 */
public class MaxPrimaryShardSizeCondition extends Condition<ByteSizeValue> {
    public static final String NAME = "max_primary_shard_size";

    public MaxPrimaryShardSizeCondition(ByteSizeValue value) {
        super(NAME, Type.MAX);
        this.value = value;
    }

    public MaxPrimaryShardSizeCondition(StreamInput in) throws IOException {
        super(NAME, Type.MAX);
        this.value = ByteSizeValue.ofBytes(in.readVLong());
    }

    @Override
    public Result evaluate(Stats stats) {
        return new Result(this, stats.maxPrimaryShardSize().getBytes() >= value.getBytes());
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // While we technically could serialize this with value.writeTo(...), that would
        // require doing the song and dance around backwards compatibility for this value. Since
        // in this case the deserialized version is not displayed to a user, it's okay to simply use
        // bytes.
        out.writeVLong(value.getBytes());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.field(NAME, value.getStringRep());
    }

    public static MaxPrimaryShardSizeCondition fromXContent(XContentParser parser) throws IOException {
        if (parser.nextToken() == XContentParser.Token.VALUE_STRING) {
            return new MaxPrimaryShardSizeCondition(ByteSizeValue.parseBytesSizeValue(parser.text(), NAME));
        } else {
            throw new IllegalArgumentException("invalid token when parsing " + NAME + " condition: " + parser.currentToken());
        }
    }
}
