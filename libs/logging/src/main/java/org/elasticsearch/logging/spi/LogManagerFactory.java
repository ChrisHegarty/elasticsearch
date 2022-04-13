/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.logging.spi;

import org.elasticsearch.logging.Logger;

/**
 * SPI for creating new loggers
 */
public interface LogManagerFactory {
    static LogManagerFactory provider() {
        return LoggingSupportProvider.provider().logManagerFactory();
    }

    Logger getLogger(String name);

    Logger getLogger(Class<?> clazz);

    Logger getPrefixLogger(String loggerName, String prefix);

    Logger getPrefixLogger(Class<?> clazz, String prefix);
}
