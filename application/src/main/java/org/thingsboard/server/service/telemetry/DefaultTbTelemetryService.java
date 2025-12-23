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
package org.thingsboard.server.service.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.*;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTbTelemetryService implements TbTelemetryService {

    private final TimeseriesService tsService;
    private final AccessValidator accessValidator;

    @Override
    public ListenableFuture<List<TsKvEntry>> getTimeseries(EntityId entityId, List<String> keys, Long startTs, Long endTs, IntervalType intervalType,
                                                           Long interval, String timeZone, Integer limit, Aggregation agg, String orderBy,
                                                           Boolean useStrictDataTypes, SecurityUser currentUser) {
        SettableFuture<List<TsKvEntry>> future = SettableFuture.create();
        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                try {
                    AggregationParams params;
                    if (Aggregation.NONE.equals(agg)) {
                        params = AggregationParams.none();
                    } else if (intervalType == null || IntervalType.MILLISECONDS.equals(intervalType)) {
                        params = interval == 0L ? AggregationParams.none() : AggregationParams.milliseconds(agg, interval);
                    } else {
                        params = AggregationParams.calendar(agg, intervalType, timeZone);
                    }
                    List<ReadTsKvQuery> queries = keys.stream().map(key -> new BaseReadTsKvQuery(key, startTs, endTs, params, limit, orderBy)).collect(Collectors.toList());
                    Futures.addCallback(tsService.findAll(currentUser.getTenantId(), entityId, queries), new FutureCallback<>() {
                        @Override
                        public void onSuccess(List<TsKvEntry> result) {
                            future.set(result);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            future.setException(t);
                        }
                    }, MoreExecutors.directExecutor());
                } catch (Throwable e) {
                    onFailure(e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });
        return future;
    }

    @Override
    public ListenableFuture<Map<String, List<FormattedTsData>>> getTimeseriesFirstValue(EntityId entityId, List<String> keys, Long startTs,
                                                                     Long endTs, Long interval, Boolean useStrictDataTypes,
                                                                     SecurityUser currentUser) {
        ListenableFuture<Map<String, List<FormattedTsData>>> timeseriesFirstValueQuery = null;
        // 临界值：60000ms
        if (interval == 60000) {
            //单次查询，内存分桶  预估扫描86,400条记录
            timeseriesFirstValueQuery = getTimeseriesFirstValueSingleQuery(entityId, keys, startTs, endTs, interval, useStrictDataTypes, currentUser);
        } else {
            //批量limit 1查询，减轻带宽压力。15分钟时 96条记录
            timeseriesFirstValueQuery =  getTimeseriesFirstValueBatchQuery(entityId, keys, startTs, endTs, interval, useStrictDataTypes, currentUser);
        }
        return timeseriesFirstValueQuery;

    }

    private ListenableFuture<Map<String, List<FormattedTsData>>> getTimeseriesFirstValueSingleQuery(EntityId entityId, List<String> keys,
                                                                                 Long startTs, Long endTs, Long interval,
                                                                                 Boolean useStrictDataTypes, SecurityUser currentUser) {
        SettableFuture<Map<String, List<FormattedTsData>>> future = SettableFuture.create();

        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                try {
                    // 使用NONE聚合类型查询所有原始数据
                    List<ReadTsKvQuery> queries = keys.stream()
                            .map(key -> new BaseReadTsKvQuery(key, startTs, endTs, AggregationParams.none(), 86400, "ASC"))
                            .collect(Collectors.toList());

                    Futures.addCallback(tsService.findAll(currentUser.getTenantId(), entityId, queries),
                            new FutureCallback<List<TsKvEntry>>() {
                                @Override
                                public void onSuccess(List<TsKvEntry> allData) {
                                    // 内存分桶处理，返回每个间隔的第一个值
                                    Map<String, List<FormattedTsData>> firstValues = extractFirstValuesFromBuckets(allData, startTs, endTs, interval, useStrictDataTypes);
                                    future.set(firstValues);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    future.setException(t);
                                }
                            }, MoreExecutors.directExecutor());
                } catch (Throwable e) {
                    future.setException(e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });

        return future;
    }

    private ListenableFuture<Map<String, List<FormattedTsData>>> getTimeseriesFirstValueBatchQuery(EntityId entityId, List<String> keys,
                                                                                Long startTs, Long endTs, Long interval,
                                                                                Boolean useStrictDataTypes, SecurityUser currentUser) {
        SettableFuture<Map<String, List<FormattedTsData>>> future = SettableFuture.create();

        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                try {
                    // 计算bucket数量
                    long bucketCount = (endTs - startTs) / interval + 1;

                    // 为每个key和每个bucket创建一个查询
                    List<ListenableFuture<List<TsKvEntry>>> futures = new ArrayList<>();

                    for (String key : keys) {
                        for (int i = 0; i < bucketCount; i++) {
                            long bucketStart = startTs + i * interval;
                            long bucketEnd = Math.min(bucketStart + interval, endTs);

                            // 查询每个bucket的第一个值（limit=1）
                            ReadTsKvQuery query = new BaseReadTsKvQuery(key, bucketStart, bucketEnd, 0, 1, Aggregation.NONE, "ASC");
                            futures.add(tsService.findAll(currentUser.getTenantId(), entityId, List.of(query)));
                        }
                    }

                    // 合并所有结果
                    Futures.addCallback(Futures.allAsList(futures),
                            new FutureCallback<>() {
                                @Override
                                public void onSuccess(List<List<TsKvEntry>> results) {
                                    Map<String, List<FormattedTsData>> keys2ListData = new HashMap<>();
                                    for (List<TsKvEntry> bucketData : results) {
                                        if (!bucketData.isEmpty()) {
                                            TsKvEntry entry = bucketData.get(0);
                                            long timestamp = entry.getTs();
                                            //计算所属的bucket
                                            long bucketStartTs = (timestamp - startTs) / interval * interval + startTs;
                                            Object value = useStrictDataTypes ? getKvValue(entry) : entry.getValueAsString();
                                            keys2ListData.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                                            .add(new FormattedTsData(bucketStartTs, timestamp, value));
                                        }
                                    }

                                    future.set(keys2ListData);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    future.setException(t);
                                }
                            }, MoreExecutors.directExecutor());

                } catch (Throwable e) {
                    future.setException(e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });

        return future;
    }

    /**
     * 提取每个interval内的第一条记录
     *
     * @param allData
     * @param startTs
     * @param endTs
     * @param interval
     * @param useStrictDataTypes
     * @return
     */
    private Map<String, List<FormattedTsData>> extractFirstValuesFromBuckets(List<TsKvEntry> allData, Long startTs, Long endTs, Long interval, Boolean useStrictDataTypes) {
        Map<String, List<FormattedTsData>> result = new HashMap<>();
        Map<String, Set<Long>> bucketFirstSeen = new java.util.HashMap<>();

        for (TsKvEntry entry : allData) {
            long timestamp = entry.getTs();

            // 计算所属的bucket
            long bucketStart = (timestamp - startTs) / interval * interval + startTs;

            String key = entry.getKey();
            Set<Long> seenBuckets = bucketFirstSeen.computeIfAbsent(key, k -> new java.util.HashSet<>());

            // 如果这个bucket还没找到第一个值，添加它
            if (!seenBuckets.contains(bucketStart)) {
                Object value = useStrictDataTypes ? getKvValue(entry) : entry.getValueAsString();
                result.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new FormattedTsData(bucketStart, timestamp, value));

                seenBuckets.add(bucketStart);
            }
        }

        return result;
    }
    private Object getKvValue(KvEntry entry) {
        if (entry.getDataType() == DataType.JSON) {
            return toJsonNode(entry.getJsonValue().get());
        }
        return entry.getValue();
    }

    private JsonNode toJsonNode(String value) {
        try {
            return JacksonUtil.toJsonNode(value);
        } catch (IllegalArgumentException e) {
            throw new JsonParseException("Can't parse jsonValue: " + value, e);
        }
    }

}
