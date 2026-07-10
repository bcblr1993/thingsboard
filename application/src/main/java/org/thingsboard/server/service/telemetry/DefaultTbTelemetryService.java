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
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.*;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.exception.InvalidParametersException;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultTbTelemetryService implements TbTelemetryService {

    private static final int MAX_RAW_DATA_POINTS = 1_000_000;
    private static final int MAX_RESULT_DATA_POINTS = 1_000_000;
    private static final int MAX_KEYS = 30;
    private static final long MIN_INTERVAL = TimeUnit.SECONDS.toMillis(1);
    private static final long MAX_TIME_RANGE = TimeUnit.DAYS.toMillis(31);
    private static final String DATA_LIMIT_EXCEEDED_MESSAGE = "请求数据量过大";

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
    public ListenableFuture<List<TsKvEntry>> getTimeseriesFill(EntityId entityId, List<String> keys, Long startTs, Long endTs,
                                                               Long interval, Aggregation agg, Boolean fillMissing, String orderBy,
                                                               SecurityUser currentUser) throws ThingsboardException {
        validateTimeseriesFillRequest(keys, startTs, endTs, interval, agg, fillMissing, orderBy);
        SettableFuture<List<TsKvEntry>> future = SettableFuture.create();
        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                TimeseriesFillQueryContext context = new TimeseriesFillQueryContext(keys);
                queryNextFillKey(currentUser.getTenantId(), entityId, startTs, endTs, interval, agg,
                        fillMissing, orderBy, context, future);
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });
        return future;
    }

    private void queryNextFillKey(TenantId tenantId, EntityId entityId, long startTs, long endTs, long interval,
                                  Aggregation aggregation, boolean fillMissing, String orderBy,
                                  TimeseriesFillQueryContext context, SettableFuture<List<TsKvEntry>> future) {
        if (future.isDone()) {
            return;
        }
        if (context.keyIndex >= context.keys.size()) {
            future.set(context.result);
            return;
        }

        String key = context.keys.get(context.keyIndex);
        int queryLimit = context.remainingRawPoints + 1;
        ReadTsKvQuery dataQuery = new BaseReadTsKvQuery(key, startTs, endTs, AggregationParams.none(), queryLimit, "ASC");
        Futures.addCallback(tsService.findAll(tenantId, entityId, Collections.singletonList(dataQuery)), new FutureCallback<>() {
            @Override
            public void onSuccess(List<TsKvEntry> data) {
                List<TsKvEntry> keyData = data == null ? Collections.emptyList() : data;
                if (keyData.size() >= queryLimit) {
                    failDataLimit(future);
                    return;
                }
                context.remainingRawPoints -= keyData.size();
                if (fillMissing && startTs > 0) {
                    ReadTsKvQuery seedQuery = new BaseReadTsKvQuery(key, 0L, startTs, AggregationParams.none(), 1, "DESC");
                    Futures.addCallback(tsService.findAll(tenantId, entityId, Collections.singletonList(seedQuery)), new FutureCallback<>() {
                        @Override
                        public void onSuccess(List<TsKvEntry> seedData) {
                            TsKvEntry seed = seedData == null || seedData.isEmpty() ? null : seedData.get(0);
                            processFillKey(tenantId, entityId, startTs, endTs, interval, aggregation, true,
                                    orderBy, context, future, key, seed, keyData);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            future.setException(t);
                        }
                    }, MoreExecutors.directExecutor());
                } else {
                    processFillKey(tenantId, entityId, startTs, endTs, interval, aggregation, false,
                            orderBy, context, future, key, null, keyData);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        }, MoreExecutors.directExecutor());
    }

    private void processFillKey(TenantId tenantId, EntityId entityId, long startTs, long endTs, long interval,
                                Aggregation aggregation, boolean fillMissing, String orderBy,
                                TimeseriesFillQueryContext context, SettableFuture<List<TsKvEntry>> future,
                                String key, TsKvEntry seed, List<TsKvEntry> keyData) {
        try {
            if (!Aggregation.NONE.equals(aggregation) && !TimeseriesFillProcessor.isNumericSeries(seed, keyData)) {
                log.warn("Skipping non-numeric timeseries key [{}] for [{}] aggregation", key, aggregation);
            } else {
                List<TsKvEntry> keyResult = TimeseriesFillProcessor.processKey(key, seed, keyData, startTs, endTs,
                        interval, aggregation, fillMissing, orderBy, context.remainingResultPoints);
                context.result.addAll(keyResult);
                context.remainingResultPoints -= keyResult.size();
            }
            context.keyIndex++;
            queryNextFillKey(tenantId, entityId, startTs, endTs, interval, aggregation, fillMissing,
                    orderBy, context, future);
        } catch (TimeseriesFillProcessor.ResultLimitExceededException e) {
            failDataLimit(future);
        } catch (Throwable t) {
            future.setException(t);
        }
    }

    private void validateTimeseriesFillRequest(List<String> keys, Long startTs, Long endTs, Long interval,
                                               Aggregation aggregation, Boolean fillMissing, String orderBy) throws ThingsboardException {
        if (keys == null || keys.isEmpty()) {
            throw badRequest("keys can't be empty");
        }
        if (keys.size() > MAX_KEYS) {
            throw badRequest("keys can't be more than " + MAX_KEYS);
        }
        if (startTs == null || endTs == null || startTs < 0 || endTs <= startTs) {
            throw badRequest("endTs must be greater than startTs");
        }
        if (endTs - startTs > MAX_TIME_RANGE) {
            throw badRequest("Time range can't be more than 31 days");
        }
        if (interval == null || interval < MIN_INTERVAL) {
            throw badRequest("interval can't be less than 1000");
        }
        if (!Aggregation.NONE.equals(aggregation) && !Aggregation.AVG.equals(aggregation)
                && !Aggregation.MIN.equals(aggregation) && !Aggregation.MAX.equals(aggregation)) {
            throw badRequest("Unsupported aggregation: " + aggregation);
        }
        if (fillMissing == null) {
            throw badRequest("fillMissing must be specified");
        }
        if (!"ASC".equalsIgnoreCase(orderBy) && !"DESC".equalsIgnoreCase(orderBy)) {
            throw badRequest("Unsupported orderBy: " + orderBy);
        }
    }

    private ThingsboardException badRequest(String message) {
        return new ThingsboardException(message, ThingsboardErrorCode.BAD_REQUEST_PARAMS);
    }

    private void failDataLimit(SettableFuture<List<TsKvEntry>> future) {
        future.setException(new InvalidParametersException(DATA_LIMIT_EXCEEDED_MESSAGE));
    }

    private static final class TimeseriesFillQueryContext {
        private final List<String> keys;
        private final List<TsKvEntry> result = new ArrayList<>();
        private int keyIndex;
        private int remainingRawPoints = MAX_RAW_DATA_POINTS;
        private int remainingResultPoints = MAX_RESULT_DATA_POINTS;

        private TimeseriesFillQueryContext(List<String> keys) {
            this.keys = keys;
        }
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
        log.info("Entering getTimeseriesFirstValueSingleQuery - entityId: {}, keys: {}, startTs: {}, endTs: {}, interval: {}, useStrictDataTypes: {}",
                entityId, keys, startTs, endTs, interval, useStrictDataTypes);

        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                try {
                    // 使用NONE聚合类型查询所有原始数据
                    List<ReadTsKvQuery> queries = keys.stream()
                            .map(key -> new BaseReadTsKvQuery(key, startTs, endTs, AggregationParams.none(), 86400, "ASC"))
                            .collect(Collectors.toList());
                    log.debug("Created queries for single query approach - query count: {}, limit per query: {}", queries.size(), 86400);

                    Futures.addCallback(tsService.findAll(currentUser.getTenantId(), entityId, queries),
                            new FutureCallback<List<TsKvEntry>>() {
                                @Override
                                public void onSuccess(List<TsKvEntry> allData) {
                                    log.info("Single query returned data - record count: {}, key count: {}", allData.size(),
                                            allData.stream().map(TsKvEntry::getKey).distinct().count());
                                    // 内存分桶处理，返回每个间隔的第一个值
                                    Map<String, List<FormattedTsData>> firstValues = extractFirstValuesFromBuckets(allData, startTs, endTs, interval, useStrictDataTypes);
                                    log.info("Extracted first values from buckets - result key count: {}, total data points: {}",
                                            firstValues.size(), firstValues.values().stream().mapToInt(List::size).sum());
                                    future.set(firstValues);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    log.error("Single query failed for entityId: {}, error: {}", entityId, t.getMessage(), t);
                                    future.setException(t);
                                }
                            }, MoreExecutors.directExecutor());
                } catch (Throwable e) {
                    log.error("Exception in getTimeseriesFirstValueSingleQuery for entityId: {}, error: {}", entityId, e.getMessage(), e);
                    future.setException(e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Access validation failed in getTimeseriesFirstValueSingleQuery for entityId: {}, error: {}", entityId, t.getMessage(), t);
                future.setException(t);
            }
        });

        return future;
    }

    private ListenableFuture<Map<String, List<FormattedTsData>>> getTimeseriesFirstValueBatchQuery(EntityId entityId, List<String> keys,
                                                                                Long startTs, Long endTs, Long interval,
                                                                                Boolean useStrictDataTypes, SecurityUser currentUser) {
        SettableFuture<Map<String, List<FormattedTsData>>> future = SettableFuture.create();
        log.info("Entering getTimeseriesFirstValueBatchQuery - entityId: {}, keys: {}, startTs: {}, endTs: {}, interval: {}, useStrictDataTypes: {}",
                entityId, keys, startTs, endTs, interval, useStrictDataTypes);

        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                try {
                    // 计算bucket数量
                    long bucketCount = (endTs - startTs) / interval + 1;
                    log.debug("Calculated bucket count - startTs: {}, endTs: {}, interval: {}, bucketCount: {}", startTs, endTs, interval, bucketCount);

                    // 为每个key和每个bucket创建一个查询
                    List<ListenableFuture<List<TsKvEntry>>> futures = new ArrayList<>();
                    log.info("Creating batch queries - key count: {}, bucket count: {}, total queries: {}", keys.size(), bucketCount, keys.size() * bucketCount);

                    for (String key : keys) {
                        for (int i = 0; i < bucketCount; i++) {
                            long bucketStart = startTs + i * interval;
                            long bucketEnd = Math.min(bucketStart + interval, endTs);

                            // 查询每个bucket的第一个值（limit=1）
                            ReadTsKvQuery query = new BaseReadTsKvQuery(key, bucketStart, bucketEnd, 0, 1, Aggregation.NONE, "ASC");
                            futures.add(tsService.findAll(currentUser.getTenantId(), entityId, List.of(query)));
                        }
                    }
                    log.debug("Created all {} batch queries", futures.size());

                    // 合并所有结果
                    Futures.addCallback(Futures.allAsList(futures),
                            new FutureCallback<List<List<TsKvEntry>>>() {
                                @Override
                                public void onSuccess(List<List<TsKvEntry>> results) {
                                    log.info("Batch queries completed successfully - total results: {}, non-empty results: {}",
                                            results.size(), results.stream().filter(list -> !list.isEmpty()).count());

                                    Map<String, List<FormattedTsData>> keys2ListData = new HashMap<>();
                                    int validDataCount = 0;
                                    for (List<TsKvEntry> bucketData : results) {
                                        if (!bucketData.isEmpty()) {
                                            TsKvEntry entry = bucketData.get(0);
                                            long timestamp = entry.getTs();
                                            //计算所属的bucket
                                            long bucketStartTs = (timestamp - startTs) / interval * interval + startTs;
                                            Object value = useStrictDataTypes ? getKvValue(entry) : entry.getValueAsString();
                                            keys2ListData.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                                            .add(FormattedTsData
                                                                    .builder()
                                                                    .ts(bucketStartTs)
                                                                    .originalTs(timestamp)
                                                                    .value(value).build());
                                            validDataCount++;
                                        }
                                    }

                                    log.info("Processed batch query results - result keys: {}, total valid data points: {}",
                                            keys2ListData.size(), validDataCount);
                                    future.set(keys2ListData);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    log.error("Batch queries failed for entityId: {}, error: {}", entityId, t.getMessage(), t);
                                    future.setException(t);
                                }
                            }, MoreExecutors.directExecutor());

                } catch (Throwable e) {
                    log.error("Exception in getTimeseriesFirstValueBatchQuery for entityId: {}, error: {}", entityId, e.getMessage(), e);
                    future.setException(e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Access validation failed in getTimeseriesFirstValueBatchQuery for entityId: {}, error: {}", entityId, t.getMessage(), t);
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
        log.debug("Entering extractFirstValuesFromBuckets - data size: {}, time range: {} to {}, interval: {}",
                allData.size(), startTs, endTs, interval);

        Map<String, List<FormattedTsData>> result = new HashMap<>();
        Map<String, Set<Long>> bucketFirstSeen = new java.util.HashMap<>();
        int processedRecords = 0;
        int addedRecords = 0;

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
                        .add(FormattedTsData
                                .builder()
                                .ts(bucketStart)
                                .originalTs(timestamp)
                                .value(value).build());

                seenBuckets.add(bucketStart);
                addedRecords++;
            }
            processedRecords++;

            if (processedRecords % 10000 == 0) {
                log.debug("Processing progress - processed: {} records, added: {} first values", processedRecords, addedRecords);
            }
        }

        log.info("Completed extractFirstValuesFromBuckets - processed records: {}, unique keys: {}, first values extracted: {}",
                processedRecords, result.size(), addedRecords);
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
