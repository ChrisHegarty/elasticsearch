/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.watcher.execution;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.logging.Message;
import org.elasticsearch.xpack.core.watcher.trigger.TriggerEvent;

import java.util.function.Consumer;

import static java.util.stream.StreamSupport.stream;

public class AsyncTriggerEventConsumer implements Consumer<Iterable<TriggerEvent>> {
    private static final Logger logger = LogManager.getLogger(AsyncTriggerEventConsumer.class);
    private final ExecutionService executionService;

    public AsyncTriggerEventConsumer(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public void accept(Iterable<TriggerEvent> events) {
        try {
            executionService.processEventsAsync(events);
        } catch (Exception e) {
            logger.error(
                Message.createParameterizedMessage(
                    "failed to process triggered events [{}]",
                    (Object) stream(events.spliterator(), false).toArray(TriggerEvent[]::new)
                ),
                e
            );
        }
    }
}
