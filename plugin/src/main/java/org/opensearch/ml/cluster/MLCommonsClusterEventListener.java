/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.cluster;

import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_MONITORING_REQUEST_COUNT;

import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.ml.model.MLModelCacheHelper;
import org.opensearch.ml.model.MLModelManager;
import org.opensearch.ml.task.MLTaskManager;

@Log4j2
public class MLCommonsClusterEventListener implements ClusterStateListener {

    private final ClusterService clusterService;
    private final MLModelManager mlModelManager;
    private final MLTaskManager mlTaskManager;
    private final MLModelCacheHelper modelCacheHelper;

    public MLCommonsClusterEventListener(
        ClusterService clusterService,
        MLModelManager mlModelManager,
        MLTaskManager mlTaskManager,
        MLModelCacheHelper modelCacheHelper
    ) {
        this.clusterService = clusterService;
        this.clusterService.addListener(this);
        this.mlModelManager = mlModelManager;
        this.mlTaskManager = mlTaskManager;
        this.modelCacheHelper = modelCacheHelper;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        ClusterState previousState = event.previousState();
        ClusterState state = event.state();
        Settings previousSettings = previousState.getMetadata().settings();
        Settings settings = state.getMetadata().settings();
        long previousMonitoringReqCount = ML_COMMONS_MONITORING_REQUEST_COUNT.get(previousSettings);
        long monitoringReqCount = ML_COMMONS_MONITORING_REQUEST_COUNT.get(settings);
        if (previousMonitoringReqCount > monitoringReqCount) {
            modelCacheHelper.resizeMonitoringQueue(monitoringReqCount);
        }
        DiscoveryNodes.Delta delta = event.nodesDelta();
        if (delta.removed()) {
            Set<String> removedNodeIds = delta.removedNodes().stream().map(DiscoveryNode::getId).collect(Collectors.toSet());
            mlModelManager.removeWorkerNodes(removedNodeIds);
        }
    }
}