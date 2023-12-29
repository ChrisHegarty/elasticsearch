/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.common.io.stream.NotSerializableExceptionWrapper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.core.async.DeleteAsyncResultAction;
import org.elasticsearch.xpack.core.async.DeleteAsyncResultRequest;
import org.elasticsearch.xpack.core.async.GetAsyncResultRequest;
import org.elasticsearch.xpack.esql.analysis.VerificationException;
import org.elasticsearch.xpack.esql.parser.ParsingException;
import org.elasticsearch.xpack.esql.plugin.QueryPragmas;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.core.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 * Runs test scenarios from EsqlActionIT, with an extra level of indirection
 * through the async query and async get APIs.
 */
@LuceneTestCase.AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/103748")
public class EsqlAsyncActionIT extends EsqlActionIT {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        ArrayList<Class<? extends Plugin>> actions = new ArrayList<>(super.nodePlugins());
        actions.add(LocalStateEsqlAsync.class);
        return Collections.unmodifiableList(actions);
    }

    @Override
    protected EsqlQueryResponse run(String esqlCommands, QueryPragmas pragmas, QueryBuilder filter) {
        EsqlQueryRequest request = new EsqlQueryRequest();
        request.query(esqlCommands);
        request.pragmas(pragmas);
        request.async(true);
        // deliberately small timeout, to frequently trigger incomplete response
        request.waitForCompletionTimeout(TimeValue.timeValueNanos(1));
        request.keepOnCompletion(randomBoolean());
        if (filter != null) {
            request.filter(filter);
        }

        var response = run(request);
        if (response.asyncExecutionId().isPresent()) {
            String id = response.asyncExecutionId().get();
            assertThat(response.isRunning(), is(true));
            assertThat(response.columns(), is(empty())); // no partial results
            assertThat(response.pages(), is(empty()));
            response.close();
            var getResponse = getAsyncResponse(id);
            assertDeletable(id);
            return getResponse;
        } else {
            return response;
        }
    }

    void assertDeletable(String id) {
        var resp = deleteAsyncId(id);
        assertTrue(resp.isAcknowledged());
        // the stored response should no longer be retrievable
        var e = expectThrows(ResourceNotFoundException.class, () -> getAsyncResponse(id));
        assertThat(e.getMessage(), equalTo(id));
    }

    EsqlQueryResponse getAsyncResponse(String id) {
        try {
            var getResultsRequest = new GetAsyncResultRequest(id).setWaitForCompletionTimeout(timeValueSeconds(60));
            return client().execute(EsqlAsyncGetResultAction.INSTANCE, getResultsRequest).actionGet(30, TimeUnit.SECONDS);
        } catch (ElasticsearchTimeoutException e) {
            throw new AssertionError("timeout", e);
        }
    }

    AcknowledgedResponse deleteAsyncId(String id) {
        try {
            DeleteAsyncResultRequest request = new DeleteAsyncResultRequest(id);
            return client().execute(DeleteAsyncResultAction.INSTANCE, request).actionGet(30, TimeUnit.SECONDS);
        } catch (ElasticsearchTimeoutException e) {
            throw new AssertionError("timeout", e);
        }
    }

    // Overridden to allow for not-serializable wrapper.
    @Override
    protected Exception assertVerificationException(String esqlCommand) {
        var e = expectThrowsAnyOf(List.of(NotSerializableExceptionWrapper.class, VerificationException.class), () -> run(esqlCommand));
        if (e instanceof NotSerializableExceptionWrapper wrapper) {
            assertThat(wrapper.unwrapCause().getMessage(), containsString("verification_exception"));
        }
        return e;
    }

    // Overridden to allow for not-serializable wrapper.
    @Override
    protected Exception assertParsingException(String esqlCommand) {
        var e = expectThrowsAnyOf(List.of(NotSerializableExceptionWrapper.class, ParsingException.class), () -> run(esqlCommand));
        if (e instanceof NotSerializableExceptionWrapper wrapper) {
            assertThat(wrapper.unwrapCause().getMessage(), containsString("parsing_exception"));
        }
        return e;
    }

    public static class LocalStateEsqlAsync extends LocalStateCompositeXPackPlugin {
        public LocalStateEsqlAsync(final Settings settings, final Path configPath) {
            super(settings, configPath);
        }
    }
}
