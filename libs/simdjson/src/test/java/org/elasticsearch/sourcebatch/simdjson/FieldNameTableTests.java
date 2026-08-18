/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.sourcebatch.simdjson;

import org.elasticsearch.test.ESTestCase;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for {@link FieldNameTable}: hashing, scanAndHash, parent/child merging,
 * cache hit/miss behavior, and edge cases for various key lengths.
 */
public class FieldNameTableTests extends ESTestCase {

    // -- hashName consistency -----------------------------------------------

    public void testHashDeterministic() {
        byte[] buf = padForHash("fieldName");
        int len = "fieldName".length();
        int h1 = FieldNameTable.hashName(buf, 0, len);
        int h2 = FieldNameTable.hashName(buf, 0, len);
        assertEquals(h1, h2);
    }

    public void testHashNeverZero() {
        for (String name : new String[] { "", "a", "ab", "abcd", "abcdefgh", "abcdefghijklmnop", "x".repeat(50) }) {
            byte[] buf = padForHash(name);
            int len = name.getBytes(StandardCharsets.UTF_8).length;
            assertNotEquals("hash must never be 0 (reserved for empty slot)", 0, FieldNameTable.hashName(buf, 0, len));
        }
    }

    public void testHashDistinctForDifferentNames() {
        Set<Integer> hashes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String name = "field_" + i;
            byte[] buf = padForHash(name);
            int len = name.getBytes(StandardCharsets.UTF_8).length;
            hashes.add(FieldNameTable.hashName(buf, 0, len));
        }
        assertTrue("expected at least 195 distinct hashes out of 200 names, got " + hashes.size(), hashes.size() >= 195);
    }

    public void testHashWithOffset() {
        byte[] padded = padForHash("XXXXname");
        byte[] plain = padForHash("name");
        assertEquals(FieldNameTable.hashName(plain, 0, 4), FieldNameTable.hashName(padded, 4, 4));
    }

    public void testHashShortKeys() {
        for (int len = 1; len <= 8; len++) {
            byte[] buf = new byte[len + 8];
            Arrays.fill(buf, 0, len, (byte) 'x');
            int h = FieldNameTable.hashName(buf, 0, len);
            assertNotEquals(0, h);
        }
    }

    public void testHashMediumKeys() {
        for (int len = 9; len <= 16; len++) {
            byte[] buf = new byte[len + 8];
            Arrays.fill(buf, 0, len, (byte) 'y');
            int h = FieldNameTable.hashName(buf, 0, len);
            assertNotEquals(0, h);
        }
    }

    public void testHashLongKeys() {
        for (int len : new int[] { 17, 32, 48, 100, 255 }) {
            byte[] buf = new byte[len + 8];
            Arrays.fill(buf, 0, len, (byte) 'z');
            int h = FieldNameTable.hashName(buf, 0, len);
            assertNotEquals(0, h);
        }
    }

    public void testHashEmptyKey() {
        byte[] buf = new byte[8];
        int h = FieldNameTable.hashName(buf, 0, 0);
        assertNotEquals(0, h);
    }

    // -- scanAndHash --------------------------------------------------------

    /**
     * scanAndHash operates on real JSON buffers where the field name sits after an opening quote
     * and is followed by a closing quote. The buffer must be laid out as a real JSON structure
     * would produce (with SIMD padding), because the function does 8-byte word reads.
     * We embed the content in a buffer filled with spaces (0x20) to avoid false zero-byte triggers.
     */
    public void testScanAndHashSimpleField() {
        byte[] buf = makeScanBuffer("hello");
        long result = FieldNameTable.scanAndHash(buf, 0);
        assertNotEquals("scanAndHash should not return -1 for a simple field", -1L, result);
        int len = (int) (result & 0xFFFFFFFFL);
        int hash = (int) (result >>> 32);
        assertEquals(5, len);
        assertEquals(FieldNameTable.hashName(buf, 0, 5), hash);
    }

    public void testScanAndHashReturnsMinusOneForBackslash() {
        byte[] buf = makeScanBufferRaw("hel\\lo\"");
        long result = FieldNameTable.scanAndHash(buf, 0);
        assertEquals(-1L, result);
    }

    public void testScanAndHashBackslashBeforeQuote() {
        byte[] buf = makeScanBufferRaw("abc\\\"def\"");
        long result = FieldNameTable.scanAndHash(buf, 0);
        assertEquals("backslash appears before quote, should return -1", -1L, result);
    }

    public void testScanAndHashEmptyFieldName() {
        byte[] buf = makeScanBuffer("");
        long result = FieldNameTable.scanAndHash(buf, 0);
        assertNotEquals(-1L, result);
        int len = (int) (result & 0xFFFFFFFFL);
        assertEquals(0, len);
    }

    public void testScanAndHashLongFieldName() {
        String name = "a_very_long_field_name_that_spans_multiple_eight_byte_words";
        byte[] buf = makeScanBuffer(name);
        long result = FieldNameTable.scanAndHash(buf, 0);
        assertNotEquals(-1L, result);
        int len = (int) (result & 0xFFFFFFFFL);
        int hash = (int) (result >>> 32);
        assertEquals(name.length(), len);
        assertEquals(FieldNameTable.hashName(buf, 0, name.length()), hash);
    }

    public void testScanAndHashConsistentWithHashName() {
        for (String name : new String[] { "a", "ab", "abc", "abcd", "abcde", "abcdefgh", "twelve_bytes", "sixteen_bytes_xx" }) {
            byte[] buf = makeScanBuffer(name);
            long result = FieldNameTable.scanAndHash(buf, 0);
            assertNotEquals("scanAndHash failed for '" + name + "'", -1L, result);
            int len = (int) (result & 0xFFFFFFFFL);
            int hash = (int) (result >>> 32);
            assertEquals("length mismatch for '" + name + "'", name.length(), len);
            assertEquals("hash mismatch for '" + name + "'", FieldNameTable.hashName(buf, 0, len), hash);
        }
    }

    // -- Child: lookup, cache hit/miss --------------------------------------

    public void testChildLookupCacheHit() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        byte[] buf = "myfield".getBytes(StandardCharsets.UTF_8);
        String first = child.lookupName(buf, 0, buf.length);
        assertEquals("myfield", first);

        String second = child.lookupName(buf, 0, buf.length);
        assertSame("cache hit should return same String instance", first, second);
    }

    public void testChildLookupWithOffset() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        byte[] buf = "____myfield".getBytes(StandardCharsets.UTF_8);
        String name = child.lookupName(buf, 4, 7);
        assertEquals("myfield", name);

        String again = child.lookupName(buf, 4, 7);
        assertSame(name, again);
    }

    public void testChildLookupManyFields() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        String[] expected = new String[200];
        for (int i = 0; i < 200; i++) {
            byte[] buf = ("field_" + i).getBytes(StandardCharsets.UTF_8);
            expected[i] = child.lookupName(buf, 0, buf.length);
        }

        for (int i = 0; i < 200; i++) {
            byte[] buf = ("field_" + i).getBytes(StandardCharsets.UTF_8);
            String result = child.lookupName(buf, 0, buf.length);
            assertSame("cache hit expected for field_" + i, expected[i], result);
        }
    }

    public void testChildLookupLongKeyBeyondInlineThreshold() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        String longName = "a_long_field_name_exceeding_sixteen_bytes_for_sure";
        byte[] raw = longName.getBytes(StandardCharsets.UTF_8);
        assertTrue(raw.length > FieldNameTable.MAX_INLINE_BYTES);
        byte[] buf = padForHash(longName);

        String first = child.lookupName(buf, 0, raw.length);
        assertEquals(longName, first);

        String second = child.lookupName(buf, 0, raw.length);
        assertSame(first, second);
    }

    // -- Parent/child merge -------------------------------------------------

    public void testParentChildMerge() {
        FieldNameTable root = new FieldNameTable();

        FieldNameTable.Child child1 = root.makeChild();
        byte[] buf = "shared_field".getBytes(StandardCharsets.UTF_8);
        String fromChild1 = child1.lookupName(buf, 0, buf.length);
        child1.release();

        FieldNameTable.Child child2 = root.makeChild();
        String fromChild2 = child2.lookupName(buf, 0, buf.length);
        assertEquals(fromChild1, fromChild2);
        child2.release();
    }

    public void testMergeFromMultipleChildren() {
        FieldNameTable root = new FieldNameTable();

        FieldNameTable.Child child1 = root.makeChild();
        byte[] buf1 = "alpha".getBytes(StandardCharsets.UTF_8);
        child1.lookupName(buf1, 0, buf1.length);
        child1.release();

        FieldNameTable.Child child2 = root.makeChild();
        byte[] buf2 = "beta".getBytes(StandardCharsets.UTF_8);
        child2.lookupName(buf2, 0, buf2.length);
        // child2 should have inherited "alpha" from parent
        String alphaFromChild2 = child2.lookupName(buf1, 0, buf1.length);
        assertEquals("alpha", alphaFromChild2);
        child2.release();

        FieldNameTable.Child child3 = root.makeChild();
        assertEquals("alpha", child3.lookupName(buf1, 0, buf1.length));
        assertEquals("beta", child3.lookupName(buf2, 0, buf2.length));
        child3.release();
    }

    public void testChildReuseAfterRelease() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        byte[] buf = "reusable".getBytes(StandardCharsets.UTF_8);
        String first = child.lookupName(buf, 0, buf.length);
        child.release();

        String afterRelease = child.lookupName(buf, 0, buf.length);
        assertEquals(first, afterRelease);
    }

    public void testNoDirtyNoMerge() {
        FieldNameTable root = new FieldNameTable();

        FieldNameTable.Child child1 = root.makeChild();
        byte[] buf = "field".getBytes(StandardCharsets.UTF_8);
        child1.lookupName(buf, 0, buf.length);
        child1.release();

        FieldNameTable.Child child2 = root.makeChild();
        child2.lookupName(buf, 0, buf.length);
        assertFalse("no new names added, child should not be dirty", child2.dirty);
        child2.release();
    }

    // -- Inline vs external key storage boundary ----------------------------

    public void testInlineBoundaryExactly16Bytes() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        byte[] exact16 = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        assertEquals(16, exact16.length);

        String first = child.lookupName(exact16, 0, exact16.length);
        String second = child.lookupName(exact16, 0, exact16.length);
        assertSame(first, second);
    }

    public void testExternalKeyJustOver16Bytes() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        String name = "0123456789abcdefg";
        byte[] buf = padForHash(name);
        int len = name.length();
        assertEquals(17, len);

        String first = child.lookupName(buf, 0, len);
        String second = child.lookupName(buf, 0, len);
        assertSame(first, second);
    }

    // -- Tail byte matching (1, 2, 3 byte tails) ---------------------------

    public void testInlineKeyTailLengths() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        for (int len = 1; len <= FieldNameTable.MAX_INLINE_BYTES; len++) {
            byte[] buf = new byte[len];
            for (int j = 0; j < len; j++) {
                buf[j] = (byte) ('a' + (j % 26));
            }
            String name = new String(buf, StandardCharsets.UTF_8);
            String first = child.lookupName(buf, 0, len);
            assertEquals("len=" + len, name, first);
            String second = child.lookupName(buf, 0, len);
            assertSame("cache hit expected for len=" + len, first, second);
        }
    }

    // -- Non-dirty child picks up concurrent parent updates ------------------

    public void testNonDirtyChildRefreshesFromParent() {
        FieldNameTable root = new FieldNameTable();

        FieldNameTable.Child child1 = root.makeChild();
        byte[] buf = "newfield".getBytes(StandardCharsets.UTF_8);
        child1.lookupName(buf, 0, buf.length);
        child1.release(); // merges "newfield" into parent

        FieldNameTable.Child child2 = root.makeChild();
        // child2 starts with "newfield" from parent
        String found = child2.lookupName(buf, 0, buf.length);
        assertEquals("newfield", found);
        assertFalse("no new names added, should not be dirty", child2.dirty);

        // Now simulate another thread merging a new name while child2 is alive
        FieldNameTable.Child child3 = root.makeChild();
        byte[] buf2 = "othername".getBytes(StandardCharsets.UTF_8);
        child3.lookupName(buf2, 0, buf2.length);
        child3.release(); // merges "othername" into parent

        // child2 releases without being dirty — should refresh from parent
        child2.release();

        // After refresh, child2 should now have "othername"
        String other = child2.lookupName(buf2, 0, buf2.length);
        assertEquals("othername", other);
        // Since the name was already in the refreshed snapshot, the child should not be dirty
        assertFalse("name was inherited from parent, should not be dirty", child2.dirty);
    }

    // -- Linear probing collision testing ------------------------------------

    public void testHashCollisionResolution() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        // Insert many names into the same child to increase collision probability.
        // Buffers are padded because hashName reads 8-byte words that may extend past the name.
        String[] names = new String[500];
        for (int i = 0; i < names.length; i++) {
            names[i] = "collision_test_field_" + i;
        }
        for (String name : names) {
            byte[] buf = padForHash(name);
            int len = name.getBytes(StandardCharsets.UTF_8).length;
            child.lookupName(buf, 0, len);
        }

        // Verify all can be retrieved
        for (String name : names) {
            byte[] buf = padForHash(name);
            int len = name.getBytes(StandardCharsets.UTF_8).length;
            String result = child.lookupName(buf, 0, len);
            assertEquals(name, result);
        }
    }

    // -- Capacity limit: beyond MAX_COUNT no new names are cached -----------

    public void testBeyondMaxCountNewNamesNotCached() {
        FieldNameTable root = new FieldNameTable();
        FieldNameTable.Child child = root.makeChild();

        // Fill to MAX_COUNT. Buffers padded for hashName's 8-byte word reads.
        for (int i = 0; i < FieldNameTable.MAX_COUNT; i++) {
            String name = "fill_" + i;
            byte[] buf = padForHash(name);
            int len = name.getBytes(StandardCharsets.UTF_8).length;
            child.lookupName(buf, 0, len);
        }
        assertEquals(FieldNameTable.MAX_COUNT, child.count);

        // One more should still return the correct name but not increase count
        byte[] extra = padForHash("overflow_name");
        int extraLen = "overflow_name".length();
        String result = child.lookupName(extra, 0, extraLen);
        assertEquals("overflow_name", result);
        assertEquals("count should not increase past MAX_COUNT", FieldNameTable.MAX_COUNT, child.count);

        // Looking it up again should still work (creates a new String each time since not cached)
        String result2 = child.lookupName(extra, 0, extraLen);
        assertEquals("overflow_name", result2);
    }

    // -- Helpers: buffers need 8+ bytes of readable slack for the 8-byte word reads in hashName/scanAndHash

    /** Pads a name string with 8 trailing zero bytes so hashName can safely read 8-byte words past the end. */
    private static byte[] padForHash(String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[raw.length + 8];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }

    /**
     * Creates a buffer for scanAndHash: the field name followed by a closing quote,
     * with space-filled padding. This mirrors what the real JSON buffer looks like
     * when the opening quote has already been consumed and startIdx points at the
     * first character of the field name.
     */
    private static byte[] makeScanBuffer(String fieldName) {
        return makeScanBufferRaw(fieldName + "\"");
    }

    /**
     * Creates a buffer for scanAndHash from raw content (caller includes the closing quote
     * and any escape sequences). Padded with spaces (0x20) to avoid false positives from
     * the zero-byte detection trick.
     */
    private static byte[] makeScanBufferRaw(String content) {
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[raw.length + 16];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }
}
