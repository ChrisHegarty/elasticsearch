/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

module org.elasticsearch.xcontent {
    // for constants in XContentFactory
    requires static com.fasterxml.jackson.dataformat.cbor;
    requires static com.fasterxml.jackson.dataformat.smile;

    requires transitive org.elasticsearch.core;

    exports org.elasticsearch.xcontent;
    exports org.elasticsearch.xcontent.cbor;
    exports org.elasticsearch.xcontent.json;
    exports org.elasticsearch.xcontent.smile;
    exports org.elasticsearch.xcontent.support;
    exports org.elasticsearch.xcontent.yaml;
    exports org.elasticsearch.xcontent.support.filtering to org.elasticsearch.xcontent.provider;
    exports org.elasticsearch.xcontent.spi to org.elasticsearch.xcontent.provider;

    uses org.elasticsearch.xcontent.ErrorOnUnknown;
    uses org.elasticsearch.xcontent.XContentBuilderExtension;
    uses org.elasticsearch.xcontent.spi.XContentProvider;
}
