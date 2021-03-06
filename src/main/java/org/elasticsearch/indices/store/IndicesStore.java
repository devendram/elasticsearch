/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.store;

import org.apache.lucene.store.StoreRateLimiting;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.threadpool.ThreadPool;

/**
 *
 */
public class IndicesStore extends AbstractComponent implements ClusterStateListener {

    static {
        MetaData.addDynamicSettings(
                "indices.store.throttle.type",
                "indices.store.throttle.max_bytes_per_sec"
        );
    }

    class ApplySettings implements NodeSettingsService.Listener {
        @Override
        public void onRefreshSettings(Settings settings) {
            String rateLimitingType = settings.get("indices.store.throttle.type", IndicesStore.this.rateLimitingType);
            // try and parse the type
            StoreRateLimiting.Type.fromString(rateLimitingType);
            if (!rateLimitingType.equals(IndicesStore.this.rateLimitingType)) {
                logger.info("updating indices.store.throttle.type from [{}] to [{}]", IndicesStore.this.rateLimitingType, rateLimitingType);
                IndicesStore.this.rateLimitingType = rateLimitingType;
                IndicesStore.this.rateLimiting.setType(rateLimitingType);
            }

            ByteSizeValue rateLimitingThrottle = settings.getAsBytesSize("indices.store.throttle.max_bytes_per_sec", IndicesStore.this.rateLimitingThrottle);
            if (!rateLimitingThrottle.equals(IndicesStore.this.rateLimitingThrottle)) {
                logger.info("updating indices.store.throttle.max_bytes_per_sec from [{}] to [{}], note, type is [{}]", IndicesStore.this.rateLimitingThrottle, rateLimitingThrottle, IndicesStore.this.rateLimitingType);
                IndicesStore.this.rateLimitingThrottle = rateLimitingThrottle;
                IndicesStore.this.rateLimiting.setMaxRate(rateLimitingThrottle);
            }
        }
    }


    private final NodeEnvironment nodeEnv;

    private final NodeSettingsService nodeSettingsService;

    private final IndicesService indicesService;

    private final ClusterService clusterService;

    private volatile String rateLimitingType;
    private volatile ByteSizeValue rateLimitingThrottle;
    private final StoreRateLimiting rateLimiting = new StoreRateLimiting();

    private final ApplySettings applySettings = new ApplySettings();

    @Inject
    public IndicesStore(Settings settings, NodeEnvironment nodeEnv, NodeSettingsService nodeSettingsService, IndicesService indicesService, ClusterService clusterService, ThreadPool threadPool) {
        super(settings);
        this.nodeEnv = nodeEnv;
        this.nodeSettingsService = nodeSettingsService;
        this.indicesService = indicesService;
        this.clusterService = clusterService;

        this.rateLimitingType = componentSettings.get("throttle.type", "none");
        rateLimiting.setType(rateLimitingType);
        this.rateLimitingThrottle = componentSettings.getAsBytesSize("throttle.max_bytes_per_sec", new ByteSizeValue(0));
        rateLimiting.setMaxRate(rateLimitingThrottle);

        logger.debug("using indices.store.throttle.type [{}], with index.store.throttle.max_bytes_per_sec [{}]", rateLimitingType, rateLimitingThrottle);

        nodeSettingsService.addListener(applySettings);
        clusterService.addLast(this);
    }

    public StoreRateLimiting rateLimiting() {
        return this.rateLimiting;
    }

    public void close() {
        nodeSettingsService.removeListener(applySettings);
        clusterService.remove(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (!event.routingTableChanged()) {
            return;
        }

        if (event.state().blocks().disableStatePersistence()) {
            return;
        }

        for (IndexRoutingTable indexRoutingTable : event.state().routingTable()) {
            // Note, closed indices will not have any routing information, so won't be deleted
            for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                ShardId shardId = indexShardRoutingTable.shardId();
                // a shard can be deleted if all its copies are active, and its not allocated on this node
                boolean shardCanBeDeleted = true;
                if (indexShardRoutingTable.size() == 0) {
                    // should not really happen, there should always be at least 1 (primary) shard in a
                    // shard replication group, in any case, protected from deleting something by mistake
                    shardCanBeDeleted = false;
                } else {
                    for (ShardRouting shardRouting : indexShardRoutingTable) {
                        // be conservative here, check on started, not even active
                        if (!shardRouting.started()) {
                            shardCanBeDeleted = false;
                            break;
                        }
                        String localNodeId = clusterService.localNode().id();
                        // check if shard is active on the current node or is getting relocated to the our node
                        if (localNodeId.equals(shardRouting.currentNodeId()) || localNodeId.equals(shardRouting.relocatingNodeId())) {
                            // shard will be used locally - keep it
                            shardCanBeDeleted = false;
                            break;
                        }
                    }
                }
                if (shardCanBeDeleted) {
                    IndexService indexService = indicesService.indexService(indexRoutingTable.index());
                    if (indexService == null) {
                        // not physical allocation of the index, delete it from the file system if applicable
                        if (nodeEnv.hasNodeFile()) {
                            logger.debug("[{}][{}] deleting shard that is no longer used", shardId.index().name(), shardId.id());
                            FileSystemUtils.deleteRecursively(nodeEnv.shardLocations(shardId));
                        }
                    } else {
                        if (!indexService.hasShard(shardId.id())) {
                            if (indexService.store().canDeleteUnallocated(shardId)) {
                                logger.debug("[{}][{}] deleting shard that is no longer used", shardId.index().name(), shardId.id());
                                try {
                                    indexService.store().deleteUnallocated(indexShardRoutingTable.shardId());
                                } catch (Exception e) {
                                    logger.debug("[{}][{}] failed to delete unallocated shard, ignoring", e, indexShardRoutingTable.shardId().index().name(), indexShardRoutingTable.shardId().id());
                                }
                            }
                        } else {
                            // this state is weird, should we log?
                            // basically, it means that the shard is not allocated on this node using the routing
                            // but its still physically exists on an IndexService
                            // Note, this listener should run after IndicesClusterStateService...
                        }
                    }
                }
            }
        }
    }
}
