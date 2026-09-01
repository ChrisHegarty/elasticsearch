/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.escf;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.util.ByteUtils;
import org.elasticsearch.common.util.MockPageCacheRecycler;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.simdjson.JsonDocumentParser;
import org.elasticsearch.simdjson.SimdJsonParserPool;
import org.elasticsearch.sourcebatch.LeafSink;
import org.elasticsearch.sourcebatch.SourceBatchEncodeHelper;
import org.elasticsearch.sourcebatch.SourceValueType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.BytesRefRecycler;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentString;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link EscfDocumentHandler} routing and KEY_VALUE integration.
 * Wire-format correctness for KV blobs is covered by {@link org.elasticsearch.sourcebatch.KeyValueWriterTests}.
 */
public class EscfDocumentHandlerTests extends ESTestCase {

    private static byte[] expectedObjectKv(String objectJson) throws IOException {
        try (
            XContentParser parser = XContentHelper.createParserNotCompressed(
                XContentParserConfiguration.EMPTY,
                new BytesArray(objectJson),
                XContentType.JSON
            )
        ) {
            parser.nextToken();
            return SourceBatchEncodeHelper.serializeKeyValue(parser);
        }
    }

    private static byte[] encodeItemsArrayViaHandler(String objectJson) throws IOException {
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();
        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, LeafSink.NO_OP, false);

        handler.startArray("items");
        handler.arrayElemStartObject();
        try (
            XContentParser parser = XContentHelper.createParserNotCompressed(
                XContentParserConfiguration.EMPTY,
                new BytesArray(objectJson),
                XContentType.JSON
            )
        ) {
            parser.nextToken();
            walkObjectFields(parser, handler);
        }
        handler.arrayElemEndObject();
        handler.endArray();
        row.finishRow();

        assertEquals("items", backend.columnPath(0));
        return firstKeyValueBytes((byte[]) row.scratchVar(0));
    }

    private static byte[] encodeItemsArrayViaSimdWalk(String doc, String innerObjectJson) throws IOException {
        assumeTrue("simdjson ESCF encoding required", EscfEncoder.isSimdEnabled());
        byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();
        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, LeafSink.NO_OP, false);

        JsonDocumentParser docParser = SimdJsonParserPool.getDefault().forCurrentThread();
        docParser.parseDocument(bytes, 0, bytes.length, handler);
        docParser.publishFieldNames();
        row.finishRow();

        assertEquals("items", backend.columnPath(0));
        byte[] actual = firstKeyValueBytes((byte[]) row.scratchVar(0));
        assertArrayEquals(expectedObjectKv(innerObjectJson), actual);
        return actual;
    }

    /** First element of a UNION inline array must be KEY_VALUE; returns its payload bytes. */
    private static byte[] firstKeyValueBytes(byte[] packedUnionArray) {
        assertEquals(SourceValueType.KEY_VALUE, packedUnionArray[0]);
        int len = ByteUtils.readIntLE(packedUnionArray, 1);
        byte[] kv = new byte[len];
        System.arraycopy(packedUnionArray, 5, kv, 0, len);
        return kv;
    }

    private static EscfBatchBuilder newBackend() {
        return new EscfBatchBuilder(new BytesRefRecycler(new MockPageCacheRecycler(org.elasticsearch.common.settings.Settings.EMPTY)));
    }

    private static void walkObjectFields(XContentParser parser, EscfDocumentHandler handler) throws IOException {
        assert parser.currentToken() == XContentParser.Token.START_OBJECT;
        if (parser.nextToken() == XContentParser.Token.END_OBJECT) {
            return;
        }
        walkObjectFieldsContent(parser, handler);
    }

    private static void walkObjectFieldsContent(XContentParser parser, EscfDocumentHandler handler) throws IOException {
        while (parser.currentToken() != XContentParser.Token.END_OBJECT) {
            walkField(parser, handler);
            parser.nextToken();
        }
    }

    private static void walkField(XContentParser parser, EscfDocumentHandler handler) throws IOException {
        if (parser.currentToken() != XContentParser.Token.FIELD_NAME) {
            throw new IllegalStateException("Expected FIELD_NAME but got " + parser.currentToken());
        }
        String name = parser.currentName();
        XContentParser.Token token = parser.nextToken();
        switch (token) {
            case VALUE_STRING -> {
                XContentString.UTF8Bytes str = parser.optimizedText().bytes();
                handler.stringField(name, str.bytes(), str.offset(), str.length());
            }
            case VALUE_NUMBER -> {
                long val = parser.longValue();
                handler.longField(name, val, val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE, new byte[0], 0, 0);
            }
            case VALUE_BOOLEAN -> handler.booleanField(name, parser.booleanValue(), new byte[0], 0, 0);
            case VALUE_NULL -> handler.nullField(name);
            case START_OBJECT -> {
                handler.startObject(name);
                if (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    walkObjectFieldsContent(parser, handler);
                }
                handler.endObject();
            }
            case START_ARRAY -> {
                handler.startArray(name);
                walkArrayElements(parser, handler);
                handler.endArray();
            }
            default -> throw new IllegalStateException("Unexpected token " + token);
        }
    }

    private static void walkArrayElements(XContentParser parser, EscfDocumentHandler handler) throws IOException {
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            switch (token) {
                case VALUE_STRING -> {
                    XContentString.UTF8Bytes str = parser.optimizedText().bytes();
                    handler.arrayElemString(str.bytes(), str.offset(), str.length());
                }
                case VALUE_NUMBER -> {
                    long val = parser.longValue();
                    handler.arrayElemLong(val, val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE);
                }
                case VALUE_BOOLEAN -> handler.arrayElemBoolean(parser.booleanValue());
                case VALUE_NULL -> handler.arrayElemNull();
                case START_OBJECT -> {
                    handler.arrayElemStartObject();
                    if (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                        walkObjectFieldsContent(parser, handler);
                    }
                    handler.arrayElemEndObject();
                }
                case START_ARRAY -> {
                    handler.arrayElemStartArray();
                    walkArrayElements(parser, handler);
                    handler.arrayElemEndArray();
                }
                default -> throw new IllegalStateException("Unexpected token " + token);
            }
        }
    }

    public void testObjectInArrayKvMatchesHelper() throws IOException {
        String inner = """
            {"a":1,"b":"x"}""";
        assertArrayEquals(expectedObjectKv(inner), encodeItemsArrayViaHandler(inner));
    }

    public void testNestedObjectInArrayKvMatchesHelper() throws IOException {
        String inner = """
            {"outer":{"inner":42}}""";
        assertArrayEquals(expectedObjectKv(inner), encodeItemsArrayViaHandler(inner));
    }

    public void testArrayInsideObjectInArrayKvMatchesHelper() throws IOException {
        String inner = """
            {"tags":["a","b"],"n":1}""";
        assertArrayEquals(expectedObjectKv(inner), encodeItemsArrayViaHandler(inner));
    }

    public void testRootScalarsGoToRowBuffer() {
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();
        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, LeafSink.NO_OP, false);

        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        handler.longField("n", 42, true, new byte[] { '4', '2' }, 0, 2);
        handler.stringField("s", hello, 0, hello.length);
        row.finishRow();

        assertEquals("n", backend.columnPath(0));
        assertEquals("s", backend.columnPath(1));
        assertEquals(SourceValueType.INT, row.scratchType(0));
        assertEquals(42, row.scratchNumeric(0));
        assertEquals(SourceValueType.STRING, row.scratchType(1));
    }

    public void testRawTextModeSinkReceivesSourceBytes() {
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();

        List<String> paths = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        LeafSink sink = new LeafSink() {
            @Override
            public boolean passRawText() {
                return true;
            }

            @Override
            public void onTextPrimitive(int columnIndex, String dottedPath, byte type, XContentString.UTF8Bytes textBytes) {
                paths.add(dottedPath);
                texts.add(new String(textBytes.bytes(), textBytes.offset(), textBytes.length(), StandardCharsets.UTF_8));
            }
        };

        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, sink, true);
        handler.longField("n", 99, true, new byte[] { '9', '9' }, 0, 2);
        handler.booleanField("b", true, new byte[] { 't', 'r', 'u', 'e' }, 0, 4);
        row.finishRow();

        assertTrue(paths.contains("n"));
        assertTrue(paths.contains("b"));
        assertEquals("99", texts.get(paths.indexOf("n")));
        assertEquals("true", texts.get(paths.indexOf("b")));
    }

    public void testRootArrayFiresOnArrayLeaf() {
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();
        AtomicInteger arrayEvents = new AtomicInteger();
        LeafSink sink = new LeafSink() {
            @Override
            public boolean passRawText() {
                return false;
            }

            @Override
            public void onArrayLeaf(int columnIndex, String dottedPath) {
                assertEquals("tags", dottedPath);
                arrayEvents.incrementAndGet();
            }
        };
        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, sink, false);

        byte[] tag = "a".getBytes(StandardCharsets.UTF_8);
        handler.startArray("tags");
        handler.arrayElemString(tag, 0, tag.length);
        handler.endArray();
        row.finishRow();

        assertEquals(1, arrayEvents.get());
        assertEquals("tags", backend.columnPath(0));
    }

    public void testSimdWalkObjectInArrayMatchesHelper() throws IOException {
        String inner = """
            {"tags":["a","b"],"n":1}""";
        encodeItemsArrayViaSimdWalk("""
            {"items":[{"tags":["a","b"],"n":1}]}""", inner);
    }

    /**
     * Layout (ordinal API while serializing an object inside an array — KV path, not schema leaves):
     * <pre>
     *   {"items":[{"n":1}]}
     *   then root scalar: {"n":99} on the same row
     * </pre>
     * While {@code kvDepth > 0}, {@link EscfDocumentHandler} must ignore field ordinals and route
     * inner fields into the KEY_VALUE blob. A subsequent root-level ordinal write must still
     * populate a top-level {@code n} column without inheriting a stale cache entry from the
     * array element.
     */
    public void testOrdinalIgnoredInsideArrayObjectKv() {
        EscfBatchBuilder backend = newBackend();
        EscfRowBuffer row = backend.beginRow();
        EscfDocumentHandler handler = new EscfDocumentHandler(row, backend, LeafSink.NO_OP, false);

        int ordN = 7;

        handler.startArray("items");
        handler.arrayElemStartObject();
        handler.longField(ordN, "n", 1, true, new byte[] { '1' }, 0, 1);
        handler.arrayElemEndObject();
        handler.endArray();

        handler.longField(ordN, "n", 99, true, new byte[] { '9', '9' }, 0, 2);
        row.finishRow();

        assertEquals("the items array should be at column 0", "items", backend.columnPath(0));
        assertEquals("root n should be at column 1", "n", backend.columnPath(1));
        assertEquals("items holds the packed [{\"n\":1}] blob", SourceValueType.UNION_ARRAY, row.scratchType(0));
        assertEquals("root n should of type INT", SourceValueType.INT, row.scratchType(1));
        assertEquals("the n column should be 99 (not 1) ", 99, row.scratchNumeric(1));
    }
}
