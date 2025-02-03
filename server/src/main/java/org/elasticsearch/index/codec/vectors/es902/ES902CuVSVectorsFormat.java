/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.es902;

import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsFormat;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.util.Bits;

import java.io.IOException;

import static org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.MAX_DIMS_COUNT;

// TODO plumb index name into this
public class ES902CuVSVectorsFormat extends KnnVectorsFormat {

    static final String NAME = "ES902CuVSVectorsFormat";

    private static final KnnVectorsFormat format = new CuVSVectorsFormat();

    public ES902CuVSVectorsFormat() {
        super(NAME);
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return new ES902CuVSVectorWriter(format.fieldsWriter(state));
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return new ES902CuVSVectorReader(format.fieldsReader(state));
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return MAX_DIMS_COUNT;
    }

    static class ES902CuVSVectorWriter extends KnnVectorsWriter {
        private final KnnVectorsWriter writer;

        ES902CuVSVectorWriter(KnnVectorsWriter writer) {
            this.writer = writer;
        }

        @Override
        public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
            return writer.addField(fieldInfo);
        }

        @Override
        public void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
            writer.flush(maxDoc, sortMap);
        }

        @Override
        public void finish() throws IOException {
            writer.finish();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }

        @Override
        public long ramBytesUsed() {
            return writer.ramBytesUsed();
        }

        @Override
        public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
            writer.mergeOneField(fieldInfo, mergeState);
        }
    }

    static final class ES902CuVSVectorReader extends KnnVectorsReader {

        final KnnVectorsReader delegate;

        ES902CuVSVectorReader(KnnVectorsReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkIntegrity() throws IOException {
            delegate.checkIntegrity();
        }

        @Override
        public FloatVectorValues getFloatVectorValues(String field) throws IOException {
            return delegate.getFloatVectorValues(field);
        }

        @Override
        public ByteVectorValues getByteVectorValues(String field) throws IOException {
            return delegate.getByteVectorValues(field);
        }

        @Override
        public void search(String s, float[] floats, KnnCollector knnCollector, Bits bits) throws IOException {
            delegate.search(s, floats, knnCollector, bits);
        }

        @Override
        public void search(String s, byte[] bytes, KnnCollector knnCollector, Bits bits) throws IOException {
            delegate.search(s, bytes, knnCollector, bits);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
