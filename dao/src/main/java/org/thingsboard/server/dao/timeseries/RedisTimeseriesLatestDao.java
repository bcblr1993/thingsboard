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
package org.thingsboard.server.dao.timeseries;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.*;
import org.thingsboard.server.dao.cache.CacheExecutorService;
import org.thingsboard.server.dao.sqlts.AggregationTimeseriesDao;
import org.thingsboard.server.dao.util.RedisTsLatestDao;

import java.util.*;
import java.util.concurrent.Callable;

@Component
@Slf4j
@RedisTsLatestDao
public class RedisTimeseriesLatestDao implements TimeseriesLatestDao {

    private static final String REDIS_KEY_PREFIX = "ts:latest:";
    private static final String REDIS_TS_KEY_PREFIX = "ts:ts:";
    private static final String REDIS_KEY_FORMAT = REDIS_KEY_PREFIX + "%s:%s"; // entityType:entityId
    private static final String REDIS_TS_KEY_FORMAT = REDIS_TS_KEY_PREFIX + "%s:%s"; // entityType:entityId

    private static final String SAVE_LATEST_SCRIPT = 
        "local dataKey = KEYS[1] " +
        "local tsKey = KEYS[2] " +
        "local field = ARGV[1] " +
        "local value = ARGV[2] " +
        "local ts = tonumber(ARGV[3]) " +
        "local existingTs = redis.call('hget', tsKey, field) " +
        "if existingTs then " +
        "    local oldTs = tonumber(existingTs) " +
        "    if oldTs and oldTs >= ts then " +
        "        return tostring(oldTs) " +
        "    end " +
        "end " +
        "redis.call('hset', dataKey, field, value) " +
        "redis.call('hset', tsKey, field, tostring(ts)) " +
        "return tostring(ts)";


    private static final String SAVE_LATEST_LIST_SCRIPT =
            "for i = 1, #ARGV, 3 do " +
            "    local field = ARGV[i] " +
            "    local value = ARGV[i+1] " +
            "    local ts = tonumber(ARGV[i+2]) " +
            "    local existingTs = redis.call('hget', KEYS[2], field) " +
            "    if existingTs then " +
            "        local oldTs = tonumber(existingTs) " +
            "        if oldTs and oldTs <= ts then " +
            "            redis.call('hset', KEYS[1], field, value) " +
            "            redis.call('hset', KEYS[2], field, tostring(ts)) " +
            "        end " +
            "    else " +
            "        redis.call('hset', KEYS[1], field, value) " +
            "        redis.call('hset', KEYS[2], field, tostring(ts)) " +
            "    end " +
            "end " +
            "return #ARGV / 3";


    private static final String FIND_LATEST_SCRIPT =
        "local dataKey = KEYS[1] " +
        "local field = ARGV[1] " +
        "return redis.call('hget', dataKey, field)";
    
    private static final String FIND_ALL_LATEST_SCRIPT = 
        "local dataKey = KEYS[1] " +
        "return redis.call('hgetall', dataKey)";

    private static final String REMOVE_LATEST_SCRIPT = 
        "local dataKey = KEYS[1] " +
        "local tsKey = KEYS[2] " +
        "local field = ARGV[1] " +
        "redis.call('hdel', dataKey, field) " +
        "redis.call('hdel', tsKey, field) " +
        "return '1'";

    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    @Autowired
    private CacheExecutorService cacheExecutorService;

    @Autowired
    private AggregationTimeseriesDao aggregationTimeseriesDao;

    private RedisScript<String> saveLatestScript;
    private RedisScript<Long> saveLatestListScript;
    private RedisScript<String> findLatestScript;
    private RedisScript<List<?>> findAllLatestScript;
    private RedisScript<String> removeLatestScript;

    // 使用分隔符来分隔不同的字段
    private static final char FIELD_DELIMITER = '|';
    private static final char TYPE_DELIMITER = ':';
    private static final char BOOL_PREFIX = 'b';
    private static final char STR_PREFIX = 's';
    private static final char LONG_PREFIX = 'l';
    private static final char DBL_PREFIX = 'd';
    private static final char JSON_PREFIX = 'j';

    @PostConstruct
    public void init() {
        saveLatestScript = new DefaultRedisScript<>(SAVE_LATEST_SCRIPT, String.class);
        saveLatestListScript = new DefaultRedisScript<>(SAVE_LATEST_LIST_SCRIPT, Long.class);
        findLatestScript = new DefaultRedisScript<>(FIND_LATEST_SCRIPT, String.class);
        findAllLatestScript = new DefaultRedisScript<>(FIND_ALL_LATEST_SCRIPT, (Class<List<?>>) (Class<?>) List.class);
        removeLatestScript = new DefaultRedisScript<>(REMOVE_LATEST_SCRIPT, String.class);
    }

    private String buildRedisKey(EntityId entityId) {
        return String.format(REDIS_KEY_FORMAT, entityId.getEntityType().name(), entityId.getId());
    }

    private String buildRedisTimeStampKey(EntityId entityId) {
        return String.format(REDIS_TS_KEY_FORMAT, entityId.getEntityType().name(), entityId.getId());
    }

    @Override
    public ListenableFuture<Optional<TsKvEntry>> findLatestOpt(TenantId tenantId, EntityId entityId, String key) {
        return cacheExecutorService.submit(new Callable<Optional<TsKvEntry>>() {
            @Override
            public Optional<TsKvEntry> call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                List<String> keys = Collections.singletonList(redisKey);
                String result = redisTemplate.execute(findLatestScript, keys, key);
                if (result == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(deserializeTsKvEntry(key, result));
                } catch (Exception e) {
                    log.error("Failed to parse data from Redis for key: {}, field: {}", redisKey, key, e);
                    return Optional.empty();
                }
            }
        });
    }

    @Override
    public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
        return cacheExecutorService.submit(new Callable<TsKvEntry>() {
            @Override
            public TsKvEntry call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                
                List<String> keys = Collections.singletonList(redisKey);
                String result = redisTemplate.execute(findLatestScript, keys, key);
                
                if (result == null) {
                    return new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(key, null));
                }
                
                try {
                    return deserializeTsKvEntry(key, result);
                } catch (Exception e) {
                    log.error("Failed to parse data from Redis for key: {}, field: {}", redisKey, key, e);
                    throw new RuntimeException("Failed to parse latest value", e);
                }
            }
        });
    }

    @Override
    public ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId) {
        return cacheExecutorService.submit(new Callable<List<TsKvEntry>>() {
            @Override
            public List<TsKvEntry> call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                List<String> keys = Collections.singletonList(redisKey);
                
                List<?> result = redisTemplate.execute(findAllLatestScript, keys);
                
                if (result == null || result.isEmpty()) {
                    return Collections.emptyList();
                }
                List<TsKvEntry> entries = new ArrayList<>();
                for (int i = 0; i < result.size(); i += 2) {
                    String field = result.get(i).toString();
                    String value = result.get(i + 1).toString();
                    try {
                        entries.add(deserializeTsKvEntry(field, value));
                    } catch (Exception e) {
                        log.error("Failed to parse data from Redis for key: {}, field: {}", redisKey, field, e);
                    }
                }
                return entries;
            }
        });
    }

    @Override
    public List<String> findAllKeysByDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId) {
        return Collections.emptyList();
    }

    @Override
    public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
        return Collections.emptyList();
    }

    @Override
    public ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry) {
        return cacheExecutorService.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                String redisTimeStampKey = buildRedisTimeStampKey(entityId);
                String serializedValue = serializeTsKvEntry(tsKvEntry);
                
                List<String> keys = Arrays.asList(redisKey, redisTimeStampKey);
                List<String> args = Arrays.asList(
                    tsKvEntry.getKey(),
                    serializedValue,
                    String.valueOf(tsKvEntry.getTs())
                );
                String result = redisTemplate.execute(saveLatestScript, keys, args.toArray());
                return Long.parseLong(result);
            }
        });
    }

    @Override
    public ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, List<TsKvEntry> tsKvEntries) {
        return cacheExecutorService.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                String redisTimeStampKey = buildRedisTimeStampKey(entityId);
                List<String> keys = Arrays.asList(redisKey, redisTimeStampKey);
                List<String> args = new ArrayList<>();
                for (TsKvEntry tsKvEntry : tsKvEntries) {
                    String serializedValue = serializeTsKvEntry(tsKvEntry);
                    args.add(tsKvEntry.getKey());
                    args.add(serializedValue);
                    args.add(String.valueOf(tsKvEntry.getTs()));
                }
                return redisTemplate.execute(saveLatestListScript, keys, args.toArray());
            }
        });
    }

    @Override
    public ListenableFuture<TsKvLatestRemovingResult> removeLatest(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        ListenableFuture<TsKvEntry> latestEntryFuture = findLatest(tenantId, entityId, query.getKey());

        ListenableFuture<Boolean> booleanFuture = Futures.transform(latestEntryFuture, latestEntry -> {
            long ts = latestEntry.getTs();
            if (ts >= query.getStartTs() && ts < query.getEndTs()) {
                return true;
            } else {
                log.trace("Won't be deleted latest value for [{}], key - {}", entityId, query.getKey());
            }
            return false;
        }, MoreExecutors.directExecutor());

        ListenableFuture<Boolean> removedLatestFuture = Futures.transformAsync(booleanFuture, isRemove -> {
            if (isRemove) {
                return Futures.transform(deleteLatest(tenantId, entityId, query.getKey()), res -> true, MoreExecutors.directExecutor());
            }
            return Futures.immediateFuture(false);
        }, MoreExecutors.directExecutor());

        return Futures.transformAsync(removedLatestFuture, isRemoved -> {
            if (isRemoved && query.getRewriteLatestIfDeleted()) {
                return getNewLatestEntryFuture(tenantId, entityId, query);
            }
            return Futures.immediateFuture(new TsKvLatestRemovingResult(query.getKey(), isRemoved));
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> deleteLatest(TenantId tenantId, EntityId entityId, String key) {
        return cacheExecutorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                String redisKey = buildRedisKey(entityId);
                String redisTimeStampKey = buildRedisTimeStampKey(entityId);
                
                List<String> keys = Arrays.asList(redisKey, redisTimeStampKey);
                redisTemplate.execute(removeLatestScript, keys, key);
                return null;
            }
        });
    }

    private ListenableFuture<TsKvLatestRemovingResult> getNewLatestEntryFuture(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        long startTs = 0;
        long endTs = query.getStartTs() - 1;
        ReadTsKvQuery findNewLatestQuery = new BaseReadTsKvQuery(query.getKey(), startTs, endTs, endTs - startTs, 1,
                Aggregation.NONE, "DESC");
        ListenableFuture<ReadTsKvQueryResult> future = aggregationTimeseriesDao.findAllAsync(tenantId, entityId, findNewLatestQuery);

        return Futures.transformAsync(future, result -> {
            var entryList = result.getData();
            if (entryList.size() == 1) {
                TsKvEntry entry = entryList.get(0);
                return Futures.transform(saveLatest(tenantId, entityId, entryList.get(0)), v -> new TsKvLatestRemovingResult(entry, v), MoreExecutors.directExecutor());
            } else {
                log.trace("Could not find new latest value for [{}], key - {}", entityId, query.getKey());
            }
            return Futures.immediateFuture(new TsKvLatestRemovingResult(query.getKey(), true));
        }, MoreExecutors.directExecutor());
    }

    private String serializeTsKvEntry(TsKvEntry tsKvEntry) {
        StringBuilder sb = new StringBuilder();
        sb.append(tsKvEntry.getTs()).append(FIELD_DELIMITER);

        if (tsKvEntry.getBooleanValue().isPresent()) {
            sb.append(BOOL_PREFIX).append(TYPE_DELIMITER)
              .append(tsKvEntry.getBooleanValue().get());
        } else if (tsKvEntry.getStrValue().isPresent()) {
            sb.append(STR_PREFIX).append(TYPE_DELIMITER)
              .append(tsKvEntry.getStrValue().get());
        } else if (tsKvEntry.getLongValue().isPresent()) {
            sb.append(LONG_PREFIX).append(TYPE_DELIMITER)
              .append(tsKvEntry.getLongValue().get());
        } else if (tsKvEntry.getDoubleValue().isPresent()) {
            sb.append(DBL_PREFIX).append(TYPE_DELIMITER)
              .append(tsKvEntry.getDoubleValue().get());
        } else if (tsKvEntry.getJsonValue().isPresent()) {
            sb.append(JSON_PREFIX).append(TYPE_DELIMITER)
              .append(tsKvEntry.getJsonValue().get());
        }
        return sb.toString();
    }

    private TsKvEntry deserializeTsKvEntry(String key, String str) {
        String[] parts = str.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid serialized format");
        }

        long ts = Long.parseLong(parts[0]);
        String value = parts[1];

        if (value.isEmpty()) {
            return new BasicTsKvEntry(ts, new StringDataEntry(key, null));
        }

        char typePrefix = value.charAt(0);
        String actualValue = value.substring(2); // Skip type prefix and delimiter

        switch (typePrefix) {
            case BOOL_PREFIX:
                return new BasicTsKvEntry(ts, new BooleanDataEntry(key, Boolean.parseBoolean(actualValue)));
            case STR_PREFIX:
                return new BasicTsKvEntry(ts, new StringDataEntry(key, actualValue));
            case LONG_PREFIX:
                return new BasicTsKvEntry(ts, new LongDataEntry(key, Long.parseLong(actualValue)));
            case DBL_PREFIX:
                return new BasicTsKvEntry(ts, new DoubleDataEntry(key, Double.parseDouble(actualValue)));
            case JSON_PREFIX:
                return new BasicTsKvEntry(ts, new JsonDataEntry(key, actualValue));
            default:
                throw new IllegalArgumentException("Unknown value type: " + typePrefix);
        }
    }
} 
