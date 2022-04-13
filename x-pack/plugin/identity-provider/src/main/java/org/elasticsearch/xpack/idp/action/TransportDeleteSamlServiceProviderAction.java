/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.idp.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.iterable.Iterables;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.idp.saml.sp.SamlServiceProviderDocument;
import org.elasticsearch.xpack.idp.saml.sp.SamlServiceProviderIndex;

import java.util.stream.Collectors;

/**
 * Transport action to remove a service provider from the IdP
 */
public class TransportDeleteSamlServiceProviderAction extends HandledTransportAction<
    DeleteSamlServiceProviderRequest,
    DeleteSamlServiceProviderResponse> {

    private final Logger logger = LogManager.getLogger(TransportDeleteSamlServiceProviderAction.class);
    private final SamlServiceProviderIndex index;

    @Inject
    public TransportDeleteSamlServiceProviderAction(
        TransportService transportService,
        ActionFilters actionFilters,
        SamlServiceProviderIndex index
    ) {
        super(DeleteSamlServiceProviderAction.NAME, transportService, actionFilters, DeleteSamlServiceProviderRequest::new);
        this.index = index;
    }

    @Override
    protected void doExecute(
        Task task,
        final DeleteSamlServiceProviderRequest request,
        final ActionListener<DeleteSamlServiceProviderResponse> listener
    ) {
        final String entityId = request.getEntityId();
        index.findByEntityId(entityId, ActionListener.wrap(matchingDocuments -> {
            if (matchingDocuments.isEmpty()) {
                listener.onResponse(new DeleteSamlServiceProviderResponse(null, entityId));
            } else if (matchingDocuments.size() == 1) {
                final SamlServiceProviderIndex.DocumentSupplier docInfo = Iterables.get(matchingDocuments, 0);
                final SamlServiceProviderDocument existingDoc = docInfo.getDocument();
                assert existingDoc.docId != null : "Loaded document with no doc id";
                assert existingDoc.entityId.equals(entityId) : "Loaded document with non-matching entity-id";
                logger.info("Deleting Service Provider [{}]", existingDoc);
                index.deleteDocument(
                    docInfo.version,
                    request.getRefreshPolicy(),
                    ActionListener.wrap(
                        deleteResponse -> listener.onResponse(new DeleteSamlServiceProviderResponse(deleteResponse, entityId)),
                        listener::onFailure
                    )
                );
            } else {
                logger.warn(
                    "Found multiple existing service providers in [{}] with entity id [{}] - [{}]",
                    index,
                    entityId,
                    matchingDocuments.stream().map(d -> d.getDocument().docId).collect(Collectors.joining(","))
                );
                listener.onFailure(new IllegalStateException("Multiple service providers exist with entity id [" + entityId + "]"));
            }
        }, listener::onFailure));
    }
}
