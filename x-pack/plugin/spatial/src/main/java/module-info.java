/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

module org.elasticsearch.spatial {
    requires org.elasticsearch.base;
    requires org.elasticsearch.geo;
    requires org.elasticsearch.h3;
    requires org.elasticsearch.painless.spi;
    requires org.elasticsearch.server;
    requires org.elasticsearch.xcontent;
    requires org.elasticsearch.xcore;
    requires org.apache.lucene.core;
    requires org.apache.lucene.spatial3d;
    requires org.elasticsearch.legacy.geo;

    exports org.elasticsearch.xpack.spatial;
    exports org.elasticsearch.xpack.spatial.action to org.elasticsearch.server;
    exports org.elasticsearch.xpack.spatial.index.fielddata; // for painless
    exports org.elasticsearch.xpack.spatial.index.fielddata.plain; // for painless
    exports org.elasticsearch.xpack.spatial.index.mapper; // for painless
    exports org.elasticsearch.xpack.spatial.search.aggregations.bucket.geogrid;

    opens org.elasticsearch.xpack.spatial to org.elasticsearch.painless.spi; // whitelist resource access

    provides org.elasticsearch.painless.spi.PainlessExtension with org.elasticsearch.xpack.spatial.SpatialPainlessExtension;
}
