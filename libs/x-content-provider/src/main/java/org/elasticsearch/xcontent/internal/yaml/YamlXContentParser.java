/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.xcontent.internal.yaml;

import com.fasterxml.jackson.core.JsonParser;

import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.internal.json.JsonXContentParser;

public class YamlXContentParser extends JsonXContentParser {

    public YamlXContentParser(XContentParserConfiguration config, JsonParser parser) {
        super(config, parser);
    }

    @Override
    public XContentType contentType() {
        return XContentType.YAML;
    }
}
