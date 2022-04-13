/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

module org.elasticsearch.transport.netty4 {
    requires org.elasticsearch.base;
    requires org.elasticsearch.server;
    requires org.elasticsearch.logging;
    requires org.elasticsearch.xcontent;
    requires org.apache.lucene.core;
    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.transport;

    exports org.elasticsearch.http.netty4;
    exports org.elasticsearch.transport.netty4;
}
