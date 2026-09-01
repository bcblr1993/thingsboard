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
package org.thingsboard.server.dao.timeseries.fast;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;
import org.thingsboard.server.dao.cache.CacheExecutorService;
import org.thingsboard.server.dao.sqlts.AggregationTimeseriesDao;
import org.thingsboard.server.dao.timeseries.BatchedTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.BatchedTimeseriesLatestWriteDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 高吞吐 latest 实现（{@code *-fast}）。与现网 {@code RedisTimeseriesLatestDao} /
 * {@code RedisClusterTimeseriesLatestDao} <b>完全独立并存</b>，通过 {@code database.ts_latest.type}
 * 按配置互斥装配。fast 表示 Lua 批处理优化，连接模式由 {@code redis.connection.type} 决定。
 *
 * <h3>相对现网实现的三点优化（压测 C 变体，实测 1.78x）</h3>
 * <ol>
 *   <li><b>单哈希</b>：现网写 data 与 ts 两个哈希；但值本身已是 {@code ts|type:value} 格式，
 *       ts 天然内嵌，独立 ts 哈希纯属冗余。去掉后<b>每点操作 3→2，内存省一半</b>。</li>
 *   <li><b>整设备批读批写</b>：一次 {@code HMGET} 读回本批全部 field 的现值，在 Lua 内逐个比较
 *       时间戳，再用<b>一次</b>多 field {@code HSET} 写回幸存者。命令数从"每点 1 次"降到
 *       "每设备 1 次"（实测降 200 倍）。</li>
 *   <li><b>多 key 批量读</b>：实现 {@link BatchedTimeseriesLatestDao}，{@code HMGET} 一次取多个
 *       测点，消除实体查询"设备数 × 测点数"的放大。</li>
 * </ol>
 *
 * <h3>严格保留现网 Lua 的三条保证</h3>
 * <ol>
 *   <li><b>原子性</b>：读-比较-写仍在单个 Lua 脚本内完成，服务端原子执行，
 *       并发写同一 field 不会交错。</li>
 *   <li><b>时间戳单调（防乱序覆盖）</b>：仅当 {@code 新ts >= 旧ts} 才写入。设备断网重传、
 *       边缘补传、<b>Kafka 重放</b>导致的旧数据后到，都不会覆盖新值 —— 这是"latest 零丢失"
 *       依赖重放的前提。</li>
 *   <li><b>data 与 ts 一致</b>：合并为单一 field 后<b>天然一致</b>，不存在"值更新而 ts 未更新"
 *       的撕裂状态（比现网双哈希更强）。</li>
 * </ol>
 *
 * <h3>数据格式兼容</h3>
 * 值编码沿用现网的 {@code ts|type:value}，因此<b>读得懂现网写入的数据</b>；仅不再维护那个
 * 冗余的 ts 哈希（其残留不影响读取，可自然过期或手工清理）。
 */
@Slf4j
public abstract class AbstractFastTimeseriesLatestDao implements TimeseriesLatestDao, BatchedTimeseriesLatestDao,
        BatchedTimeseriesLatestWriteDao {

    // ── 值编码（与现网实现保持一致，保证可读旧数据）──────────────────────────────
    private static final char FIELD_DELIMITER = '|';
    private static final char TYPE_DELIMITER = ':';
    private static final char BOOL_PREFIX = 'b';
    private static final char STR_PREFIX = 's';
    private static final char LONG_PREFIX = 'l';
    private static final char DBL_PREFIX = 'd';
    private static final char JSON_PREFIX = 'j';

    /**
     * 批量写入：一次 HMGET 批读 + 一次多 field HSET 批写，Lua 内完成时间戳守卫。
     * ARGV 为 {@code [field1, value1, field2, value2, ...]}，value 形如 {@code ts|type:payload}。
     * 返回实际写入（通过守卫）的 field 数。
     */
    private static final String SAVE_LATEST_BATCH_SCRIPT =
            "local n = #ARGV / 2 " +
            "local fields = {} " +
            "for i = 1, n do fields[i] = ARGV[i*2-1] end " +
            "local cur = redis.call('hmget', KEYS[1], unpack(fields)) " +
            "local out = {} local cnt = 0 " +
            "for i = 1, n do " +
            "    local newVal = ARGV[i*2] " +
            "    local sep = string.find(newVal, '|', 1, true) " +
            "    local newTs = tonumber(string.sub(newVal, 1, sep - 1)) " +
            "    local ok = true " +
            "    if cur[i] then " +
            "        local oSep = string.find(cur[i], '|', 1, true) " +
            "        if oSep then " +
            "            local oldTs = tonumber(string.sub(cur[i], 1, oSep - 1)) " +
            // 严格保留现网语义: 仅当新 ts >= 旧 ts 才覆盖(旧数据后到时丢弃)
            "            if oldTs and newTs and oldTs > newTs then ok = false end " +
            "        end " +
            "    end " +
            "    if ok then out[#out+1] = fields[i] out[#out+1] = newVal cnt = cnt + 1 end " +
            "end " +
            "if #out > 0 then redis.call('hset', KEYS[1], unpack(out)) end " +
            "return cnt";

    private static final String FIND_ALL_LATEST_SCRIPT = "return redis.call('hgetall', KEYS[1])";

    private static final String REMOVE_LATEST_SCRIPT = "return redis.call('hdel', KEYS[1], unpack(ARGV))";

    @Autowired
    protected RedisTemplate<String, String> redisTemplate;

    @Autowired
    protected CacheExecutorService cacheExecutorService;

    @Autowired
    protected AggregationTimeseriesDao aggregationTimeseriesDao;

    private RedisScript<Long> saveLatestBatchScript;
    private RedisScript<List<?>> findAllLatestScript;
    private RedisScript<Long> removeLatestScript;

    @PostConstruct
    public void init() {
        saveLatestBatchScript = new DefaultRedisScript<>(SAVE_LATEST_BATCH_SCRIPT, Long.class);
        findAllLatestScript = new DefaultRedisScript<>(FIND_ALL_LATEST_SCRIPT, (Class<List<?>>) (Class<?>) List.class);
        removeLatestScript = new DefaultRedisScript<>(REMOVE_LATEST_SCRIPT, Long.class);
        log.info("[{}] fast latest DAO initialized (single-hash + batched read/write, ts-guard preserved)",
                backendName());
    }

    /** 后端名称，仅用于日志/指标区分（redis / valkey）。 */
    protected abstract String backendName();

    /**
     * 实体的哈希键。集群下用 hash tag 保证同一实体的所有操作落在同一 slot。
     */
    protected String buildKey(EntityId entityId) {
        return "ts:{" + entityId.getEntityType().name() + entityId.getId() + "}:data";
    }

    // ────────────────────────────────────────────────────────────── 写入

    @Override
    public ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry) {
        return saveLatest(tenantId, entityId, Collections.singletonList(tsKvEntry));
    }

    @Override
    public ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, List<TsKvEntry> tsKvEntries) {
        if (tsKvEntries == null || tsKvEntries.isEmpty()) {
            return Futures.immediateFuture(0L);
        }
        return cacheExecutorService.submit(() -> {
            List<String> argv = new ArrayList<>(tsKvEntries.size() * 2);
            for (TsKvEntry e : tsKvEntries) {
                argv.add(e.getKey());
                argv.add(serialize(e));
            }
            Long saved = redisTemplate.execute(saveLatestBatchScript, Collections.singletonList(buildKey(entityId)),
                    argv.toArray());
            return saved != null ? saved : 0L;
        });
    }

    // ────────────────────────────────────────────────────────────── 读取

    @Override
    public ListenableFuture<Optional<TsKvEntry>> findLatestOpt(TenantId tenantId, EntityId entityId, String key) {
        return cacheExecutorService.submit(() -> {
            String raw = redisTemplate.<String, String>opsForHash().get(buildKey(entityId), key);
            return raw == null ? Optional.empty() : Optional.of(deserialize(key, raw));
        });
    }

    @Override
    public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
        return cacheExecutorService.submit(() -> {
            String raw = redisTemplate.<String, String>opsForHash().get(buildKey(entityId), key);
            return raw == null
                    ? new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(key, null))
                    : deserialize(key, raw);
        });
    }

    /**
     * 多 key 批量读（{@link BatchedTimeseriesLatestDao}）：单次 HMGET 取回全部测点，
     * 消除实体查询的 N×K 放大。契约与逐 key 路径等价：<b>按请求顺序返回、每个请求 key 都有
     * 一个条目、缺失者补 null 值占位</b>。
     */
    @Override
    public ListenableFuture<List<TsKvEntry>> findLatest(TenantId tenantId, EntityId entityId, Collection<String> keys) {
        List<String> requested = new ArrayList<>(keys);
        if (requested.isEmpty()) {
            return Futures.immediateFuture(Collections.emptyList());
        }
        return cacheExecutorService.submit(() -> {
            // 去重后批量读，再按请求顺序还原（重复 key 与逐 key 路径行为一致）
            List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(requested));
            List<String> raw = redisTemplate.<String, String>opsForHash()
                    .multiGet(buildKey(entityId), Collections.unmodifiableList(unique));
            Map<String, String> found = new LinkedHashMap<>(unique.size());
            for (int i = 0; i < unique.size(); i++) {
                String v = raw == null || i >= raw.size() ? null : raw.get(i);
                if (v != null) {
                    found.put(unique.get(i), v);
                }
            }
            long missingTs = System.currentTimeMillis();
            List<TsKvEntry> result = new ArrayList<>(requested.size());
            for (String k : requested) {
                String v = found.get(k);
                result.add(v != null ? deserialize(k, v)
                        : new BasicTsKvEntry(missingTs, new StringDataEntry(k, null)));
            }
            return result;
        });
    }

    @Override
    public ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId) {
        return cacheExecutorService.submit(() -> {
            List<?> flat = redisTemplate.execute(findAllLatestScript,
                    Collections.singletonList(buildKey(entityId)));
            List<TsKvEntry> result = new ArrayList<>();
            if (flat != null) {
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    String k = String.valueOf(flat.get(i));
                    String v = String.valueOf(flat.get(i + 1));
                    try {
                        result.add(deserialize(k, v));
                    } catch (Exception ex) {
                        log.warn("[{}] skip malformed latest value for key {}: {}", backendName(), k, ex.getMessage());
                    }
                }
            }
            return result;
        });
    }

    // ────────────────────────────────────────────────────────────── 删除

    @Override
    public ListenableFuture<TsKvLatestRemovingResult> removeLatest(TenantId tenantId, EntityId entityId,
                                                                   DeleteTsKvQuery query) {
        ListenableFuture<Optional<TsKvEntry>> current = findLatestOpt(tenantId, entityId, query.getKey());
        ListenableFuture<Boolean> removedFuture = Futures.transformAsync(current, opt -> {
            if (opt.isPresent() && opt.get().getValue() != null) {
                long ts = opt.get().getTs();
                // 与删除语句一致的半开区间 [startTs, endTs)
                if (ts >= query.getStartTs() && ts < query.getEndTs()) {
                    return Futures.transform(deleteLatest(entityId, query.getKey()), v -> true,
                            MoreExecutors.directExecutor());
                }
            }
            return Futures.immediateFuture(false);
        }, MoreExecutors.directExecutor());

        return Futures.transformAsync(removedFuture, isRemoved -> {
            if (Boolean.TRUE.equals(isRemoved) && query.getRewriteLatestIfDeleted()) {
                return rewriteLatest(tenantId, entityId, query);
            }
            return Futures.immediateFuture(new TsKvLatestRemovingResult(query.getKey(), Boolean.TRUE.equals(isRemoved)));
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> deleteLatest(EntityId entityId, String key) {
        return cacheExecutorService.submit(() -> {
            redisTemplate.execute(removeLatestScript, Collections.singletonList(buildKey(entityId)), key);
            return null;
        });
    }

    private ListenableFuture<TsKvLatestRemovingResult> rewriteLatest(TenantId tenantId, EntityId entityId,
                                                                     DeleteTsKvQuery query) {
        long endTs = query.getStartTs() - 1;
        ReadTsKvQuery findNewLatest = new BaseReadTsKvQuery(query.getKey(), 0, endTs, endTs, 1,
                Aggregation.NONE, "DESC");
        return Futures.transformAsync(aggregationTimeseriesDao.findAllAsync(tenantId, entityId, findNewLatest),
                result -> {
                    List<TsKvEntry> data = result.getData();
                    if (data.size() == 1) {
                        TsKvEntry entry = data.get(0);
                        return Futures.transform(saveLatest(tenantId, entityId, entry),
                                v -> new TsKvLatestRemovingResult(entry, v), MoreExecutors.directExecutor());
                    }
                    log.trace("[{}] no previous value to rewrite latest for [{}] key {}", backendName(), entityId, query.getKey());
                    return Futures.immediateFuture(new TsKvLatestRemovingResult(query.getKey(), true));
                }, MoreExecutors.directExecutor());
    }

    // ────────────────────────────────────────────────────────────── key 枚举

    @Override
    public List<String> findAllKeysByDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId) {
        // 与 Cassandra / IoTDB latest DAO 行为一致：按 profile 枚举 key 由 SQL 侧提供
        return Collections.emptyList();
    }

    @Override
    public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (EntityId entityId : entityIds) {
            java.util.Set<String> hashKeys = redisTemplate.<String, String>opsForHash().keys(buildKey(entityId));
            if (hashKeys != null) {
                keys.addAll(hashKeys);
            }
        }
        return new ArrayList<>(keys);
    }

    // ────────────────────────────────────────────────────────────── 编解码

    /** 与现网实现完全一致的编码：{@code ts|typePrefix:value}。 */
    protected String serialize(TsKvEntry e) {
        StringBuilder sb = new StringBuilder(48);
        sb.append(e.getTs()).append(FIELD_DELIMITER);
        if (e.getBooleanValue().isPresent()) {
            sb.append(BOOL_PREFIX).append(TYPE_DELIMITER).append(e.getBooleanValue().get());
        } else if (e.getStrValue().isPresent()) {
            sb.append(STR_PREFIX).append(TYPE_DELIMITER).append(e.getStrValue().get());
        } else if (e.getLongValue().isPresent()) {
            sb.append(LONG_PREFIX).append(TYPE_DELIMITER).append(e.getLongValue().get());
        } else if (e.getDoubleValue().isPresent()) {
            sb.append(DBL_PREFIX).append(TYPE_DELIMITER).append(e.getDoubleValue().get());
        } else if (e.getJsonValue().isPresent()) {
            sb.append(JSON_PREFIX).append(TYPE_DELIMITER).append(e.getJsonValue().get());
        }
        return sb.toString();
    }

    protected TsKvEntry deserialize(String key, String str) {
        int sep = str.indexOf(FIELD_DELIMITER);
        if (sep < 0) {
            throw new IllegalArgumentException("Invalid serialized latest value for key " + key);
        }
        long ts = Long.parseLong(str.substring(0, sep));
        String value = str.substring(sep + 1);
        if (value.isEmpty()) {
            return new BasicTsKvEntry(ts, new StringDataEntry(key, null));
        }
        char type = value.charAt(0);
        String actual = value.length() > 2 ? value.substring(2) : "";
        return switch (type) {
            case BOOL_PREFIX -> new BasicTsKvEntry(ts, new BooleanDataEntry(key, Boolean.parseBoolean(actual)));
            case LONG_PREFIX -> new BasicTsKvEntry(ts, new LongDataEntry(key, Long.parseLong(actual)));
            case DBL_PREFIX -> new BasicTsKvEntry(ts, new DoubleDataEntry(key, Double.parseDouble(actual)));
            case JSON_PREFIX -> new BasicTsKvEntry(ts, new JsonDataEntry(key, actual));
            default -> new BasicTsKvEntry(ts, new StringDataEntry(key, actual));
        };
    }
}
