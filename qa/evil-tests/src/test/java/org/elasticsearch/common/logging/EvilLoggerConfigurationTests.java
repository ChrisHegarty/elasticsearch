/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.logging;

import org.elasticsearch.test.ESTestCase;

public class EvilLoggerConfigurationTests extends ESTestCase {

    // @Override
    // public void setUp() throws Exception {
    // super.setUp();
    // BootstrapSupport.provider().registerErrorListener();
    // }
    //
    // @Override
    // public void tearDown() throws Exception {
    // LoggerContext context = (LoggerContext) LogManager.getContext(false);
    // Configurator.shutdown(context);
    // super.tearDown();
    // }
    //
    // public void testResolveMultipleConfigs() throws Exception {
    // final Level level = LogManager.getLogger("test").getLevel();
    // try {
    // final Path configDir = getDataPath("config");
    // final Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();
    // final Environment environment = new Environment(settings, configDir);
    // BootstrapSupport.provider().configure(environment);
    //
    // {
    // final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    // final Configuration config = ctx.getConfiguration();
    // final LoggerConfig loggerConfig = config.getLoggerConfig("test");
    // final Appender appender = loggerConfig.getAppenders().get("console");
    // assertThat(appender, notNullValue());
    // }
    //
    // {
    // final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    // final Configuration config = ctx.getConfiguration();
    // final LoggerConfig loggerConfig = config.getLoggerConfig("second");
    // final Appender appender = loggerConfig.getAppenders().get("console2");
    // assertThat(appender, notNullValue());
    // }
    //
    // {
    // final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    // final Configuration config = ctx.getConfiguration();
    // final LoggerConfig loggerConfig = config.getLoggerConfig("third");
    // final Appender appender = loggerConfig.getAppenders().get("console3");
    // assertThat(appender, notNullValue());
    // }
    // } finally {
    // Configurator.setLevel("test", level);
    // }
    // }
    //
    // public void testDefaults() throws IOException, UserException {
    // final Path configDir = getDataPath("config");
    // final String level = randomFrom(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).toString();
    // final Settings settings = Settings.builder()
    // .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
    // .put("logger.level", level)
    // .build();
    // final Environment environment = new Environment(settings, configDir);
    // BootstrapSupport.provider().configure(environment);
    //
    // final String loggerName = "test";
    // final Logger logger = LogManager.getLogger(loggerName);
    // assertThat(logger.getLevel().toString(), equalTo(level));
    // }
    //
    // // tests that custom settings are not overwritten by settings in the config file
    // public void testResolveOrder() throws Exception {
    // final Path configDir = getDataPath("config");
    // final Settings settings = Settings.builder()
    // .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
    // .put("logger.test_resolve_order", "TRACE")
    // .build();
    // final Environment environment = new Environment(settings, configDir);
    // BootstrapSupport.provider().configure(environment);
    //
    // // args should overwrite whatever is in the config
    // final String loggerName = "test_resolve_order";
    // final Logger logger = LogManager.getLogger(loggerName);
    // assertTrue(logger.isTraceEnabled());
    // }
    //
    // public void testHierarchy() throws Exception {
    // final Path configDir = getDataPath("hierarchy");
    // final Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();
    // final Environment environment = new Environment(settings, configDir);
    // BootstrapSupport.provider().configure(environment);
    //
    // assertThat(LogManager.getLogger("x").getLevel(), equalTo(Level.TRACE));
    // assertThat(LogManager.getLogger("x.y").getLevel(), equalTo(Level.DEBUG));
    //
    // final Level level = randomFrom(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    // LogLevelSupport.provider().setLevel(LogManager.getLogger("x"), level);
    //
    // assertThat(LogManager.getLogger("x").getLevel(), equalTo(level));
    // assertThat(LogManager.getLogger("x.y").getLevel(), equalTo(level));
    // }
    //
    // public void testMissingConfigFile() {
    // final Path configDir = getDataPath("does_not_exist");
    // final Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();
    // final Environment environment = new Environment(settings, configDir);
    // UserException e = expectThrows(UserException.class, () -> BootstrapSupport.provider().configure(environment));
    // assertThat(e, hasToString(containsString("no log4j2.properties found; tried")));
    // }
    //
    // public void testLoggingLevelsFromSettings() throws IOException, UserException {
    // final Level rootLevel = randomFrom(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    // final Level fooLevel = randomFrom(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    // final Level barLevel = randomFrom(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    // final Path configDir = getDataPath("minimal");
    // final Settings settings = Settings.builder()
    // .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
    // .put("logger.level", rootLevel.name())
    // .put("logger.foo", fooLevel.name())
    // .put("logger.bar", barLevel.name())
    // .build();
    // final Environment environment = new Environment(settings, configDir);
    // BootstrapSupport.provider().configure(environment);
    //
    // final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    // final Configuration config = ctx.getConfiguration();
    // final Map<String, LoggerConfig> loggerConfigs = config.getLoggers();
    // assertThat(loggerConfigs.size(), equalTo(3));
    // assertThat(loggerConfigs, hasKey(""));
    // assertThat(loggerConfigs.get("").getLevel(), equalTo(rootLevel));
    // assertThat(loggerConfigs, hasKey("foo"));
    // assertThat(loggerConfigs.get("foo").getLevel(), equalTo(fooLevel));
    // assertThat(loggerConfigs, hasKey("bar"));
    // assertThat(loggerConfigs.get("bar").getLevel(), equalTo(barLevel));
    //
    // assertThat(ctx.getLogger(randomAlphaOfLength(16)).getLevel(), equalTo(rootLevel));
    // }

}
