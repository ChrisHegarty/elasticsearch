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
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link SimdJsonBatchParser}: stage 1 runs once over a contiguous buffer and
 * {@link SimdJsonBatchParser#prepareDocumentWindow} provides per-document {@link BitIndexes} windows.
 */
public class SimdJsonBatchParserTests extends ESTestCase {

    private static final int CAPACITY = 64 * 1024;
    private static final int PADDING = 64;

    // -- prepareDocumentWindow: stage 1 only, direct BitIndexes access --------

    public void testPrepareDocumentWindowSetsReadWindow() {
        String doc1 = "{\"a\":1}";
        String doc2 = "{\"b\":2}";
        byte[] buffer = buildBatchBuffer(doc1, doc2);
        int[] offsets = computeOffsets(doc1, doc2);
        int[] lengths = computeLengths(doc1, doc2);

        SimdJsonBatchParser batch = new SimdJsonBatchParser(CAPACITY);
        batch.stage1(buffer, totalLen(lengths));

        batch.prepareDocumentWindow(offsets[0], lengths[0]);
        BitIndexes bi = batch.bitIndexes();
        assertFalse("BitIndexes should have entries for doc 0", bi.isEnd());
        int firstIdx = bi.getAndAdvance();
        assertEquals('{', buffer[firstIdx]);

        batch.prepareDocumentWindow(offsets[1], lengths[1]);
        assertFalse("BitIndexes should have entries for doc 1", bi.isEnd());
        int secondIdx = bi.getAndAdvance();
        assertEquals('{', buffer[secondIdx]);
        assertTrue("second doc starts at or after first doc ends", secondIdx >= offsets[1]);
    }

    public void testPrepareDocumentWindowIsolatesDocuments() {
        String doc1 = "{\"a\":1,\"b\":2}";
        String doc2 = "{\"c\":3}";
        byte[] buffer = buildBatchBuffer(doc1, doc2);
        int[] offsets = computeOffsets(doc1, doc2);
        int[] lengths = computeLengths(doc1, doc2);

        SimdJsonBatchParser batch = new SimdJsonBatchParser(CAPACITY);
        batch.stage1(buffer, totalLen(lengths));

        batch.prepareDocumentWindow(offsets[0], lengths[0]);
        BitIndexes bi = batch.bitIndexes();
        int count = 0;
        while (!bi.isEnd()) {
            int idx = bi.getAndAdvance();
            assertTrue("structural index " + idx + " should be within doc 0 range", idx < offsets[0] + lengths[0]);
            count++;
        }
        assertTrue("doc 0 should have multiple structural indices", count > 1);

        batch.prepareDocumentWindow(offsets[1], lengths[1]);
        count = 0;
        while (!bi.isEnd()) {
            int idx = bi.getAndAdvance();
            assertTrue(
                "structural index " + idx + " should be within doc 1 range [" + offsets[1] + ", " + (offsets[1] + lengths[1]) + ")",
                idx >= offsets[1] && idx < offsets[1] + lengths[1]
            );
            count++;
        }
        assertTrue("doc 1 should have structural indices", count > 0);
    }

    public void testManySmallDocumentsWindow() {
        String[] docs = new String[100];
        for (int i = 0; i < 100; i++) {
            docs[i] = "{\"i\":" + i + "}";
        }
        byte[] buffer = buildBatchBuffer(docs);
        int[] offsets = computeOffsets(docs);
        int[] lengths = computeLengths(docs);

        SimdJsonBatchParser batch = new SimdJsonBatchParser(CAPACITY);
        batch.stage1(buffer, totalLen(lengths));

        for (int d = 0; d < docs.length; d++) {
            batch.prepareDocumentWindow(offsets[d], lengths[d]);
            BitIndexes bi = batch.bitIndexes();
            assertFalse("BitIndexes should have entries for doc " + d, bi.isEnd());
            int firstIdx = bi.getAndAdvance();
            assertEquals("doc " + d + " should start with '{'", '{', buffer[firstIdx]);
        }
    }

    // -- stage1 must be called first -----------------------------------------

    public void testPrepareDocumentWindowBeforeStage1Throws() {
        SimdJsonBatchParser batch = new SimdJsonBatchParser(CAPACITY);
        expectThrows(IllegalStateException.class, () -> batch.prepareDocumentWindow(0, 10));
    }

    // -- batch reuse (stage1 called again) -----------------------------------

    public void testBatchReuse() {
        SimdJsonBatchParser batch = new SimdJsonBatchParser(CAPACITY);

        String[] docs1 = { "{\"a\":1}", "{\"b\":2}" };
        byte[] buffer1 = buildBatchBuffer(docs1);
        int[] offsets1 = computeOffsets(docs1);
        int[] lengths1 = computeLengths(docs1);

        batch.stage1(buffer1, totalLen(lengths1));
        for (int d = 0; d < docs1.length; d++) {
            batch.prepareDocumentWindow(offsets1[d], lengths1[d]);
            assertFalse(batch.bitIndexes().isEnd());
        }

        String[] docs2 = { "{\"x\":10,\"y\":20}", "{\"z\":30}" };
        byte[] buffer2 = buildBatchBuffer(docs2);
        int[] offsets2 = computeOffsets(docs2);
        int[] lengths2 = computeLengths(docs2);

        batch.stage1(buffer2, totalLen(lengths2));
        for (int d = 0; d < docs2.length; d++) {
            batch.prepareDocumentWindow(offsets2[d], lengths2[d]);
            assertFalse(batch.bitIndexes().isEnd());
        }
    }

    // -- helpers -------------------------------------------------------------

    private static byte[] buildBatchBuffer(String... jsonDocs) {
        List<byte[]> docBytes = new ArrayList<>();
        int total = 0;
        for (String doc : jsonDocs) {
            byte[] b = doc.getBytes(StandardCharsets.UTF_8);
            docBytes.add(b);
            total += b.length;
        }
        byte[] buffer = new byte[total + PADDING];
        int pos = 0;
        for (byte[] b : docBytes) {
            System.arraycopy(b, 0, buffer, pos, b.length);
            pos += b.length;
        }
        return buffer;
    }

    private static int[] computeOffsets(String... jsonDocs) {
        int[] offsets = new int[jsonDocs.length];
        int pos = 0;
        for (int i = 0; i < jsonDocs.length; i++) {
            offsets[i] = pos;
            pos += jsonDocs[i].getBytes(StandardCharsets.UTF_8).length;
        }
        return offsets;
    }

    private static int[] computeLengths(String... jsonDocs) {
        int[] lengths = new int[jsonDocs.length];
        for (int i = 0; i < jsonDocs.length; i++) {
            lengths[i] = jsonDocs[i].getBytes(StandardCharsets.UTF_8).length;
        }
        return lengths;
    }

    private static int totalLen(int[] lengths) {
        int total = 0;
        for (int len : lengths) {
            total += len;
        }
        return total;
    }
}
