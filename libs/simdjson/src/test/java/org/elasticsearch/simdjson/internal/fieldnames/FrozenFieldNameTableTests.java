/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdjson.internal.fieldnames;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;

import static org.elasticsearch.simdjson.SimdJsonTestSupport.toBytes;
import static org.elasticsearch.simdjson.SimdJsonTestSupport.toBytesAtOffset;

public class FrozenFieldNameTableTests extends ESTestCase {

    public void testLookupReturnsSameInstance() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("field_name");
        int len = "field_name".length();
        int hash = FieldNameHash.hashName(buf, 0, len);

        String inserted = child.insert(buf, 0, len, hash);
        String looked = child.lookup(buf, 0, len, hash);
        assertSame(inserted, looked);
    }

    public void testLookupBeforeInsertReturnsNull() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("unknown");
        int len = "unknown".length();
        int hash = FieldNameHash.hashName(buf, 0, len);

        assertNull(child.lookup(buf, 0, len, hash));
    }

    public void testInsertCreatesString() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("hello");
        int len = "hello".length();
        int hash = FieldNameHash.hashName(buf, 0, len);

        String result = child.insert(buf, 0, len, hash);
        assertEquals("hello", result);
    }

    public void testFreezeAndLookup() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        String[] names = { "alpha", "beta", "gamma", "delta", "epsilon" };
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child.insert(buf, 0, len, hash);
        }

        child.freeze();

        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            String result = child.lookup(buf, 0, len, hash);
            assertEquals(name, result);
        }
    }

    public void testFreezeIdempotent() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("test");
        int len = "test".length();
        int hash = FieldNameHash.hashName(buf, 0, len);
        child.insert(buf, 0, len, hash);

        child.freeze();
        child.freeze();
        assertTrue(child.isFrozen());
    }

    public void testIsFrozenBeforeAndAfter() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        assertFalse(child.isFrozen());

        byte[] buf = toBytes("x");
        int len = 1;
        int hash = FieldNameHash.hashName(buf, 0, len);
        child.insert(buf, 0, len, hash);

        assertFalse(child.isFrozen());
        child.freeze();
        assertTrue(child.isFrozen());
    }

    public void testLookupWithOffset() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        int offset = 10;
        String name = "offset_field";
        byte[] buf = toBytesAtOffset(name, offset);
        int len = name.length();
        int hash = FieldNameHash.hashName(buf, offset, len);

        String inserted = child.insert(buf, offset, len, hash);
        assertEquals(name, inserted);

        String looked = child.lookup(buf, offset, len, hash);
        assertSame(inserted, looked);
    }

    public void testManyFieldsScaleToHashTable() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        String[] names = new String[200];
        for (int i = 0; i < 200; i++) {
            names[i] = "field_" + i;
            byte[] buf = toBytes(names[i]);
            int len = names[i].length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child.insert(buf, 0, len, hash);
        }

        child.freeze();

        for (int i = 0; i < 200; i++) {
            byte[] buf = toBytes(names[i]);
            int len = names[i].length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            String result = child.lookup(buf, 0, len, hash);
            assertEquals(names[i], result);
        }
    }

    public void testParentChildMerge() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();

        FrozenFieldNameTable.Child child1 = table.makeChild();
        String[] names = { "one", "two", "three" };
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child1.insert(buf, 0, len, hash);
        }
        child1.release();

        FrozenFieldNameTable.Child child2 = table.makeChild();
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            String result = child2.lookup(buf, 0, len, hash);
            assertEquals(name, result);
        }
    }

    public void testTwoChildrenMerge() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();

        FrozenFieldNameTable.Child child1 = table.makeChild();
        byte[] bufAlpha = toBytes("alpha");
        int lenAlpha = "alpha".length();
        int hashAlpha = FieldNameHash.hashName(bufAlpha, 0, lenAlpha);
        child1.insert(bufAlpha, 0, lenAlpha, hashAlpha);
        child1.release();

        FrozenFieldNameTable.Child child2 = table.makeChild();
        byte[] bufBeta = toBytes("beta");
        int lenBeta = "beta".length();
        int hashBeta = FieldNameHash.hashName(bufBeta, 0, lenBeta);
        child2.insert(bufBeta, 0, lenBeta, hashBeta);
        child2.release();

        FrozenFieldNameTable.Child child3 = table.makeChild();
        String resultAlpha = child3.lookup(bufAlpha, 0, lenAlpha, hashAlpha);
        assertEquals("alpha", resultAlpha);
    }

    public void testReleaseFreezesIfDirty() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("dirty_field");
        int len = "dirty_field".length();
        int hash = FieldNameHash.hashName(buf, 0, len);
        child.insert(buf, 0, len, hash);

        assertFalse(child.isFrozen());
        child.release();
        assertTrue(child.isFrozen());
    }

    public void testReleaseRefreshesIfNotDirty() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();

        FrozenFieldNameTable.Child child2 = table.makeChild();
        assertFalse(child2.isFrozen());

        FrozenFieldNameTable.Child child1 = table.makeChild();
        byte[] buf = toBytes("shared");
        int len = "shared".length();
        int hash = FieldNameHash.hashName(buf, 0, len);
        child1.insert(buf, 0, len, hash);
        child1.release();

        assertFalse(child2.isFrozen());
        child2.release();
        assertTrue(child2.isFrozen());

        String result = child2.lookup(buf, 0, len, hash);
        assertEquals("shared", result);
    }

    public void testShortFieldNames() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        for (int nameLen = 1; nameLen <= 8; nameLen++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nameLen; i++) {
                sb.append((char) ('a' + (i % 26)));
            }
            String name = sb.toString();
            byte[] buf = toBytes(name);
            int hash = FieldNameHash.hashName(buf, 0, nameLen);
            child.insert(buf, 0, nameLen, hash);
        }

        child.freeze();

        for (int nameLen = 1; nameLen <= 8; nameLen++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nameLen; i++) {
                sb.append((char) ('a' + (i % 26)));
            }
            String name = sb.toString();
            byte[] buf = toBytes(name);
            int hash = FieldNameHash.hashName(buf, 0, nameLen);
            String result = child.lookup(buf, 0, nameLen, hash);
            assertEquals(name, result);
        }
    }

    public void testLongFieldNames() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        String[] names = { "this_is_a_long_name", "another_long_field_name_here", "field_name_exceeding_8_bytes" };
        for (String name : names) {
            assertTrue(name.length() > 8);
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child.insert(buf, 0, len, hash);
        }

        child.freeze();

        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            String result = child.lookup(buf, 0, len, hash);
            assertEquals(name, result);
        }
    }

    public void testEmptyFieldName() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("");
        int hash = FieldNameHash.hashName(buf, 0, 0);
        String inserted = child.insert(buf, 0, 0, hash);
        assertEquals("", inserted);

        String looked = child.lookup(buf, 0, 0, hash);
        assertSame(inserted, looked);
    }

    public void testInsertAfterFreezeStillWorks() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf1 = toBytes("before");
        int len1 = "before".length();
        int hash1 = FieldNameHash.hashName(buf1, 0, len1);
        child.insert(buf1, 0, len1, hash1);

        child.freeze();

        byte[] buf2 = toBytes("after");
        int len2 = "after".length();
        int hash2 = FieldNameHash.hashName(buf2, 0, len2);
        String result = child.insert(buf2, 0, len2, hash2);
        assertEquals("after", result);
    }

    public void testFieldNamesWithSamePrefix8() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        String name1 = "abcdefgh_suffix1";
        String name2 = "abcdefgh_suffix2";
        assertEquals(name1.substring(0, 8), name2.substring(0, 8));

        byte[] buf1 = toBytes(name1);
        int len1 = name1.length();
        int hash1 = FieldNameHash.hashName(buf1, 0, len1);
        child.insert(buf1, 0, len1, hash1);

        byte[] buf2 = toBytes(name2);
        int len2 = name2.length();
        int hash2 = FieldNameHash.hashName(buf2, 0, len2);
        child.insert(buf2, 0, len2, hash2);

        child.freeze();

        String result1 = child.lookup(buf1, 0, len1, hash1);
        String result2 = child.lookup(buf2, 0, len2, hash2);
        assertEquals(name1, result1);
        assertEquals(name2, result2);
        assertNotSame(result1, result2);
    }

    public void testLookupFieldReturnsOrdinalAfterFreeze() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();
        FrozenFieldNameTable.Child child = table.makeChild();

        byte[] buf = toBytes("timestamp");
        int len = "timestamp".length();
        int hash = FieldNameHash.hashName(buf, 0, len);
        child.insert(buf, 0, len, hash);
        child.freeze();

        ResolvedFieldName resolved = child.lookupField(buf, 0, len, hash, FieldNameHash.readPrefix8(buf, 0, len));
        assertNotNull(resolved);
        assertEquals("timestamp", resolved.name());
        assertEquals(0, resolved.ordinal());
    }

    public void testFieldNameCachingAcrossDocs() {
        FrozenFieldNameTable table = new FrozenFieldNameTable();

        FrozenFieldNameTable.Child child1 = table.makeChild();
        String[] docFields = { "timestamp", "message", "level", "source" };
        for (String name : docFields) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child1.insert(buf, 0, len, hash);
        }
        child1.freeze();
        child1.release();

        FrozenFieldNameTable.Child child2 = table.makeChild();
        assertTrue(child2.isFrozen());
        for (String name : docFields) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            String result = child2.lookup(buf, 0, len, hash);
            assertEquals(name, result);
        }
    }

    public void testDeferredFreezeWaitsForStableSchema() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();

        String[] variantA = { "type", "id", "ts", "val", "label", "active", "count" };
        String[] variantB = { "type", "uid", "score", "tags", "region", "retries" };

        child.beginDocument();
        learnFields(child, variantA);
        child.maybeFreezeAfterDocument();
        assertFalse(child.isFrozen());

        child.beginDocument();
        learnFields(child, variantB);
        child.maybeFreezeAfterDocument();
        assertFalse(child.isFrozen());

        child.beginDocument();
        lookupOnly(child, variantA);
        child.maybeFreezeAfterDocument();
        assertTrue(child.isFrozen());

        for (String name : variantA) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            ResolvedFieldName resolved = child.lookupField(buf, 0, len, hash, FieldNameHash.readPrefix8(buf, 0, len));
            assertNotNull(resolved);
            assertEquals(name, resolved.name());
            assertTrue(resolved.ordinal() >= 0);
        }
        for (String name : variantB) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            ResolvedFieldName resolved = child.lookupField(buf, 0, len, hash, FieldNameHash.readPrefix8(buf, 0, len));
            assertNotNull(resolved);
            assertEquals(name, resolved.name());
            assertTrue(resolved.ordinal() >= 0);
        }
    }

    public void testSmallTableUsesOrdinalsWithoutDirectMap() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();
        String[] names = { "alpha", "beta", "gamma", "delta", "epsilon" };
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child.insert(buf, 0, len, hash);
        }
        child.freeze();
        assertNull(child.frozenDirectOrdinals());

        for (int i = 0; i < names.length; i++) {
            byte[] buf = toBytes(names[i]);
            int len = names[i].length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            ResolvedFieldName resolved = child.lookupField(buf, 0, len, hash, FieldNameHash.readPrefix8(buf, 0, len));
            assertEquals(i, resolved.ordinal());
        }
    }

    public void testLargeTableBuildsDirectMap() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();
        for (int i = 0; i < FrozenFieldNameTable.DIRECT_MAP_MIN_FIELDS; i++) {
            String name = "field_" + i;
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            child.insert(buf, 0, len, hash);
        }
        child.freeze();
        assertNotNull(child.frozenDirectOrdinals());
    }

    /**
     * ClickBench has three {@code (prefix8, len)} collision groups where full byte comparison
     * is still required after hash + prefix match. All groups must resolve to distinct names.
     */
    public void testClickBenchPrefixLenCollisionGroups() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();

        String[][] groups = {
            { "ResolutionWidth", "ResolutionDepth" },
            { "UserAgentMajor", "UserAgentMinor" },
            { "SilverlightVersion1", "SilverlightVersion2", "SilverlightVersion3", "SilverlightVersion4" } };

        for (String[] group : groups) {
            assertSamePrefixAndLength(group);
            for (String name : group) {
                byte[] buf = toBytes(name);
                int len = name.length();
                int hash = FieldNameHash.hashName(buf, 0, len);
                child.insert(buf, 0, len, hash);
            }
        }

        child.freeze();

        for (String[] group : groups) {
            for (String name : group) {
                byte[] buf = toBytes(name);
                int len = name.length();
                int hash = FieldNameHash.hashName(buf, 0, len);
                long pfx = FieldNameHash.readPrefix8(buf, 0, len);
                ResolvedFieldName resolved = child.lookupField(buf, 0, len, hash, pfx);
                assertNotNull(name, resolved);
                assertEquals(name, resolved.name());
                assertTrue(name, resolved.ordinal() >= 0);
            }
        }
    }

    /**
     * Fused {@link FieldNameHash#scanFieldName} + {@link FieldNameLookup#lookupField} must agree
     * with {@link FieldNameHash#hashName} for ClickBench collision pairs.
     */
    public void testScanFieldNameLookupFieldForCollisionPairs() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();

        String[] collisionNames = {
            "ResolutionWidth",
            "ResolutionDepth",
            "UserAgentMajor",
            "UserAgentMinor",
            "SilverlightVersion1",
            "SilverlightVersion4" };

        for (String name : collisionNames) {
            byte[] buf = toBytes(name);
            int len = name.length();
            child.insert(buf, 0, len, FieldNameHash.hashName(buf, 0, len));
        }
        child.freeze();

        for (String name : collisionNames) {
            ResolvedFieldName resolved = lookupViaScan(child, name);
            assertEquals(name, resolved.name());
            assertTrue(resolved.ordinal() >= 0);
        }
    }

    /**
     * When two names share {@code (prefix8, len)}, the direct-mapped bucket is marked colliding
     * and both names still resolve correctly through the probe loop.
     */
    public void testDirectMapMarksCollidingPrefixLenBucket() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();

        String width = "ResolutionWidth";
        String depth = "ResolutionDepth";
        for (int i = 0; i < FrozenFieldNameTable.DIRECT_MAP_MIN_FIELDS - 2; i++) {
            insertName(child, "field_" + i);
        }
        insertName(child, width);
        insertName(child, depth);
        child.freeze();

        int[] directOrdinals = child.frozenDirectOrdinals();
        assertNotNull(directOrdinals);

        byte[] widthBuf = toBytes(width);
        int widthLen = width.length();
        long widthPfx = FieldNameHash.readPrefix8(widthBuf, 0, widthLen);
        int directIdx = FrozenFieldNameTable.directIndex(widthPfx, widthLen, directOrdinals.length);
        assertEquals(-2, directOrdinals[directIdx]);

        ResolvedFieldName resolvedWidth = lookupViaScan(child, width);
        ResolvedFieldName resolvedDepth = lookupViaScan(child, depth);
        assertEquals(width, resolvedWidth.name());
        assertEquals(depth, resolvedDepth.name());
        assertNotEquals(resolvedWidth.ordinal(), resolvedDepth.ordinal());
    }

    /**
     * Repeating the same document shape before all variants are seen freezes early. Unknown
     * names after freeze still parse correctly but carry {@code ordinal = -1}.
     */
    public void testPrematureDeferredFreezeStillCorrect() {
        FrozenFieldNameTable.Child child = new FrozenFieldNameTable().makeChild();

        String[] variantA = { "type", "id", "ts" };
        String[] variantB = { "type", "uid", "score" };

        child.beginDocument();
        learnFields(child, variantA);
        child.maybeFreezeAfterDocument();
        assertFalse(child.isFrozen());

        child.beginDocument();
        lookupOnly(child, variantA);
        child.maybeFreezeAfterDocument();
        assertTrue(child.isFrozen());
        assertNull(child.frozenDirectOrdinals());

        lookupViaScan(child, "type");
        lookupViaScan(child, "id");

        byte[] uidBuf = toBytes("uid");
        int uidLen = "uid".length();
        int uidHash = FieldNameHash.hashName(uidBuf, 0, uidLen);
        assertNull(child.lookup(uidBuf, 0, uidLen, uidHash));

        ResolvedFieldName inserted = child.insertField(uidBuf, 0, uidLen, uidHash);
        assertEquals("uid", inserted.name());
        assertEquals(-1, inserted.ordinal());

        child.beginDocument();
        learnFields(child, variantB);
        child.maybeFreezeAfterDocument();
        assertTrue(child.isFrozen());

        byte[] scoreBuf = toBytes("score");
        int scoreLen = "score".length();
        int scoreHash = FieldNameHash.hashName(scoreBuf, 0, scoreLen);
        assertNull(child.lookup(scoreBuf, 0, scoreLen, scoreHash));
        ResolvedFieldName scoreInserted = child.insertField(scoreBuf, 0, scoreLen, scoreHash);
        assertEquals("score", scoreInserted.name());
        assertEquals(-1, scoreInserted.ordinal());
    }

    private static void insertName(FrozenFieldNameTable.Child child, String name) {
        byte[] buf = toBytes(name);
        int len = name.length();
        child.insert(buf, 0, len, FieldNameHash.hashName(buf, 0, len));
    }

    private static ResolvedFieldName lookupViaScan(FrozenFieldNameTable.Child child, String name) {
        byte[] nameBytes = toBytes(name);
        byte[] buf = Arrays.copyOf(nameBytes, nameBytes.length + 1);
        buf[nameBytes.length] = '"';
        FieldNameHash.FieldNameScan scan = FieldNameHash.scanFieldName(buf, 0);
        assertNotNull(name, scan);
        assertEquals(name.length(), scan.len());
        assertEquals(FieldNameHash.hashName(nameBytes, 0, name.length()), scan.hash());
        ResolvedFieldName resolved = child.lookupField(buf, 0, scan.len(), scan.hash(), scan.prefix8());
        assertNotNull(name, resolved);
        return resolved;
    }

    private static void assertSamePrefixAndLength(String[] names) {
        byte[] first = toBytes(names[0]);
        int len = names[0].length();
        long prefix8 = FieldNameHash.readPrefix8(first, 0, len);
        for (int i = 1; i < names.length; i++) {
            byte[] buf = toBytes(names[i]);
            assertEquals(len, names[i].length());
            assertEquals(prefix8, FieldNameHash.readPrefix8(buf, 0, len));
        }
    }

    private static void learnFields(FrozenFieldNameTable.Child child, String[] names) {
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            if (child.lookup(buf, 0, len, hash) == null) {
                child.insert(buf, 0, len, hash);
            }
        }
    }

    private static void lookupOnly(FrozenFieldNameTable.Child child, String[] names) {
        for (String name : names) {
            byte[] buf = toBytes(name);
            int len = name.length();
            int hash = FieldNameHash.hashName(buf, 0, len);
            assertNotNull(child.lookup(buf, 0, len, hash));
        }
    }
}
