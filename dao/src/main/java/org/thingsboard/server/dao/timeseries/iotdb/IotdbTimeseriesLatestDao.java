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
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;
import org.thingsboard.server.dao.timeseries.BatchedTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;
import org.thingsboard.server.dao.util.IotdbTsLatestDao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Apache IoTDB implementation of {@link TimeseriesLatestDao}, backed by IoTDB's native
 * {@code SELECT LAST} (LastCache). {@code saveLatest} writes through the shared
 * {@link IotdbWriteService}; when the historical store is also IoTDB the identical
 * {@code (device, ts, key)} point merges in the write buffer, so latest costs no extra write.
 */
@Component
@IotdbTsLatestDao
@Slf4j
public class IotdbTimeseriesLatestDao implements TimeseriesLatestDao, BatchedTimeseriesLatestDao {

    @Autowired
    private IotdbSessionPoolConfig iotdb;
    @Autowired
    private IotdbWriteService writeService;
    @Autowired
    private IotdbQueryMetrics queryMetrics;

    @Value("${iotdb.read_threads:0}")
    private int readThreads;
    // 读线程池有界队列容量(0=自动 threads*64)。满队后 fast-fail(AbortPolicy): 查询立即返回失败,
    // 绝不在调用线程(规则引擎/状态/订阅线程)内联执行, 避免阻塞(见 submitRead)。
    @Value("${iotdb.read_queue_capacity:0}")
    private int readQueueCapacity;
    @Value("${iotdb.latest_batch_size:500}")
    private int latestBatchSize;

    private SessionPool pool;
    private String database;
    private ListeningExecutorService readExecutor;

    @PostConstruct
    public void init() {
        // 读专用连接池: 与写入物理隔离, latest 查询洪峰(实体查询的 N×K SELECT LAST)不占写连接
        this.pool = iotdb.getReadSessionPool();
        this.database = iotdb.getDatabase();
        if (latestBatchSize <= 0) {
            latestBatchSize = 500;
        }
        int threads = readThreads > 0 ? readThreads : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        int queueCap = readQueueCapacity > 0 ? readQueueCapacity : threads * 64;
        // AbortPolicy(而非 CallerRuns): 队列满时拒绝, 由 submitRead 转成 fast-fail 的 failed
        // future。latest 查询的调用方是规则引擎/设备状态/订阅线程, 绝不能内联执行查询把它们
        // 阻塞至 query_timeout_ms(会导致 Rule Engine pack timeout / Kafka offset 延迟 / lag)。
        ThreadPoolExecutor exec = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCap), r -> new Thread(r, "iotdb-latest-read"),
                new ThreadPoolExecutor.AbortPolicy());
        this.readExecutor = MoreExecutors.listeningDecorator(exec);
    }

    /**
     * 提交读任务。队列满被拒时返回立即失败的 future(而非阻塞/内联执行), 保护调用线程
     * (规则引擎/设备状态/订阅线程) —— 上游按"查询失败"降级, 好过整个 pack 被拖到超时。
     */
    private <T> ListenableFuture<T> submitRead(java.util.concurrent.Callable<T> task) {
        try {
            return readExecutor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException rej) {
            warnReadRejectedThrottled();
            return Futures.immediateFailedFuture(new RuntimeException(
                    "[IoTDB] latest 读队列已满, 查询被拒绝(fast-fail 保护调用线程)。可增大 iotdb.read_queue_capacity 或 read_threads。", rej));
        }
    }

    private final java.util.concurrent.atomic.AtomicLong lastReadRejectWarnNs = new java.util.concurrent.atomic.AtomicLong();

    private void warnReadRejectedThrottled() {
        long now = System.nanoTime();
        long prev = lastReadRejectWarnNs.get();
        if (now - prev > 1_000_000_000L && lastReadRejectWarnNs.compareAndSet(prev, now)) {
            log.warn("[IoTDB] latest 读队列已满, 查询被 fast-fail 拒绝(保护规则引擎/状态/订阅线程免于阻塞)。"
                    + "持续出现说明读并发超出 read_threads/read_queue_capacity, 请调大或排查慢查询。");
        }
    }

    @PreDestroy
    public void stop() {
        if (readExecutor != null) {
            readExecutor.shutdown();
        }
    }

    // ---------------------------------------------------------------- save

    @Override
    public ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, TsKvEntry tsKvEntry) {
        String key = tsKvEntry.getKey();
        String reason = IotdbSchemaUtil.illegalKeyReason(key);
        if (reason != null) {
            if (IotdbSchemaUtil.shouldWarnIllegalOnce(key)) {
                log.warn("[IoTDB] latest 测点 key '{}' 无法存储({}) — 该点被丢弃。请修改测点命名。", key, reason);
            }
            // latest-only 保存不会经过历史批量路径。非法 key 不可能靠重试恢复，因此按“未产生
            // latest version”成功结算，避免 RETRY 队列形成永久毒丸；null 也会阻止 EDQS 更新。
            return Futures.immediateFuture(null);
        }
        String device = IotdbSchemaUtil.devicePath(database, entityId);
        TSDataType type = IotdbSchemaUtil.toIotdbType(tsKvEntry.getDataType());
        Object value = IotdbSchemaUtil.toIotdbValue(type, IotdbSchemaUtil.rawValue(tsKvEntry));
        // measurement 用 quote(key), 与历史 save 一致(同一 (device,ts,key) 才能在缓冲合并)
        return Futures.transform(
                writeService.write(device, IotdbSchemaUtil.quote(key), tsKvEntry.getTs(), type, value, tsKvEntry.getDataPoints()),
                i -> (Long) null, MoreExecutors.directExecutor());
    }

    // ---------------------------------------------------------------- read

    @Override
    public ListenableFuture<Optional<TsKvEntry>> findLatestOpt(TenantId tenantId, EntityId entityId, String key) {
        return submitRead(() -> queryMetrics.timed("latest", entityId,
                () -> Optional.ofNullable(readLast(entityId, key))));
    }

    @Override
    public ListenableFuture<TsKvEntry> findLatest(TenantId tenantId, EntityId entityId, String key) {
        return submitRead(() -> queryMetrics.timed("latest", entityId, () -> {
            TsKvEntry entry = readLast(entityId, key);
            return entry != null ? entry
                    : new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(key, null));
        }));
    }

    @Override
    public ListenableFuture<List<TsKvEntry>> findLatest(TenantId tenantId, EntityId entityId, Collection<String> keys) {
        List<String> requestedKeys = new ArrayList<>(keys);
        if (requestedKeys.isEmpty()) {
            return Futures.immediateFuture(Collections.emptyList());
        }
        return submitRead(() -> queryMetrics.timed("latestBatch", entityId,
                () -> readLatestBatch(entityId, requestedKeys)));
    }

    private List<TsKvEntry> readLatestBatch(EntityId entityId, List<String> requestedKeys) {
        List<String> uniqueKeys = new ArrayList<>(new LinkedHashSet<>(requestedKeys));
        Map<String, TsKvEntry> found = new LinkedHashMap<>(uniqueKeys.size());
        int batchSize = Math.max(1, latestBatchSize);
        for (int from = 0; from < uniqueKeys.size(); from += batchSize) {
            int to = Math.min(uniqueKeys.size(), from + batchSize);
            String measurements = uniqueKeys.subList(from, to).stream()
                    .map(IotdbSchemaUtil::quote)
                    .collect(java.util.stream.Collectors.joining(","));
            String sql = "select last " + measurements + " from "
                    + IotdbSchemaUtil.devicePath(database, entityId);
            try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
                while (w.hasNext()) {
                    TsKvEntry entry = parseLastRow(w.next());
                    if (entry != null) {
                        found.put(entry.getKey(), entry);
                    }
                }
            } catch (Exception e) {
                if (isPathAbsent(e)) {
                    log.debug("[IoTDB] findLatest batch has no series for {}", entityId);
                } else {
                    log.error("[IoTDB] findLatest batch failed for {}", entityId, e);
                    throw queryFailure("findLatestBatch", entityId, e);
                }
            }
        }
        long missingTs = System.currentTimeMillis();
        List<TsKvEntry> result = new ArrayList<>(requestedKeys.size());
        for (String key : requestedKeys) {
            TsKvEntry entry = found.get(key);
            result.add(entry != null ? entry : new BasicTsKvEntry(missingTs, new StringDataEntry(key, null)));
        }
        return result;
    }

    @Override
    public ListenableFuture<List<TsKvEntry>> findAllLatest(TenantId tenantId, EntityId entityId) {
        return submitRead(() -> queryMetrics.timed("latestAll", entityId, () -> {
            List<TsKvEntry> result = new ArrayList<>();
            String sql = "select last * from " + IotdbSchemaUtil.devicePath(database, entityId);
            try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
                while (w.hasNext()) {
                    TsKvEntry e = parseLastRow(w.next());
                    if (e != null) {
                        result.add(e);
                    }
                }
            } catch (Exception e) {
                if (isPathAbsent(e)) {
                    log.debug("[IoTDB] findAllLatest no series for {}", entityId);
                } else {
                    // 真实故障(连接/超时/认证/解析): 让 Future 失败, 前端看到错误而非"没有遥测"
                    log.error("[IoTDB] findAllLatest failed for {}", entityId, e);
                    throw queryFailure("findAllLatest", entityId, e);
                }
            }
            return result;
        }));
    }

    private TsKvEntry readLast(EntityId entityId, String key) {
        String sql = "select last " + IotdbSchemaUtil.quote(key) + " from " + IotdbSchemaUtil.devicePath(database, entityId);
        try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
            if (w.hasNext()) {
                return parseLastRow(w.next());
            }
        } catch (Exception e) {
            if (isPathAbsent(e)) {
                log.debug("[IoTDB] findLatest no series for {}.{}", entityId, key);
            } else {
                log.error("[IoTDB] findLatest failed for {}.{}", entityId, key, e);
                throw queryFailure("findLatest", entityId + "." + key, e);
            }
        }
        return null;
    }

    /**
     * A {@code SELECT LAST} row has columns: Time | Timeseries | Value | DataType.
     */
    private TsKvEntry parseLastRow(RowRecord row) {
        List<Field> fields = row.getFields();
        if (fields.size() < 3) {
            return null;
        }
        String timeseries = fields.get(0).getStringValue();
        String valueStr = fields.get(1).getStringValue();
        String dataType = fields.get(2).getStringValue();
        String key = leafKey(timeseries);
        return new BasicTsKvEntry(row.getTimestamp(), parseValue(key, valueStr, dataType));
    }

    /**
     * 区分"路径/序列不存在"(新设备无数据的正常空)与真实故障(连接/超时/认证/解析)。
     * 实测 IoTDB 1.3.7 对不存在路径返回空结果集而非抛异常, 故 catch 到的一般都是真实故障;
     * 此判断仅作跨版本防御——个别版本可能对不存在路径抛 "path does not exist"。
     */
    private static boolean isPathAbsent(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof StatementExecutionException) {
                String msg = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
                boolean namesSeriesPath = msg.contains("path [") || msg.contains("timeseries [");
                boolean reportsAbsent = msg.contains("does not exist") || msg.contains("is not exist");
                return namesSeriesPath && reportsAbsent;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException queryFailure(String what, Object target, Exception e) {
        return new RuntimeException("[IoTDB] " + what + " 查询失败 for " + target
                + " (连接/超时/认证/解析故障, 非'无数据'): " + e.getMessage(), e);
    }

    private static String leafKey(String timeseriesPath) {
        // 反引号感知(含点的 key 不能用 lastIndexOf 分割), 复用 IotdbSchemaUtil.leafKey
        return IotdbSchemaUtil.leafKey(timeseriesPath);
    }

    private static KvEntry parseValue(String key, String valueStr, String dataType) {
        if (valueStr == null || "null".equalsIgnoreCase(valueStr)) {
            return new StringDataEntry(key, null);
        }
        switch (dataType) {
            case "BOOLEAN":
                return new BooleanDataEntry(key, Boolean.parseBoolean(valueStr));
            case "INT32":
            case "INT64":
                return new LongDataEntry(key, Long.parseLong(valueStr));
            case "FLOAT":
            case "DOUBLE":
                return new DoubleDataEntry(key, Double.parseDouble(valueStr));
            case "STRING":
                // IoTDB STRING 类型专用于 TB 的 JSON(见 IotdbSchemaUtil.toIotdbType), 还原为 JSON
                return new JsonDataEntry(key, valueStr);
            case "TEXT":
            default:
                return new StringDataEntry(key, valueStr);
        }
    }

    // ---------------------------------------------------------------- remove

    @Override
    public ListenableFuture<TsKvLatestRemovingResult> removeLatest(TenantId tenantId, EntityId entityId, DeleteTsKvQuery query) {
        return submitRead(() -> {
            String key = query.getKey();
            TsKvEntry current = readLast(entityId, key);
            boolean removed = false;
            // 区间判定必须与下方 delete 的 [startTs, endTs) 完全一致(并与 SQL 参照实现对齐),
            // 否则 latest 恰在边界时会出现"删了数据但不重写 latest"或"没删却误重写"
            if (current != null && current.getValue() != null
                    && current.getTs() >= query.getStartTs() && current.getTs() < query.getEndTs()) {
                pool.executeNonQueryStatement("delete from " + IotdbSchemaUtil.measurementPath(database, entityId, key)
                        + " where time >= " + query.getStartTs() + " and time < " + query.getEndTs());
                removed = true;
            }
            if (removed && query.getRewriteLatestIfDeleted()) {
                TsKvEntry newLatest = readPreviousBefore(entityId, key, query.getStartTs());
                if (newLatest != null) {
                    return new TsKvLatestRemovingResult(newLatest, null);
                }
            }
            return new TsKvLatestRemovingResult(key, removed);
        });
    }

    private TsKvEntry readPreviousBefore(EntityId entityId, String key, long startTs) {
        String sql = "select " + IotdbSchemaUtil.quote(key) + " from " + IotdbSchemaUtil.devicePath(database, entityId)
                + " where time < " + startTs + " order by time desc limit 1";
        try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
            if (w.hasNext()) {
                RowRecord r = w.next();
                Field f = r.getFields().get(0);
                if (f != null && f.getDataType() != null) {
                    return new BasicTsKvEntry(r.getTimestamp(), IotdbSchemaUtil.toKvEntry(key, f));
                }
            }
        } catch (Exception e) {
            if (isPathAbsent(e)) {
                log.debug("[IoTDB] no previous latest for {}.{}", entityId, key);
            } else {
                log.error("[IoTDB] readPreviousBefore failed for {}.{}", entityId, key, e);
                throw queryFailure("removeLatest.rewrite", entityId + "." + key, e);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- key listing

    @Override
    public List<String> findAllKeysByEntityIds(TenantId tenantId, List<EntityId> entityIds) {
        Set<String> keys = new LinkedHashSet<>();
        for (EntityId entityId : entityIds) {
            String sql = "show timeseries " + IotdbSchemaUtil.devicePath(database, entityId) + ".**";
            try (SessionDataSetWrapper w = pool.executeQueryStatement(sql)) {
                while (w.hasNext()) {
                    keys.add(leafKey(w.next().getFields().get(0).getStringValue()));
                }
            } catch (Exception e) {
                if (isPathAbsent(e)) {
                    log.debug("[IoTDB] no timeseries for {}", entityId);
                } else {
                    log.error("[IoTDB] findAllKeysByEntityIds failed for {}", entityId, e);
                    throw queryFailure("findAllKeysByEntityIds", entityId, e);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    @Override
    public List<String> findAllKeysByDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId) {
        // Parity with the Cassandra NoSQL latest DAO — key enumeration by profile is served elsewhere.
        return Collections.emptyList();
    }
}
