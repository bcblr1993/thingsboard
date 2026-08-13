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
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationCallback;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.ValidationResultCode;
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
    private static final int FIRST_VALUE_MAX_KEYS = 200;
    private static final int FIRST_VALUE_MAX_DATA_POINTS = 500_000;
    private static final int FIRST_VALUE_QUERY_BATCH_SIZE = 256;
    private static final long FIRST_VALUE_MIN_INTERVAL = TimeUnit.MINUTES.toMillis(1);
    private static final long FIRST_VALUE_MAX_TIME_RANGE = TimeUnit.DAYS.toMillis(31);

    private final TimeseriesService tsService;
    private final AccessValidator accessValidator;
    private final DbCallbackExecutorService dbCallbackExecutorService;

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
        List<String> distinctKeys = keys == null ? null : new ArrayList<>(new LinkedHashSet<>(keys));
        validateTimeseriesFillRequest(distinctKeys, startTs, endTs, interval, agg, fillMissing, orderBy);
        SettableFuture<List<TsKvEntry>> future = SettableFuture.create();
        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                if (validationResult.getResultCode() != ValidationResultCode.OK) {
                    future.setException(ValidationCallback.getException(validationResult));
                    return;
                }
                TimeseriesFillQueryContext context = new TimeseriesFillQueryContext(distinctKeys);
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
                                                                                        SecurityUser currentUser) throws ThingsboardException {
        long bucketCount = validateTimeseriesFirstValueRequest(keys, startTs, endTs, interval);
        List<String> distinctKeys = new ArrayList<>(new LinkedHashSet<>(keys));
        SettableFuture<Map<String, List<FormattedTsData>>> future = SettableFuture.create();
        accessValidator.validate(currentUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                if (validationResult.getResultCode() != ValidationResultCode.OK) {
                    future.setException(ValidationCallback.getException(validationResult));
                    return;
                }
                TimeseriesFirstValueQueryContext context = new TimeseriesFirstValueQueryContext(
                        distinctKeys, startTs, endTs, interval, bucketCount, Boolean.TRUE.equals(useStrictDataTypes));
                queryNextFirstValueBatch(currentUser.getTenantId(), entityId, context, future);
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });
        return future;
    }

    private long validateTimeseriesFirstValueRequest(List<String> keys, Long startTs, Long endTs, Long interval) throws ThingsboardException {
        if (keys == null || keys.isEmpty()) {
            throw badRequest("keys不能为空");
        }
        if (keys.size() > FIRST_VALUE_MAX_KEYS) {
            throw badRequest("keys数量不能超过" + FIRST_VALUE_MAX_KEYS);
        }
        if (keys.stream().anyMatch(key -> key == null || key.isEmpty())) {
            throw badRequest("keys不能包含空值");
        }
        if (startTs == null || endTs == null || startTs < 0 || endTs <= startTs) {
            throw badRequest("startTs必须大于等于0，且endTs必须大于startTs");
        }
        long timeRange = endTs - startTs;
        if (timeRange > FIRST_VALUE_MAX_TIME_RANGE) {
            throw badRequest("查询时间范围不能超过31天");
        }
        if (interval == null || interval < FIRST_VALUE_MIN_INTERVAL) {
            throw badRequest("interval不能小于60000");
        }
        long bucketCount = timeRange / interval;
        if (timeRange % interval != 0) {
            bucketCount++;
        }
        if (bucketCount > FIRST_VALUE_MAX_DATA_POINTS / keys.size()) {
            throw badRequest("请求的潜在返回桶位不能超过" + FIRST_VALUE_MAX_DATA_POINTS);
        }
        return bucketCount;
    }

    private void queryNextFirstValueBatch(TenantId tenantId, EntityId entityId,
                                          TimeseriesFirstValueQueryContext context,
                                          SettableFuture<Map<String, List<FormattedTsData>>> future) {
        if (future.isDone()) {
            return;
        }
        List<ReadTsKvQuery> queries = context.nextBatch();
        if (queries.isEmpty()) {
            context.result.values().forEach(Collections::sort);
            future.set(context.result);
            return;
        }
        ListenableFuture<List<TsKvEntry>> batchFuture;
        try {
            batchFuture = tsService.findAll(tenantId, entityId, queries);
        } catch (Throwable t) {
            future.setException(t);
            return;
        }
        Futures.addCallback(batchFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(List<TsKvEntry> entries) {
                if (future.isDone()) {
                    return;
                }
                try {
                    if (entries != null) {
                        entries.forEach(context::addResult);
                    }
                    queryNextFirstValueBatch(tenantId, entityId, context, future);
                } catch (Throwable t) {
                    future.setException(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        }, dbCallbackExecutorService);
    }

    private final class TimeseriesFirstValueQueryContext {
        private final List<String> keys;
        private final long startTs;
        private final long endTs;
        private final long interval;
        private final long bucketCount;
        private final boolean useStrictDataTypes;
        private final Map<String, List<FormattedTsData>> result = new LinkedHashMap<>();
        private final Map<String, Map<Long, Integer>> resultIndexes = new HashMap<>();
        private int keyIndex;
        private long bucketIndex;

        private TimeseriesFirstValueQueryContext(List<String> keys, long startTs, long endTs, long interval,
                                                 long bucketCount, boolean useStrictDataTypes) {
            this.keys = keys;
            this.startTs = startTs;
            this.endTs = endTs;
            this.interval = interval;
            this.bucketCount = bucketCount;
            this.useStrictDataTypes = useStrictDataTypes;
        }

        private List<ReadTsKvQuery> nextBatch() {
            List<ReadTsKvQuery> queries = new ArrayList<>(FIRST_VALUE_QUERY_BATCH_SIZE);
            while (queries.size() < FIRST_VALUE_QUERY_BATCH_SIZE && keyIndex < keys.size()) {
                long bucketStart = startTs + bucketIndex * interval;
                long remaining = endTs - bucketStart;
                long bucketEnd = interval >= remaining ? endTs : bucketStart + interval;
                queries.add(new BaseReadTsKvQuery(keys.get(keyIndex), bucketStart, bucketEnd,
                        AggregationParams.none(), 1, "ASC"));
                bucketIndex++;
                if (bucketIndex == bucketCount) {
                    bucketIndex = 0;
                    keyIndex++;
                }
            }
            return queries;
        }

        private void addResult(TsKvEntry entry) {
            long timestamp = entry.getTs();
            if (timestamp < startTs || timestamp >= endTs) {
                return;
            }
            long bucketStart = startTs + ((timestamp - startTs) / interval) * interval;
            Object value = useStrictDataTypes ? getKvValue(entry) : entry.getValueAsString();
            List<FormattedTsData> keyResult = result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
            Map<Long, Integer> keyResultIndexes = resultIndexes.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>());
            Integer existingIndex = keyResultIndexes.get(bucketStart);
            if (existingIndex != null) {
                FormattedTsData existing = keyResult.get(existingIndex);
                if (timestamp < existing.getOriginalTs()) {
                    keyResult.set(existingIndex, new FormattedTsData(bucketStart, timestamp, value));
                }
                return;
            }
            keyResultIndexes.put(bucketStart, keyResult.size());
            keyResult.add(new FormattedTsData(bucketStart, timestamp, value));
        }
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
