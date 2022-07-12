/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.analysis.nori;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanReadingFormFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.sp.api.analysis.TokenFilterFactory;

public class NoriReadingFormFilterFactory /*extends AbstractTokenFilterFactory*/ implements TokenFilterFactory {
    private String name;

    public NoriReadingFormFilterFactory() {}

    public NoriReadingFormFilterFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        // super(name, settings);
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        return new KoreanReadingFormFilter(tokenStream);
    }
}
