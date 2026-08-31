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
package org.thingsboard.server.dao.timeseries.iotdb;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.sqlts.AggregationTimeseriesDao;
import org.thingsboard.server.dao.timeseries.BatchedTimeseriesDao;
import org.thingsboard.server.dao.timeseries.TimeseriesDao;
import org.thingsboard.server.dao.util.IotdbTsDao;
import org.thingsboard.server.dao.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Apache IoTDB implementation of {@link TimeseriesDao} (historical time-series).
 * <p>
 * Data model (aligned tree model): see {@link IotdbSchemaUtil}. Writes go through the shared
 * {@link IotdbWriteService} (batched aligned tablets); reads and aggregation are pushed down to
 * IoTDB via SQL (including native GROUP BY).
 */
@Component
@IotdbTsDao
@Slf4j
public class IotdbBaseTimeseriesDao implements TimeseriesDao, BatchedTimeseriesDao, AggregationTimeseriesDao {

    public static final String DESC = "DESC";

    @Autowired
    private IotdbSessionPoolConfig iotdb;
    @Autowired
    private IotdbWriteService writeService;
    @Autowired
    private IotdbQueryMetrics queryMetrics;

    @Value("${iotdb.read_threads:0}")
    private int readThreads;
    // 读线程池有界队列容量(0=自动 threads*64)。满队后 fast-fail(AbortPolicy): 查询立即返回失败,
    // 绝不在调用线程内联执行, 避免规则引擎/状态/订阅线程被阻塞(见 submitRead)。
    @Value("${iotdb.read_queue_capacity:0}")
    private int readQueueCapacity;

    private SessionPool pool;
    private String database;
    private ListeningExecutorService readExecutor;
    // Cache the computed IoTDB device path per entity to avoid recomputing it on every point.
    private final Map<EntityId, String> devicePathCache = new ConcurrentHashMap<>();
    // 数据级 TTL(per-tenant/per-entity)不被 IoTDB 后端支持, 首次遇到只提示一次, 避免刷屏。
    private final java.util.concurrent.atomic.AtomicBoolean warnedDataLevelTtl =
            new java.util.concurrent.atomic.AtomicBoolean();
    // 批量路径隔离非法 key 的 WARN 限流(每秒至多一条), 保证持续可见又不刷屏。
    private final java.util.concurrent.atomic.AtomicLong lastIllegalKeyWarnNs =
            new java.util.concurrent.atomic.AtomicLong();

    @PostConstruct
    public void init() {
        // 读专用连接池: 与写入物理隔离, 查询洪峰不占写连接
        this.pool = iotdb.getReadSessionPool();
        this.database = iotdb.getDatabase();
        int threads = readThreads > 0 ? readThreads : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        int queueCap = readQueueCapacity > 0 ? readQueueCapacity : threads * 64;
        // AbortPolicy(而非 CallerRuns): 队列满时拒绝, 由 submitRead 转成 fast-fail 的 failed
        // future。绝不在调用线程(可能是规则引擎/设备状态/订阅线程)内联执行查询——否则调用
        // 线程会被 IoTDB 查询阻塞至 query_timeout_ms, 触发 Rule Engine pack timeout / Kafka lag。
        ThreadPoolExecutor exec = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCap), r -> new Thread(r, "iotdb-read"),
                new ThreadPoolExecutor.AbortPolicy());
        this.readExecutor = MoreExecutors.listeningDecorator(exec);
    }

    /**
     * 提交读任务。队列满被拒时返回立即失败的 future(而非阻塞/内联执行), 保护调用线程 ——
     * 上游按"查询失败"降级(重试/失败链), 好过整个 pack 被拖到超时。
     */
    private <T> ListenableFuture<T> submitRead(java.util.concurrent.Callable<T> task) {
        try {
            return readExecutor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException rej) {
            warnReadRejectedThrottled();
            return Futures.immediateFailedFuture(new RuntimeException(
                    "[IoTDB] 读队列已满, 查询被拒绝(fast-fail 保护调用线程)。可增大 iotdb.read_queue_capacity 或 read_threads。", rej));
        }
    }

    private final java.util.concurrent.atomic.AtomicLong lastReadRejectWarnNs = new java.util.concurrent.atomic.AtomicLong();

    private void warnReadRejectedThrottled() {
        long now = System.nanoTime();
        long prev = lastReadRejectWarnNs.get();
        if (now - prev > 1_000_000_000L && lastReadRejectWarnNs.compareAndSet(prev, now)) {
            log.warn("[IoTDB] 读队列已满, 历史查询被 fast-fail 拒绝(保护调用线程免于阻塞)。"
                    + "持续出现说明读并发超出 read_threads/read_queue_capacity, 请调大或排查慢查询。");
        }
    }

    @PreDestroy
    public void stop() {
        if (readExecutor != null) {
            readExecutor.shutdown();
        }
    }

    // ---------------------------------------------------------------- write

    @Override
    public ListenableFuture<Integer> save(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry, long ttl) {
        // 数据级 TTL(TB 传入的 per-tenant/per-entity ttl)在 IoTDB 后端不生效: 只有全局 TTL
        // (iotdb.ttl_ms)管过期。首次检测到非零数据级 TTL 时明确提示一次, 避免运维误以为已按
        // 租户/设备过期而磁盘失控。
        if (ttl > 0 && warnedDataLevelTtl.compareAndSet(false, true)) {
            log.warn("[IoTDB] 检测到数据级 TTL(per-tenant/per-entity, ttl={}ms), 但 IoTDB 后端不支持: "
                    + "仅全局 TTL(iotdb.ttl_ms)生效。如需按租户/设备过期, 请改用全局 TTL 或外部清理方案。", ttl);
        }
        String key = tsKvEntry.getKey();
        String reason = IotdbSchemaUtil.illegalKeyReason(key);
        if (reason != null) {
            if (IotdbSchemaUtil.shouldWarnIllegalOnce(key)) {
                log.warn("[IoTDB] 测点 key '{}' 无法存储({}) — 该点被丢弃, 同设备其他测点不受影响。请修改测点命名。",
                        key, reason);
            }
            // 单点兼容路径也必须与 saveBatch 一致：非法 key 是确定性、不可重试的数据错误。
            // 返回成功且计数为 0，避免 RETRY 队列永久重放同一条 Kafka 毒丸。
            return Futures.immediateFuture(0);
        }
        String device = devicePathCache.computeIfAbsent(entityId, e -> IotdbSchemaUtil.devicePath(database, e));
        TSDataType type = IotdbSchemaUtil.toIotdbType(tsKvEntry.getDataType());
        Object value = IotdbSchemaUtil.toIotdbValue(type, IotdbSchemaUtil.rawValue(tsKvEntry));
        // measurement 用 quote(key): 含点/横杠/空格/纯数字/反引号的 key 经服务端引用解析后
        // 存为真实节点名(实测对裸名 key 存储结果不变, 兼容既有数据)。
        return writeService.write(device, IotdbSchemaUtil.quote(key), tsKvEntry.getTs(), type, value, tsKvEntry.getDataPoints());
    }

    @Override
    public ListenableFuture<Integer> saveBatch(TenantId tenantId, EntityId entityId,
                                               List<TsKvEntry> entries, long ttl) {
        if (entries.isEmpty()) {
            return Futures.immediateFuture(0);
        }
        if (ttl > 0 && warnedDataLevelTtl.compareAndSet(false, true)) {
            log.warn("[IoTDB] 检测到数据级 TTL(per-tenant/per-entity, ttl={}ms), 但 IoTDB 后端不支持: "
                    + "仅全局 TTL(iotdb.ttl_ms)生效。如需按租户/设备过期, 请改用全局 TTL 或外部清理方案。", ttl);
        }
        String device = devicePathCache.computeIfAbsent(entityId, e -> IotdbSchemaUtil.devicePath(database, e));
        List<IotdbTimeseriesWriteBuffer.WritePoint> points = new ArrayList<>(entries.size());
        int dataPoints = 0;
        int rejectedPoints = 0;
        String firstRejectedKey = null;
        String firstRejectedReason = null;
        for (TsKvEntry entry : entries) {
            String key = entry.getKey();
            String reason = IotdbSchemaUtil.illegalKeyReason(key);
            if (reason != null) {
                // IoTDB 无法保存该测点，但不能让一个非法 key 把同消息其他合法点一起拒绝，
                // 更不能让 RETRY 策略把它变成永久 Kafka 毒丸。仅隔离该点并持续限流告警。
                rejectedPoints++;
                if (firstRejectedKey == null) {
                    firstRejectedKey = key;
                    firstRejectedReason = reason;
                }
                continue;
            }
            TSDataType type = IotdbSchemaUtil.toIotdbType(entry.getDataType());
            Object value = IotdbSchemaUtil.toIotdbValue(type, IotdbSchemaUtil.rawValue(entry));
            points.add(new IotdbTimeseriesWriteBuffer.WritePoint(
                    IotdbSchemaUtil.quote(key), entry.getTs(), type, value, entry.getDataPoints()));
            dataPoints += entry.getDataPoints();
        }
        if (rejectedPoints > 0) {
            warnIllegalKeyIsolatedThrottled(firstRejectedKey, firstRejectedReason,
                    rejectedPoints, points.size(), entityId);
        }
        if (points.isEmpty()) {
            log.debug("[IoTDB] telemetry message contains no storable points for {} (rejected={})",
                    entityId, rejectedPoints);
            return Futures.immediateFuture(0);
        }
        return writeService.writeBatch(device, points, dataPoints);
    }

    /** 批量中的非法 key 被单点隔离: 限流 WARN(每秒至多一条), 始终可见但不刷屏。 */
    private void warnIllegalKeyIsolatedThrottled(String key, String reason, int rejectedPoints,
                                                 int acceptedPoints, EntityId entityId) {
        long now = System.nanoTime();
        long prev = lastIllegalKeyWarnNs.get();
        if (now - prev > 1_000_000_000L && lastIllegalKeyWarnNs.compareAndSet(prev, now)) {
            log.warn("[IoTDB] 非法测点已隔离: 示例 key '{}' 无法存储({}) — 本消息丢弃 {} 个非法点，"
                    + "其余 {} 个测点继续写入(设备 {})。请修改测点命名。",
                    key, reason, rejectedPoints, acceptedPoints, entityId);
        }
    }

    @Override
    public ListenableFuture<Integer> savePartition(TenantId tenantId, EntityId entityId, long tsKvEntryTs, String key) {
        // IoTDB partitions internally by time — no partition bookkeeping needed.
        return Futures.immediateFuture(0);
    }

    @Override
    public ListenableFuture<Void> remove(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        return submitRead(() -> {
            String sql = "delete from " + IotdbSchemaUtil.measurementPath(database, entityId, query.getKey())
                    + " where time >= " + query.getStartTs() + " and time < " + query.getEndTs();
            pool.executeNonQueryStatement(sql);
            return null;
        });
    }

    @Override
    public void cleanup(long systemTtl) {
        // TTL is handled natively by IoTDB (see iotdb.ttl_ms). Nothing to do here.
    }

    // ---------------------------------------------------------------- read

    @Override
    public ListenableFuture<List<ReadTsKvQueryResult>> findAllAsync(TenantId tenantId, EntityId entityId, List<ReadTsKvQuery> queries) {
        List<ListenableFuture<ReadTsKvQueryResult>> futures = queries.stream()
                .map(q -> findAllAsync(tenantId, entityId, q))
                .collect(Collectors.toList());
        return Futures.allAsList(futures);
    }

    @Override
    public ListenableFuture<ReadTsKvQueryResult> findAllAsync(TenantId tenantId, EntityId entityId, ReadTsKvQuery query) {
        if (Aggregation.NONE.equals(query.getAggregation())) {
            return submitRead(() -> queryMetrics.timed("raw", entityId, () -> readRaw(entityId, query)));
        }
        return submitRead(() -> queryMetrics.timed("agg", entityId, () -> readAggregated(entityId, query)));
    }

    private ReadTsKvQueryResult readRaw(EntityId entityId, ReadTsKvQuery query) throws Exception {
        // 对齐 Cassandra(TsKvQueryCursor.isFull) 与 SQL(PageRequest 要求 size≥1) 的语义:
        // limit<=0 不视为"取全量", 直接返回空 —— 避免无界结果集全量物化到 TB 堆(OOM),
        // 并保证切换存储后端时结果一致(IoTDB 走 NoSQL 查询路径, 与 Cassandra 同语义)。
        if (query.getLimit() <= 0) {
            return new ReadTsKvQueryResult(query.getId(), new ArrayList<>(), query.getStartTs());
        }
        String order = DESC.equalsIgnoreCase(query.getOrder()) ? "desc" : "asc";
        String sql = "select " + IotdbSchemaUtil.quote(query.getKey()) + " from " + IotdbSchemaUtil.devicePath(database, entityId)
                + " where time >= " + query.getStartTs() + " and time < " + query.getEndTs()
                + " order by time " + order
                + " limit " + query.getLimit();
        List<TsKvEntry> data = new ArrayList<>();
        long lastTs = query.getStartTs();
        try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
            while (w.hasNext()) {
                RowRecord r = w.next();
                Field f = r.getFields().get(0);
                if (f == null || f.getDataType() == null) {
                    continue;
                }
                long ts = r.getTimestamp();
                data.add(new BasicTsKvEntry(ts, IotdbSchemaUtil.toKvEntry(query.getKey(), f)));
                lastTs = Math.max(lastTs, ts);
            }
        }
        return new ReadTsKvQueryResult(query.getId(), data, lastTs);
    }

    private ReadTsKvQueryResult readAggregated(EntityId entityId, ReadTsKvQuery query) throws Exception {
        if (!IntervalType.MILLISECONDS.equals(query.getAggParameters().getIntervalType())) {
            return readCalendarAggregated(entityId, query);
        }
        return readFixedAggregated(entityId, query);
    }

    /** 固定毫秒窗口继续使用 IoTDB 原生 GROUP BY：一条 SQL，不改变现有高吞吐查询路径。 */
    private ReadTsKvQueryResult readFixedAggregated(EntityId entityId, ReadTsKvQuery query) throws Exception {
        long interval = Math.max(1, query.getInterval());
        String fn = aggFunction(query.getAggregation());
        String q = IotdbSchemaUtil.quote(query.getKey());
        // 额外取 max_time(key): lastEntryTs 契约要求返回"匹配区间内原始记录的最大时间戳"
        // (见 ReadTsKvQueryResult.lastEntryTs 注释 — 不是聚合窗口的 ts)。SQL 后端用每窗口
        // MAX(ts) 再取全局最大, 这里对齐: 用 max_time 聚合而非窗口中点。
        String sql = "select " + fn + "(" + q + "), max_time(" + q + ") from " + IotdbSchemaUtil.devicePath(database, entityId)
                + " group by ([" + query.getStartTs() + "," + Math.max(query.getStartTs() + 1, query.getEndTs()) + ")," + interval + "ms)";
        List<TsKvEntry> data = new ArrayList<>();
        long lastTs = query.getStartTs();
        try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
            while (w.hasNext()) {
                RowRecord r = w.next();
                Field f = r.getFields().get(0);
                if (f == null || f.getDataType() == null) {
                    continue; // empty window
                }
                long ts = r.getTimestamp() + interval / 2; // mirror midpoint placement (数据点 ts, 与 SQL 一致)
                data.add(new BasicTsKvEntry(ts, IotdbSchemaUtil.toKvEntry(query.getKey(), f)));
                Field maxTime = r.getFields().size() > 1 ? r.getFields().get(1) : null;
                if (maxTime != null && maxTime.getDataType() != null) {
                    lastTs = Math.max(lastTs, maxTime.getLongV());
                }
            }
        }
        return new ReadTsKvQueryResult(query.getId(), data, lastTs);
    }

    /**
     * 自然周/月/季度必须按 ThingsBoard 的 intervalType + tzId 计算真实边界。不能把月近似成
     * 30 天、季度近似成 90 天，否则月长和 DST 切换时窗口漂移。日历聚合通常窗口数很少，
     * 仅该路径逐窗口查询；常用固定毫秒聚合仍走单 RPC 的 {@link #readFixedAggregated}。
     */
    private ReadTsKvQueryResult readCalendarAggregated(EntityId entityId, ReadTsKvQuery query) throws Exception {
        String fn = aggFunction(query.getAggregation());
        String q = IotdbSchemaUtil.quote(query.getKey());
        String device = IotdbSchemaUtil.devicePath(database, entityId);
        IntervalType intervalType = query.getAggParameters().getIntervalType();
        long rangeEnd = Math.max(query.getStartTs() + 1, query.getEndTs());
        long periodStart = query.getStartTs();
        long lastTs = query.getStartTs();
        List<TsKvEntry> data = new ArrayList<>();

        while (periodStart < rangeEnd) {
            long calculatedEnd = TimeUtils.calculateIntervalEnd(
                    periodStart, intervalType, query.getAggParameters().getTzId());
            if (calculatedEnd <= periodStart) {
                throw new IllegalStateException("Calendar aggregation did not advance: type=" + intervalType
                        + ", start=" + periodStart + ", calculatedEnd=" + calculatedEnd);
            }
            long periodEnd = Math.min(calculatedEnd, rangeEnd);
            String sql = "select " + fn + "(" + q + "), max_time(" + q + ") from " + device
                    + " where time >= " + periodStart + " and time < " + periodEnd;
            try (SessionDataSetWrapper wrapper = pool.executeQueryStatement(sql)) {
                while (wrapper.hasNext()) {
                    RowRecord row = wrapper.next();
                    Field value = row.getFields().isEmpty() ? null : row.getFields().get(0);
                    if (value == null || value.getDataType() == null) {
                        continue;
                    }
                    long midpoint = periodStart + (periodEnd - periodStart) / 2;
                    data.add(new BasicTsKvEntry(midpoint, IotdbSchemaUtil.toKvEntry(query.getKey(), value)));
                    Field maxTime = row.getFields().size() > 1 ? row.getFields().get(1) : null;
                    if (maxTime != null && maxTime.getDataType() != null) {
                        lastTs = Math.max(lastTs, maxTime.getLongV());
                    }
                }
            }
            periodStart = periodEnd;
        }
        return new ReadTsKvQueryResult(query.getId(), data, lastTs);
    }

    private static String aggFunction(Aggregation aggregation) {
        switch (aggregation) {
            case MIN:
                return "min_value";
            case MAX:
                return "max_value";
            case SUM:
                return "sum";
            case COUNT:
                return "count";
            case AVG:
            default:
                return "avg";
        }
    }
}
