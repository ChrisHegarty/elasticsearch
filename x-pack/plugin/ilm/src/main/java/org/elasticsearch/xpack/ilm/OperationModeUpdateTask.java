/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ilm;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.ack.AckedRequest;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.Priority;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.OperationMode;
import org.elasticsearch.xpack.core.slm.SnapshotLifecycleMetadata;

import java.util.Objects;

/**
 * This task updates the operation mode state for ILM.
 *
 * As stopping ILM proved to be an action we want to sometimes take in order to allow clusters to stabilise when under heavy load this
 * task might run at {@link Priority#IMMEDIATE} priority so please make sure to keep this task as lightweight as possible.
 */
public class OperationModeUpdateTask extends ClusterStateUpdateTask {
    private static final Logger logger = LogManager.getLogger(OperationModeUpdateTask.class);
    @Nullable
    private final OperationMode ilmMode;
    @Nullable
    private final OperationMode slmMode;

    public static AckedClusterStateUpdateTask wrap(
        OperationModeUpdateTask task,
        AckedRequest request,
        ActionListener<AcknowledgedResponse> listener
    ) {
        return new AckedClusterStateUpdateTask(task.priority(), request, listener) {
            @Override
            public ClusterState execute(ClusterState currentState) {
                return task.execute(currentState);
            }
        };
    }

    private OperationModeUpdateTask(Priority priority, OperationMode ilmMode, OperationMode slmMode) {
        super(priority);
        this.ilmMode = ilmMode;
        this.slmMode = slmMode;
    }

    public static OperationModeUpdateTask ilmMode(OperationMode mode) {
        return new OperationModeUpdateTask(getPriority(mode), mode, null);
    }

    public static OperationModeUpdateTask slmMode(OperationMode mode) {
        return new OperationModeUpdateTask(getPriority(mode), null, mode);
    }

    private static Priority getPriority(OperationMode mode) {
        if (mode == OperationMode.STOPPED || mode == OperationMode.STOPPING) {
            return Priority.IMMEDIATE;
        } else {
            return Priority.NORMAL;
        }
    }

    OperationMode getILMOperationMode() {
        return ilmMode;
    }

    OperationMode getSLMOperationMode() {
        return slmMode;
    }

    @Override
    public ClusterState execute(ClusterState currentState) {
        ClusterState newState = currentState;
        newState = updateILMState(newState);
        newState = updateSLMState(newState);
        return newState;
    }

    private ClusterState updateILMState(final ClusterState currentState) {
        if (ilmMode == null) {
            return currentState;
        }
        IndexLifecycleMetadata currentMetadata = currentState.metadata().custom(IndexLifecycleMetadata.TYPE);
        if (currentMetadata != null && currentMetadata.getOperationMode().isValidChange(ilmMode) == false) {
            return currentState;
        } else if (currentMetadata == null) {
            currentMetadata = IndexLifecycleMetadata.EMPTY;
        }

        final OperationMode newMode;
        if (currentMetadata.getOperationMode().isValidChange(ilmMode)) {
            newMode = ilmMode;
        } else {
            newMode = currentMetadata.getOperationMode();
        }

        if (newMode.equals(ilmMode) == false) {
            logger.info("updating ILM operation mode to {}", newMode);
        }
        return ClusterState.builder(currentState)
            .metadata(
                Metadata.builder(currentState.metadata())
                    .putCustom(IndexLifecycleMetadata.TYPE, new IndexLifecycleMetadata(currentMetadata.getPolicyMetadatas(), newMode))
            )
            .build();
    }

    private ClusterState updateSLMState(final ClusterState currentState) {
        if (slmMode == null) {
            return currentState;
        }
        SnapshotLifecycleMetadata currentMetadata = currentState.metadata().custom(SnapshotLifecycleMetadata.TYPE);
        if (currentMetadata != null && currentMetadata.getOperationMode().isValidChange(slmMode) == false) {
            return currentState;
        } else if (currentMetadata == null) {
            currentMetadata = SnapshotLifecycleMetadata.EMPTY;
        }

        final OperationMode newMode;
        if (currentMetadata.getOperationMode().isValidChange(slmMode)) {
            newMode = slmMode;
        } else {
            newMode = currentMetadata.getOperationMode();
        }

        if (newMode.equals(slmMode) == false) {
            logger.info("updating SLM operation mode to {}", newMode);
        }
        return ClusterState.builder(currentState)
            .metadata(
                Metadata.builder(currentState.metadata())
                    .putCustom(
                        SnapshotLifecycleMetadata.TYPE,
                        new SnapshotLifecycleMetadata(currentMetadata.getSnapshotConfigurations(), newMode, currentMetadata.getStats())
                    )
            )
            .build();
    }

    @Override
    public void onFailure(Exception e) {
        logger.error("unable to update lifecycle metadata with new ilm mode [" + ilmMode + "], slm mode [" + slmMode + "]", e);
    }

    @Override
    public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
        if (ilmMode != null) {
            logger.info("ILM operation mode updated to {}", ilmMode);
        }
        if (slmMode != null) {
            logger.info("SLM operation mode updated to {}", slmMode);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), ilmMode, slmMode);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        OperationModeUpdateTask other = (OperationModeUpdateTask) obj;
        return Objects.equals(priority(), other.priority())
            && Objects.equals(ilmMode, other.ilmMode)
            && Objects.equals(slmMode, other.slmMode);
    }
}
