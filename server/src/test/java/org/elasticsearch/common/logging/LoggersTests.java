/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.logging;

import org.elasticsearch.logging.Level;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.logging.Message;
import org.elasticsearch.logging.internal.Loggers;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class LoggersTests extends ESTestCase {

    public void testParameterizedMessageLambda() throws Exception {
        // adding a random id to allow test to run multiple times. See AbstractConfiguration#addAppender
        final MockAppender appender = new MockAppender("trace_appender" + randomInt());
        // appender.start();
        final Logger testLogger = LogManager.getLogger(LoggersTests.class);
        // Loggers.addAppender(testLogger, appender); TODO PG
        Loggers.setLevel(testLogger, Level.TRACE);

        Throwable ex = randomException();
        testLogger.error(() -> Message.createParameterizedMessage("an error message"), ex);
        assertThat(appender.lastEvent.getLevel(), equalTo(Level.ERROR));
        assertThat(appender.lastEvent.getThrown(), equalTo(ex));
        assertThat(appender.lastParameterizedMessage().getFormattedMessage(), equalTo("an error message"));

        ex = randomException();
        testLogger.warn(() -> Message.createParameterizedMessage("a warn message: [{}]", "long gc"), ex);
        assertThat(appender.lastEvent.getLevel(), equalTo(Level.WARN));
        assertThat(appender.lastEvent.getThrown(), equalTo(ex));
        assertThat(appender.lastParameterizedMessage().getFormattedMessage(), equalTo("a warn message: [long gc]"));
        assertThat(appender.lastParameterizedMessage().getParameters(), arrayContaining("long gc"));

        testLogger.info(() -> Message.createParameterizedMessage("an info message a=[{}], b=[{}], c=[{}]", 1, 2, 3));
        assertThat(appender.lastEvent.getLevel(), equalTo(Level.INFO));
        assertThat(appender.lastEvent.getThrown(), nullValue());
        assertThat(appender.lastParameterizedMessage().getFormattedMessage(), equalTo("an info message a=[1], b=[2], c=[3]"));
        assertThat(appender.lastParameterizedMessage().getParameters(), arrayContaining(1, 2, 3));

        ex = randomException();
        testLogger.debug(() -> Message.createParameterizedMessage("a debug message options = {}", Arrays.asList("yes", "no")), ex);
        assertThat(appender.lastEvent.getLevel(), equalTo(Level.DEBUG));
        assertThat(appender.lastEvent.getThrown(), equalTo(ex));
        assertThat(appender.lastParameterizedMessage().getFormattedMessage(), equalTo("a debug message options = [yes, no]"));
        assertThat(appender.lastParameterizedMessage().getParameters(), arrayContaining(Arrays.asList("yes", "no")));

        ex = randomException();
        testLogger.trace(() -> Message.createParameterizedMessage("a trace message; element = [{}]", new Object[] { null }), ex);
        assertThat(appender.lastEvent.getLevel(), equalTo(Level.TRACE));
        assertThat(appender.lastEvent.getThrown(), equalTo(ex));
        assertThat(appender.lastParameterizedMessage().getFormattedMessage(), equalTo("a trace message; element = [null]"));
        assertThat(appender.lastParameterizedMessage().getParameters(), arrayContaining(new Object[] { null }));
    }

    private Throwable randomException() {
        return randomFrom(
            new IOException("file not found"),
            new UnknownHostException("unknown hostname"),
            new OutOfMemoryError("out of space"),
            new IllegalArgumentException("index must be between 10 and 100")
        );
    }
}
