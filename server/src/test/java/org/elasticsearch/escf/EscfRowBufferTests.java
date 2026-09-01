/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.escf;

import org.elasticsearch.sourcebatch.SourceSchema;
import org.elasticsearch.test.ESTestCase;

/**
 * Tests for {@link EscfRowBuffer} staging behavior, including frozen field ordinal caching.
 * Frozen ordinals in each test are arbitrary positive integers; only parent context matters.
 */
public class EscfRowBufferTests extends ESTestCase {

    /**
     * Layout (same segment name, different parents — segment ordinal is shared):
     * <pre>
     *   {"a":{"x":1},  "x":2 }
     *   {"a":{"x":10}, "x":20}
     * </pre>
     * Name-table ordinals are per segment ({@code "x"}), but ESCF columns are keyed by
     * {@code (parentNonLeafIdx, segmentName)}. Row two must reuse the column indices learned
     * on row one without collapsing {@code a.x} and root {@code x}.
     */
    public void testNestedOrdinalCacheUsesParentContext() {
        SourceSchema schema = new SourceSchema();
        EscfRowBuffer row = new EscfRowBuffer(schema);

        final int ordX = 5;

        // row 1: establish columns
        row.beginRow();
        row.startObject("a");
        final int nestedCol = row.longField(ordX, "x", 1);
        row.endObject();
        final int rootCol = row.longField(ordX, "x", 2);
        row.finishRow();

        assertEquals("same segment ordinal must map to two distinct columns", 2, schema.leafCount());
        assertNotEquals("a.x and root x must not share a column", nestedCol, rootCol);
        int parentA = schema.findNonLeaf("a", 0);
        assertEquals("a.x column parent must be non-leaf a", parentA, schema.getLeafParent(nestedCol));
        assertEquals("root x column parent must be root", 0, schema.getLeafParent(rootCol));

        // row 2: warm cache must reuse column indices
        row.beginRow();
        row.startObject("a");
        assertReusesColumn("a.x", nestedCol, row.longField(ordX, "x", 10));
        row.endObject();
        assertReusesColumn("root x", rootCol, row.longField(ordX, "x", 20));
        row.finishRow();

        // row 2 values staged in reused columns
        assertEquals("a.x value on row 2", 10, row.scratchNumeric(nestedCol));
        assertEquals("root x value on row 2", 20, row.scratchNumeric(rootCol));
    }

    /**
     * Layout (flat root fields, warm ordinal cache across rows):
     * <pre>
     *   {"n":1,  "m":2 }
     *   {"n":10, "m":20}
     * </pre>
     * After the first row establishes schema leaves, the second row should map the same
     * frozen ordinals to the same column indices without growing {@link SourceSchema}.
     */
    public void testRootOrdinalCacheReusesColumnAcrossRows() {
        SourceSchema schema = new SourceSchema();
        EscfRowBuffer row = new EscfRowBuffer(schema);

        final int ordN = 1;
        final int ordM = 2;

        // row 1: establish columns
        row.beginRow();
        final int colN = row.longField(ordN, "n", 1);
        final int colM = row.longField(ordM, "m", 2);
        row.finishRow();
        assertEquals(2, schema.leafCount());

        // row 2: warm cache must reuse column indices
        row.beginRow();
        assertReusesColumn("n", colN, row.longField(ordN, "n", 10));
        assertReusesColumn("m", colM, row.longField(ordM, "m", 20));
        row.finishRow();

        assertEquals("warm ordinal cache must not add schema leaves on row 2", 2, schema.leafCount());

        // row 2 values staged in reused columns
        assertEquals("n value on row 2", 10, row.scratchNumeric(colN));
        assertEquals("m value on row 2", 20, row.scratchNumeric(colM));
    }

    /**
     * Layout (two-level nesting — otel-like path depth):
     * <pre>
     *   {"outer":{"inner":{"leaf":1}}}
     *   {"outer":{"inner":{"leaf":2}}}
     * </pre>
     * Nested ordinal cache must key on the non-leaf parent index at {@code parentDepth == 2},
     * not only root-level fields.
     */
    public void testDeepNestedOrdinalCacheReusesColumnAcrossRows() {
        SourceSchema schema = new SourceSchema();
        EscfRowBuffer row = new EscfRowBuffer(schema);

        final int ordLeaf = 8;

        // row 1: establish columns
        row.beginRow();
        row.startObject("outer");
        row.startObject("inner");
        final int col = row.longField(ordLeaf, "leaf", 1);
        row.endObject();
        row.endObject();
        row.finishRow();

        assertEquals("column path must reflect inner non-leaf parent", "outer.inner.leaf", schema.getFullPath(col));

        // row 2: warm cache must reuse column indices
        row.beginRow();
        row.startObject("outer");
        row.startObject("inner");
        assertReusesColumn("outer.inner.leaf", col, row.longField(ordLeaf, "leaf", 2));
        row.endObject();
        row.endObject();
        row.finishRow();

        assertEquals("warm ordinal cache must not add schema leaves on row 2", 1, schema.leafCount());

        // row 2 value staged in reused column
        assertEquals("outer.inner.leaf value on row 2", 2, row.scratchNumeric(col));
    }

    /**
     * Layout (same segment under two sibling object parents):
     * <pre>
     *   {"a":{"x":1}, "b":{"x":2}}
     *   {"a":{"x":10}, "b":{"x":20}}
     * </pre>
     * Segment {@code "x"} shares one name-table ordinal but maps to two columns keyed by
     * different non-leaf parents {@code a} and {@code b}.
     */
    public void testSiblingNestedParentsDoNotShareOrdinalColumn() {
        SourceSchema schema = new SourceSchema();
        EscfRowBuffer row = new EscfRowBuffer(schema);

        final int ordX = 3;

        // row 1: establish columns
        row.beginRow();
        row.startObject("a");
        final int colAX = row.longField(ordX, "x", 1);
        row.endObject();
        row.startObject("b");
        final int colBX = row.longField(ordX, "x", 2);
        row.endObject();
        row.finishRow();

        assertEquals("same ordinal under sibling parents must yield two columns", 2, schema.leafCount());
        assertNotEquals("a.x and b.x must not share a column", colAX, colBX);
        assertEquals("a.x column path", "a.x", schema.getFullPath(colAX));
        assertEquals("b.x column path", "b.x", schema.getFullPath(colBX));

        // row 2: warm cache must reuse column indices
        row.beginRow();
        row.startObject("a");
        assertReusesColumn("a.x", colAX, row.longField(ordX, "x", 10));
        row.endObject();
        row.startObject("b");
        assertReusesColumn("b.x", colBX, row.longField(ordX, "x", 20));
        row.endObject();
        row.finishRow();

        // row 2 values staged in reused columns
        assertEquals("a.x value on row 2", 10, row.scratchNumeric(colAX));
        assertEquals("b.x value on row 2", 20, row.scratchNumeric(colBX));
    }

    private static void assertReusesColumn(String path, int expectedCol, int actualCol) {
        assertEquals("row 2 reuses " + path + " column", expectedCol, actualCol);
    }
}
