/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.logging;

import org.elasticsearch.test.ESTestCase;

public class DeprecationLoggerTests extends ESTestCase {
    /*
    public void testMultipleSlowLoggersUseSingleLog4jLogger() {
        org.apache.logging.log4j.core.LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);

        DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(DeprecationLoggerTests.class);
        int numberOfLoggersBefore = context.getLoggers().size();

        class LoggerTest {}
        DeprecationLogger deprecationLogger2 = DeprecationLogger.getLogger(LoggerTest.class);

        context = (LoggerContext) LogManager.getContext(false);
        int numberOfLoggersAfter = context.getLoggers().size();

        assertThat(numberOfLoggersAfter, equalTo(numberOfLoggersBefore + 1));
    }

    public void testLogPermissions() {
        final LoggerContextFactory originalFactory = LogManager.getFactory();
        try {
            AtomicBoolean supplierCalled = new AtomicBoolean(false);
            // mocking the logger used inside DeprecationLogger requires heavy hacking...

            Logger mockLogger = mock(Logger.class);
            doAnswer(invocationOnMock -> {
                supplierCalled.set(true);
                createTempDir(); // trigger file permission, like rolling logs would
                return null;
            }).when(mockLogger).log(eq(Level.WARN), any(ESLogMessage.class));

            final LoggerContext context = Mockito.mock(LoggerContext.class);
            when(context.getLogger(anyString())).thenReturn(mockLogger);

            // "extending" the existing factory to avoid creating new one which
            // would result in LoaderUtil.getParent SM permission exception
            final LoggerContextFactory spy = Mockito.spy(originalFactory);
            Mockito.doReturn(context).when(spy).getContext(any(), any(), any(), anyBoolean(), any(), any());
            LogManager.setFactory(spy);

            DeprecationLogger deprecationLogger = DeprecationLogger.getLogger("name");

            AccessControlContext noPermissionsAcc = new AccessControlContext(
                new ProtectionDomain[] { new ProtectionDomain(null, new Permissions()) }
            );
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                deprecationLogger.warn(DeprecationCategory.API, "key", "foo", "bar");
                return null;
            }, noPermissionsAcc);
            assertThat("supplier called", supplierCalled.get(), is(true));
        } finally {
            LogManager.setFactory(originalFactory);
        }
    }*/
}
