/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.shard;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.mapper.LuceneDocument;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.mapper.VersionFieldMapper;
import org.elasticsearch.plugins.internal.XContentMeteringParserDecorator;
import org.elasticsearch.xcontent.XContentType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Deliberately trivial CSV parser.
 *
 * Parses comma separate input, formatted as a number of newline separated rows of data:
 * row1: field names
 * row2: field typs
 * row3: doc1
 * row4: doc2
 * row5: doc3
 * ...
 */
public class CSVParser {

    /** Returns a ParsedDocument whose docs is a list of LuceneDocuments. */
    public static ParsedDocument parseInput(SourceToParse source) {
        try (StreamInput is = source.source().streamInput()) {
            List<LuceneDocument> docs = parseCsv(is);
            return new ParsedDocument(
                VersionFieldMapper.versionField(),
                SeqNoFieldMapper.SequenceIDFields.emptySeqID(SeqNoFieldMapper.SeqNoIndexOptions.DOC_VALUES_ONLY),
                "1",
                null,
                docs,
                source.source(),
                XContentType.JSON,
                null,
                XContentMeteringParserDecorator.UNKNOWN_SIZE
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<LuceneDocument> parseCsv(StreamInput in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            String headerLine = reader.readLine();
            String typeLine = reader.readLine();
            if (headerLine == null || typeLine == null) {
                throw new AssertionError("Error: CSV must have at least two lines (headers and types)");
            }

            String[] fieldNames = headerLine.split(",");
            String[] fieldTypes = typeLine.split(",");

            if (fieldNames.length != fieldTypes.length) {
                var msg = ("Error: Field name count " + fieldNames.length +
                    " and type count " + fieldTypes.length + "  must match.");
                throw new AssertionError(msg);
            }

            String line;
            int rowNum = 0;
            ArrayList<LuceneDocument> docs = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                LuceneDocument doc = new LuceneDocument();
                rowNum++;
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                if (values.length != fieldNames.length) {
                    throw new AssertionError("wrong number of fields in line:" + rowNum);
                }

                for (int i = 0; i < fieldNames.length; i++) {
                    String name = fieldNames[i];
                    String type = fieldTypes[i];
                    String raw = values[i];
                    var field = parseValueToField(name, type, raw);
                    doc.add(field);
                }
                docs.add(doc);
            }
            return docs;
        }
    }

    static Field parseValueToField(String name, String type, String raw) {
        if (raw.isEmpty()) {
            throw new AssertionError("empty");
        }
        return switch (type) {
            case "short" -> new SortedNumericDocValuesField(name, Short.parseShort(raw));
            case "int" -> new SortedNumericDocValuesField(name, Integer.parseInt(raw));
            case "long" -> new SortedNumericDocValuesField(name, Long.parseLong(raw));
            case "keyword" -> new SortedDocValuesField(name, new BytesRef(raw.getBytes(UTF_8)));
            // TODO add date support :  JavaDatetime.convertoLong(raw);
            // _id is a StringField IDFieldMapper
            //    return new StringField(NAME, Uid.encodeId(id), Field.Store.YES);
            default -> throw new AssertionError("unknown: " + type);
        };
    }
}
