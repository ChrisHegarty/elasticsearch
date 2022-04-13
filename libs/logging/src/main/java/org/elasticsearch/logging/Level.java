/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.logging;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Level {

    public static final Level OFF = new Level("OFF", StandardLevels.OFF);
    public static final Level FATAL = new Level("FATAL", StandardLevels.FATAL);
    public static final Level ERROR = new Level("ERROR", StandardLevels.ERROR);
    public static final Level WARN = new Level("WARN", StandardLevels.WARN);
    public static final Level INFO = new Level("INFO", StandardLevels.INFO);
    public static final Level DEBUG = new Level("DEBUG", StandardLevels.DEBUG);
    public static final Level TRACE = new Level("TRACE", StandardLevels.TRACE);
    public static final Level ALL = new Level("ALL", StandardLevels.ALL);

    private static final ConcurrentMap<String, Level> LEVELS = new ConcurrentHashMap<>();

    static {
        LEVELS.put(OFF.name, OFF);
        LEVELS.put(FATAL.name, FATAL);
        LEVELS.put(ERROR.name, ERROR);
        LEVELS.put(WARN.name, WARN);
        LEVELS.put(INFO.name, INFO);
        LEVELS.put(DEBUG.name, DEBUG);
        LEVELS.put(TRACE.name, TRACE);
        LEVELS.put(ALL.name, ALL);
    }
    private final String name;

    private final int severity;

    // TODO PG make sure we don't create too many levels..
    /*package*/ public static Level of(String name, int severity) {
        var level = new Level(name, severity);
        if (LEVELS.putIfAbsent(name, level) != null) {
            // throw new IllegalStateException("Level " + name + " is already been defined.");
        }
        return level;
    }

    private Level(String name, int severity) {
        this.name = name;
        this.severity = severity;
    }

    public static Collection<Level> values() {
        return LEVELS.values();
    }

    @Override
    public String toString() {
        return this.name;
    }

    /**
     * Returns the name of this level.
     */
    public String name() {
        return name;
    }

    public int getSeverity() {
        return severity;
    }

    public static Level valueOf(final String name) {
        Objects.requireNonNull(name);
        final String levelName = name.trim().toUpperCase(Locale.ROOT);
        final Level level = LEVELS.get(levelName);
        if (level != null) {
            return level;
        }
        throw new IllegalArgumentException("Unknown level constant [" + levelName + "].");
    }

    public boolean isMoreSpecificThan(Level level) {
        return this.severity <= level.severity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Level level = (Level) o;
        return severity == level.severity && Objects.equals(name, level.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, severity);
    }
}
