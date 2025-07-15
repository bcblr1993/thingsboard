/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.telemetry;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.TenantProfile;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.tenant.profile.DefaultTenantProfileConfiguration;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "fetch redis latest timeseries",
        configClazz = TbMsgTimeseriesLatestFetchRedisNodeConfiguration.class,
        nodeDescription = "Fetch  redis latest timeseries data",
        nodeDetails = "Saves the latest telemetry data. Expects messages with 'POST_TELEMETRY_REQUEST' message type. " +
                "Timestamp in milliseconds will be taken from metadata.ts, otherwise 'now' message timestamp will be applied. " +
                "<br/>" +
                "Enable 'useServerTs' param to use the timestamp of the message processing instead of the timestamp from the message. " +
                "Useful for all sorts of sequential processing if you merge messages from multiple sources (devices, assets, etc).\n" +
                "<br/>" +
                "In the case of sequential processing, the platform guarantees that the messages are processed in the order of their submission to the queue. " +
                "However, the timestamp of the messages originated by multiple devices/servers may be unsynchronized long before they are pushed to the queue. " +
                "The DB layer has certain optimizations to ignore the updates of the \"attributes\" and \"latest values\" tables if the new record has a timestamp that is older than the previous record. " +
                "So, to make sure that all the messages will be processed correctly, one should enable this parameter for sequential message processing scenarios.",
        configDirective = "tbActionNodeTimeseriesFetchLatestRedisConfig",
        icon = "search"
)
public class TbMsgTimeseriesLatestFetchRedisNode implements TbNode {

    private TbMsgTimeseriesLatestFetchRedisNodeConfiguration config;
    private TbContext ctx;
    private final Gson gson = new Gson();


    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbMsgTimeseriesLatestFetchRedisNodeConfiguration.class);
        this.ctx = ctx;
        ctx.addTenantProfileListener(this::onTenantProfileUpdate);
        onTenantProfileUpdate(ctx.getTenantProfile());
    }

    void onTenantProfileUpdate(TenantProfile tenantProfile) {
        DefaultTenantProfileConfiguration configuration = (DefaultTenantProfileConfiguration) tenantProfile.getProfileData().getConfiguration();
    }

    /**
     * 返回格式：
     * {
     *     "entityId": [{
     *             "ts": 1742192922000,
     *             "kv": {
     *                 "value": 744.0,
     *                 "key": "标签213"
     *             }
     *         }
     *     ]
     * }
     */
    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        long startTime = System.currentTimeMillis();

        //实体ID
        String[] entityIdList = config.getEntityId().split(",");
        //遍历实体ID查询最新消息
        List<ListenableFuture<Pair<String, List<TsKvEntry>>>> futures = new ArrayList<>();
        String entityType = config.getEntityType();
        for (String entityIdStr : entityIdList) {
            EntityId entityId = EntityIdFactory.getByTypeAndId(entityType, entityIdStr);
            ListenableFuture<List<TsKvEntry>> future;
            if (config.isFetchAllKeys()) {
                //查询所有属性
                future = ctx.getTimeseriesService().findAllLatest(ctx.getTenantId(), entityId);
            } else {
                //实体属性key
                List<String> keys  = config.getEntityTypeKeys();
                //查询配置的属性
                future = ctx.getTimeseriesService().findLatest(ctx.getTenantId(), entityId, keys);
            }
            futures.add(Futures.transform(future, result -> Pair.of(entityIdStr, result), MoreExecutors.directExecutor()));
        }
        ListenableFuture<List<Pair<String, List<TsKvEntry>>>> allFutures = Futures.allAsList(futures);
        allFutures.addListener(() -> {
            try {
                List<Pair<String, List<TsKvEntry>>> results = allFutures.get();
                Map<String, List<TsKvEntry>> resultMap = new HashMap<>();
                for (Pair<String, List<TsKvEntry>> pair : results) {
                    resultMap.put(pair.getFirst(), pair.getSecond());
                }
                TbMsgMetaData metaData = msg.getMetaData().copy();
                metaData.putValue("type","latest_redis_telemetry");
                metaData.putValue("time",getCurrentFormattedTime());
                long elapsedTime = System.currentTimeMillis() - startTime;
                metaData.putValue("elapsed_time_ms", String.valueOf(elapsedTime));
                
                TbMsg newMsg = TbMsg.newMsg().data(gson.toJson(resultMap))
                        .id(msg.getId())
                        .metaData(metaData)
                        .type(TbMsgType.POST_TELEMETRY_REQUEST)
                        .originator(msg.getOriginator())
                        .ruleNodeId(msg.getRuleNodeId())
                        .ruleChainId(msg.getRuleChainId())
                        .queueName(msg.getQueueName())
                        .dataType(TbMsgDataType.JSON).build();
                ctx.tellSuccess(newMsg);
            } catch (Exception e) {
                log.error("Error fetching timeseries data", e);
                ctx.tellFailure(msg, e);
            }
        }, MoreExecutors.directExecutor());
    }
    public String getCurrentFormattedTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }

    @Override
    public void destroy() {
        ctx.removeListeners();
    }

}
