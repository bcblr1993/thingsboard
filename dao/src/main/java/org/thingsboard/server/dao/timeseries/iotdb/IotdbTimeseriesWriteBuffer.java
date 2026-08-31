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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.utils.BitMap;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.MeasurementSchema;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * High-throughput write path for IoTDB.
 * <p>
 * ThingsBoard calls {@code save()} one {@code TsKvEntry} at a time. To reach IoTDB's
 * batch-oriented performance ceiling, points are sharded by device, merged by
 * {@code (device, ts)} into aligned rows and flushed as {@code insertAlignedTablet}
 * either when a device reaches {@code batchSize} distinct-timestamp rows or when the
 * flush interval elapses. Each buffered point's future completes on successful flush.
 * <p>
 * Durability: this is a pure in-memory buffer — points not yet flushed are lost on a
 * crash (accepted trade-off for maximum throughput).
 */
@Slf4j
public class IotdbTimeseriesWriteBuffer {

    // 单次 insertAlignedTablets RPC 的上限(防止消息过大)：总单元格数 / 设备数
    private static final long MAX_CELLS_PER_RPC = 500_000;
    private static final int MAX_DEVICES_PER_RPC = 1000;
    // chunk 失败降级重试时, 连续失败多少个设备后判定为系统性故障并停止重试
    private static final int MAX_RETRY_PROBES = 3;

    private final SessionPool pool;
    private final int shards;
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxPendingPerShard;
    // 背压等待上限(ms)。0 = 无限等(默认): 反压如实传导到 Kafka(lag 可恢复、不丢数据)。
    // >0 = 有上限: 超时后快速失败该点、释放调用线程(避免规则引擎线程被长期占用, 代价是丢该点)。
    private final long maxBackpressureWaitMs;
    private final Shard[] shard;
    private final Stats stats = new Stats();
    private final java.util.concurrent.atomic.AtomicLong lastTypeDriftWarnNs =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;

    /**
     * Hot-path counters (LongAdder: no cross-shard contention). Cumulative since startup;
     * consumers (gauges / periodic log) compute their own deltas.
     */
    public static final class Stats {
        final LongAdder addedPoints = new LongAdder();
        final LongAdder writtenPoints = new LongAdder();
        final LongAdder failedPoints = new LongAdder();
        final LongAdder flushRpcs = new LongAdder();
        final LongAdder rpcFailures = new LongAdder();
        final LongAdder backpressureEvents = new LongAdder();
        final LongAdder typeDriftPoints = new LongAdder();
        final LongAdder flushNanos = new LongAdder();
        final LongAccumulator maxFlushNanos = new LongAccumulator(Math::max, 0);

        public long addedPoints() {
            return addedPoints.sum();
        }

        public long writtenPoints() {
            return writtenPoints.sum();
        }

        public long failedPoints() {
            return failedPoints.sum();
        }

        public long flushRpcs() {
            return flushRpcs.sum();
        }

        public long rpcFailures() {
            return rpcFailures.sum();
        }

        public long backpressureEvents() {
            return backpressureEvents.sum();
        }

        public long typeDriftPoints() {
            return typeDriftPoints.sum();
        }

        public long flushNanos() {
            return flushNanos.sum();
        }

        /** Max single-flush latency since the last call (resets on read). */
        public long maxFlushNanosAndReset() {
            return maxFlushNanos.getThenReset();
        }
    }

    public Stats getStats() {
        return stats;
    }

    /** Points accepted but not yet flushed to IoTDB (queued + batched), across all shards. */
    public long pendingPoints() {
        long sum = 0;
        for (Shard s : shard) {
            sum += s.size.get();
        }
        return sum;
    }

    public IotdbTimeseriesWriteBuffer(SessionPool pool, int shards, int batchSize,
                                      long flushIntervalMs, int maxPendingPerShard) {
        this(pool, shards, batchSize, flushIntervalMs, maxPendingPerShard, 0);
    }

    public IotdbTimeseriesWriteBuffer(SessionPool pool, int shards, int batchSize,
                                      long flushIntervalMs, int maxPendingPerShard, long maxBackpressureWaitMs) {
        if (maxPendingPerShard <= 0) {
            throw new IllegalArgumentException("maxPendingPerShard must be greater than 0");
        }
        this.pool = pool;
        this.shards = shards > 0 ? shards : Runtime.getRuntime().availableProcessors();
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.maxPendingPerShard = maxPendingPerShard;
        this.maxBackpressureWaitMs = maxBackpressureWaitMs;
        this.shard = new Shard[this.shards];
        for (int i = 0; i < this.shards; i++) {
            shard[i] = new Shard(i);
            shard[i].thread.start();
        }
        log.info("[IoTDB] write buffer started: shards={}, batchSize={}, flushInterval={}ms, maxBackpressureWait={}ms",
                this.shards, batchSize, flushIntervalMs, maxBackpressureWaitMs);
    }

    /**
     * Enqueue a single data point. Returns a future that completes with {@code dataPoints}
     * once the point has been flushed to IoTDB.
     */
    public ListenableFuture<Integer> add(String device, String measurement, long ts,
                                         TSDataType type, Object value, int dataPoints) {
        return addBatch(device, List.of(new WritePoint(measurement, ts, type, value, dataPoints)), dataPoints);
    }

    /**
     * Enqueue all points from one ThingsBoard telemetry message with one completion future.
     * The physical writer still tracks every point for memory/backpressure accounting, but the
     * Rule Engine now allocates and completes one future per message instead of one per key.
     */
    public ListenableFuture<Integer> addBatch(String device, List<WritePoint> points, int dataPoints) {
        if (points.isEmpty()) {
            SettableFuture<Integer> empty = SettableFuture.create();
            empty.set(0);
            return empty;
        }
        int pointCount = points.size();
        BatchCompletion completion = new BatchCompletion(pointCount, dataPoints);
        // 停止后拒收: 分片线程可能已退出(退出条件是 running=false 且队列/pending 皆空), 若仍入队
        // 则该点无人消费、future 永不完成 —— 上游 Kafka offset 提交不了 / 停机被卡住。停机中直接
        // 快速失败, 让调用方 future 立即完成。
        if (!running) {
            completion.fail(pointCount, bufferStoppedEx(device));
            stats.failedPoints.add(pointCount);
            return completion.future;
        }
        int s = (device.hashCode() & 0x7fffffff) % shards;
        Shard target = shard[s];
        // 一个原始遥测消息可能比单分片水位还大。若整条消息一起参与条件判断，
        // pointCount > maxPendingPerShard 时即使分片为空也永远无法满足条件。这里把消息按水位
        // 分段入队，但所有分段共享同一个 BatchCompletion，仍保持“一条消息一个 Future”。
        // 每段都不超过水位，因此健康时可随 flush 逐段推进；IoTDB 故障时仍按配置进行背压。
        int offset = 0;
        while (offset < pointCount) {
            int chunkCount = Math.min(maxPendingPerShard, pointCount - offset);
            if (running && (long) target.size.get() + chunkCount > maxPendingPerShard) {
                stats.backpressureEvents.increment();
                long deadlineNanos = maxBackpressureWaitMs > 0
                        ? System.nanoTime() + maxBackpressureWaitMs * 1_000_000L : 0L;
                while (running && (long) target.size.get() + chunkCount > maxPendingPerShard) {
                    if (deadlineNanos > 0L && System.nanoTime() >= deadlineNanos) {
                        int rejected = pointCount - offset;
                        completion.fail(rejected, new RuntimeException("[IoTDB] backpressure wait exceeded "
                                + maxBackpressureWaitMs + "ms (shard " + s + " saturated at "
                                + maxPendingPerShard + ")"));
                        stats.failedPoints.add(rejected);
                        return completion.future;
                    }
                    LockSupport.parkNanos(100_000);
                }
            }
            if (!running) {
                int rejected = pointCount - offset;
                completion.fail(rejected, bufferStoppedEx(device));
                stats.failedPoints.add(rejected);
                return completion.future;
            }

            List<WritePoint> chunk = new ArrayList<>(points.subList(offset, offset + chunkCount));
            stats.addedPoints.add(chunkCount);
            target.size.addAndGet(chunkCount);
            WriteBatch enqueued = new WriteBatch(device, chunk, completion);
            target.queue.add(enqueued);
            offset += chunkCount;

            // 入队后复核: 若期间被停止, 只有**成功把该批次从队列移除**(证明分片线程尚未取走)
            // 才由本线程失败它并释放计数。remove 与消费端 poll 对同一节点互斥, 保证恰好一方处理。
            if (!running && target.queue.remove(enqueued)) {
                completion.fail(chunkCount, bufferStoppedEx(device));
                target.size.addAndGet(-chunkCount);
                stats.failedPoints.add(chunkCount);
            }
        }
        return completion.future;
    }

    private static IllegalStateException bufferStoppedEx(String device) {
        return new IllegalStateException("[IoTDB] write buffer stopped — point rejected/not flushed (device=" + device + ")");
    }

    public void stop() {
        running = false;
        for (Shard s : shard) {
            try {
                s.thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 兜底: 线程退出后, 清空各分片队列里可能因竞态残留、未被消费的点并失败其 future,
        // 确保绝无永不完成的 future(否则上游停机卡住 / offset 无法提交)。setException 幂等。
        for (Shard s : shard) {
            WriteBatch batch;
            while ((batch = s.queue.poll()) != null) {
                int count = batch.points().size();
                batch.completion().fail(count, bufferStoppedEx(batch.device()));
                s.size.addAndGet(-count);
                stats.failedPoints.add(count);
            }
        }
    }

    private final class Shard implements Runnable {
        final ConcurrentLinkedQueue<WriteBatch> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        final Thread thread;
        // device -> accumulated batch
        final Map<String, DeviceBatch> pending = new LinkedHashMap<>();
        long lastFlush = System.currentTimeMillis();

        Shard(int id) {
            thread = new Thread(this, "iotdb-write-shard-" + id);
            thread.setDaemon(true);
        }

        @Override
        public void run() {
            while (running || !queue.isEmpty() || !pending.isEmpty()) {
                try {
                    int drained = 0;
                    WriteBatch batch;
                    // note: `size` is NOT decremented here — a point stays counted until its
                    // batch is flushed, so backpressure covers batched-but-unflushed points too
                    while ((batch = queue.poll()) != null) {
                        accumulate(batch);
                        drained += batch.points().size();
                        if (drained >= 8192) {
                            break;
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= flushIntervalMs) {
                        flushAll();
                        lastFlush = now;
                    }
                    if (drained == 0 && running) {
                        LockSupport.parkNanos(200_000);
                    }
                } catch (Throwable t) {
                    // 最后一道保险: 任何未预期异常都不允许杀死 shard 线程 —— 线程死亡意味着
                    // 该分片永久停写, 且 size 计数无法释放, 背压会永久阻塞上游规则引擎线程
                    log.error("[IoTDB] shard loop error (thread kept alive)", t);
                }
            }
            flushAll();
        }

        void accumulate(WriteBatch writeBatch) {
            String device = writeBatch.device();
            for (WritePoint point : writeBatch.points()) {
                DeviceBatch batch = pending.computeIfAbsent(device, d -> new DeviceBatch());
                if (!batch.add(point, writeBatch.completion())) {
                    // 类型漂移是确定性、不可重试的数据错误。该点不入批并从消息成功计数中扣除，
                    // 但不能失败整条消息，否则 RETRY_* 队列会永久重放同一条 Kafka 毒丸。
                    size.decrementAndGet();
                    stats.failedPoints.increment();
                    stats.typeDriftPoints.increment();
                    warnTypeDriftThrottled(device, point, "in-memory batch type mismatch");
                    continue;
                }
                if (batch.rows.size() >= batchSize) {
                    flushOne(device, batch);
                    pending.remove(device);
                }
            }
        }

        void flushAll() {
            if (pending.isEmpty()) {
                return;
            }
            // 先快照并清空 pending: 即使后续任何环节异常, 同一 batch 也不会被重复 flush
            List<Map.Entry<String, DeviceBatch>> toFlush = new ArrayList<>(pending.entrySet());
            pending.clear();
            // 关键优化：多设备合并为一次 insertAlignedTablets RPC。
            // 稀疏场景(每设备每窗口只有 1~2 行)下逐设备单发 RPC 会导致 RPC 数爆炸。
            Map<String, Tablet> chunk = new LinkedHashMap<>();
            List<DeviceBatch> chunkBatches = new ArrayList<>();
            long cells = 0;
            for (Map.Entry<String, DeviceBatch> e : toFlush) {
                DeviceBatch batch = e.getValue();
                if (batch.rows.isEmpty()) {
                    continue;
                }
                Tablet tablet;
                try {
                    tablet = batch.toTablet(e.getKey());
                } catch (Throwable ex) {
                    // 毒丸隔离(构造期): 该设备批次数据异常(如同批类型漂移的 ClassCastException),
                    // 只让它自己失败, 不影响本轮其他设备, 更不允许异常逃逸杀死 shard 线程
                    log.error("[IoTDB] tablet build failed for device {} — poison batch isolated", e.getKey(), ex);
                    batch.fail(ex);
                    onFlushed(batch, false);
                    continue;
                }
                chunk.put(e.getKey(), tablet);
                chunkBatches.add(batch);
                cells += (long) batch.rows.size() * batch.schemas.size();
                if (cells >= MAX_CELLS_PER_RPC || chunk.size() >= MAX_DEVICES_PER_RPC) {
                    flushChunk(chunk, chunkBatches);
                    chunk = new LinkedHashMap<>();
                    chunkBatches = new ArrayList<>();
                    cells = 0;
                }
            }
            if (!chunk.isEmpty()) {
                flushChunk(chunk, chunkBatches);
            }
        }

        void flushChunk(Map<String, Tablet> tablets, List<DeviceBatch> batches) {
            long start = System.nanoTime();
            try {
                if (tablets.size() == 1) {
                    pool.insertAlignedTablet(tablets.values().iterator().next());
                } else {
                    pool.insertAlignedTablets(tablets);
                }
                recordFlush(start);
                for (DeviceBatch b : batches) {
                    b.complete();
                    onFlushed(b, true);
                }
            } catch (Throwable ex) {
                recordFlush(start);
                stats.rpcFailures.increment();
                if (tablets.size() == 1) {
                    String device = tablets.keySet().iterator().next();
                    DeviceBatch b = batches.get(0);
                    if (isTypeConflict(ex)) {
                        log.warn("[IoTDB] non-retryable type conflict for device {}: {}", device, ex.getMessage());
                        recoverTypeConflict(device, b, ex);
                    } else {
                        log.error("[IoTDB] flush failed for device {}", device, ex);
                        b.fail(ex);
                        onFlushed(b, false);
                    }
                } else {
                    // 毒丸隔离(写入期): 单个设备的服务端错误(如跨批类型冲突)会让整个 chunk 的
                    // RPC 失败, 连坐最多 999 个无辜设备。降级为逐设备单发重试, 只让真正的
                    // 毒丸设备失败。
                    log.warn("[IoTDB] chunk flush failed for {} devices — degrading to per-device retry: {}",
                            tablets.size(), ex.getMessage());
                    retryPerDevice(tablets, batches, ex);
                }
            }
        }

        /**
         * chunk 整体失败后的降级路径: 逐设备单发, 只让真正失败的设备失败。
         * <p>
         * 提前中止仅在**连接类**异常(网络/节点不可用)连续 {@code MAX_RETRY_PROBES} 次时触发 ——
         * 此时逐设备重试只会各自超时、白白拖住 shard 线程。服务端语句拒绝(如类型冲突这类
         * 单设备"毒丸")是快速失败且互相独立, **不计入**中止阈值, 必须继续隔离尝试其余设备,
         * 否则多个毒丸相邻就会连坐丢弃后面无辜设备的健康数据。
         */
        private void retryPerDevice(Map<String, Tablet> tablets, List<DeviceBatch> batches, Throwable chunkError) {
            int i = 0;
            int consecutiveConnFailures = 0;
            boolean systemicAbort = false;
            for (Map.Entry<String, Tablet> e : tablets.entrySet()) {
                DeviceBatch batch = batches.get(i++);
                if (systemicAbort) {
                    batch.fail(chunkError);
                    onFlushed(batch, false);
                    continue;
                }
                try {
                    pool.insertAlignedTablet(e.getValue());
                    batch.complete();
                    onFlushed(batch, true);
                    consecutiveConnFailures = 0;
                } catch (Throwable ex) {
                    if (isTypeConflict(ex)) {
                        log.warn("[IoTDB] non-retryable type conflict for device {} (isolated retry): {}",
                                e.getKey(), ex.getMessage());
                        recoverTypeConflict(e.getKey(), batch, ex);
                        consecutiveConnFailures = 0;
                    } else if (isConnectionError(ex)) {
                        log.error("[IoTDB] flush failed for device {} (isolated retry)", e.getKey(), ex);
                        batch.fail(ex);
                        onFlushed(batch, false);
                        if (++consecutiveConnFailures >= MAX_RETRY_PROBES) {
                            systemicAbort = true;
                            log.warn("[IoTDB] {} consecutive connection failures — aborting remaining {} devices in chunk",
                                    MAX_RETRY_PROBES, tablets.size() - i);
                        }
                    } else {
                        log.error("[IoTDB] flush failed for device {} (isolated retry)", e.getKey(), ex);
                        batch.fail(ex);
                        onFlushed(batch, false);
                        // 非连接类真实错误不累积, 继续尝试其余设备
                        consecutiveConnFailures = 0;
                    }
                }
            }
        }

        /** 区分连接类故障(系统性, 应尽快中止)与服务端语句拒绝(单设备毒丸, 应继续隔离)。 */
        private static boolean isConnectionError(Throwable ex) {
            for (Throwable t = ex; t != null; t = t.getCause()) {
                if (t instanceof org.apache.iotdb.rpc.IoTDBConnectionException
                        || t instanceof java.net.SocketException
                        || t instanceof java.util.concurrent.TimeoutException) {
                    return true;
                }
            }
            return false;
        }

        /**
         * IoTDB 已有序列类型冲突时，按 measurement 降级重试。只有被服务端再次明确判定为类型
         * 冲突的 measurement 才作为不可重试丢点成功结算；同设备其余 measurement 继续落库。
         * 权限、磁盘、连接、超时等异常仍失败 Future，绝不静默吞掉系统故障。
         */
        private void recoverTypeConflict(String device, DeviceBatch batch, Throwable originalError) {
            log.warn("[IoTDB] type conflict for device {} — retrying {} measurements independently: {}",
                    device, batch.schemas.size(), originalError.getMessage());
            boolean connectionFailed = false;
            Throwable connectionError = null;
            for (Map.Entry<String, DeviceBatch> entry : batch.splitByMeasurement().entrySet()) {
                DeviceBatch measurementBatch = entry.getValue();
                if (connectionFailed) {
                    measurementBatch.fail(connectionError);
                    onFlushed(measurementBatch, false);
                    continue;
                }
                long start = System.nanoTime();
                try {
                    pool.insertAlignedTablet(measurementBatch.toTablet(device));
                    recordFlush(start);
                    measurementBatch.complete();
                    onFlushed(measurementBatch, true);
                } catch (Throwable measurementError) {
                    recordFlush(start);
                    stats.rpcFailures.increment();
                    if (isTypeConflict(measurementError)) {
                        int dropped = measurementBatch.pointCount();
                        measurementBatch.dropAll();
                        onFlushed(measurementBatch, false);
                        stats.typeDriftPoints.add(dropped);
                        warnTypeDriftThrottled(device, measurementBatch.firstPoint(), measurementError.getMessage());
                    } else {
                        measurementBatch.fail(measurementError);
                        onFlushed(measurementBatch, false);
                        if (isConnectionError(measurementError)) {
                            connectionFailed = true;
                            connectionError = measurementError;
                        }
                    }
                }
            }
        }

        /** 仅识别 IoTDB 明确的类型不兼容错误；其他 StatementExecutionException 仍按失败处理。 */
        private static boolean isTypeConflict(Throwable ex) {
            for (Throwable t = ex; t != null; t = t.getCause()) {
                if (t instanceof org.apache.iotdb.rpc.StatementExecutionException) {
                    String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                    if (message.contains("data type is not consistent")
                            || (message.contains("data type of") && message.contains("not consistent"))
                            || message.contains("type mismatch")
                            || message.contains("type conflict")
                            || message.contains("incompatible data type")) {
                        return true;
                    }
                }
            }
            return false;
        }

        void flushOne(String device, DeviceBatch batch) {
            if (batch.rows.isEmpty()) {
                return;
            }
            long start = System.nanoTime();
            try {
                Tablet tablet = batch.toTablet(device);
                pool.insertAlignedTablet(tablet);
                recordFlush(start);
                batch.complete();
                onFlushed(batch, true);
            } catch (Throwable ex) {
                recordFlush(start);
                stats.rpcFailures.increment();
                if (isTypeConflict(ex)) {
                    log.warn("[IoTDB] non-retryable type conflict for device {}: {}", device, ex.getMessage());
                    recoverTypeConflict(device, batch, ex);
                } else {
                    log.error("[IoTDB] flush failed for device {}", device, ex);
                    batch.fail(ex);
                    onFlushed(batch, false);
                }
            }
        }

        private void recordFlush(long startNanos) {
            long elapsed = System.nanoTime() - startNanos;
            stats.flushRpcs.increment();
            stats.flushNanos.add(elapsed);
            stats.maxFlushNanos.accumulate(elapsed);
        }

        private void onFlushed(DeviceBatch batch, boolean success) {
            int points = batch.pointCount();
            size.addAndGet(-points);
            (success ? stats.writtenPoints : stats.failedPoints).add(points);
        }
    }

    /** 全局 MeasurementSchema 缓存：同名同类型的测点在所有设备/批次间复用同一对象 */
    private static final Map<String, MeasurementSchema> SCHEMA_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static MeasurementSchema cachedSchema(String measurement, TSDataType type) {
        return SCHEMA_CACHE.computeIfAbsent(measurement + ' ' + type.ordinal(),
                k -> new MeasurementSchema(measurement, type, encodingFor(type), CompressionType.LZ4));
    }

    /**
     * Accumulates one device's points, merging by timestamp into aligned rows.
     */
    private static final class DeviceBatch {
        // measurement -> stable column index
        final Map<String, Integer> columnIndex = new LinkedHashMap<>();
        final List<MeasurementSchema> schemas = new ArrayList<>();
        // ts -> row values (indexed by column)
        final LinkedHashMap<Long, Object[]> rows = new LinkedHashMap<>();
        // 保留每条原始消息实际进入本批的 WritePoint 引用。正常路径只增加一个 ArrayList/消息，
        // 不复制 WritePoint；类型冲突降级时可按 measurement 精确结算，避免丢弃同消息正常 key。
        final Map<BatchCompletion, List<WritePoint>> completionPoints = new IdentityHashMap<>();
        int pointCount;

        /**
         * @return {@code true} 已入批; {@code false} 因批内同 key 类型漂移被隔离丢弃。
         */
        boolean add(WritePoint p, BatchCompletion completion) {
            Integer col = columnIndex.get(p.measurement);
            if (col == null) {
                col = schemas.size();
                columnIndex.put(p.measurement, col);
                schemas.add(cachedSchema(p.measurement, p.type));
            } else if (schemas.get(col).getType() != p.type) {
                // 同批同 key 出现类型漂移(如先 LONG 后 STRING): 该点无法与已建列(首见类型)对齐,
                // 且 IoTDB 序列类型不可变。该点属于不可重试数据错误：扣除成功数据点并正常结算，
                // 不能让共享消息 Future 失败成为 Kafka 永久毒丸。
                completion.drop(1, p.dataPoints);
                return false;
            }
            Object[] row = rows.computeIfAbsent(p.ts, t -> new Object[Math.max(8, schemas.size())]);
            if (row.length <= col) {
                // 倍增扩容，避免宽设备下逐列 +1 的 O(n^2) 复制
                Object[] grown = new Object[Math.max(schemas.size(), row.length * 2)];
                System.arraycopy(row, 0, grown, 0, row.length);
                row = grown;
                rows.put(p.ts, row);
            }
            row[col] = p.value;
            completionPoints.computeIfAbsent(completion, ignored -> new ArrayList<>()).add(p);
            pointCount++;
            return true;
        }

        Tablet toTablet(String device) {
            int cols = schemas.size();
            Tablet tablet = new Tablet(device, schemas, rows.size());
            tablet.initBitMaps();
            BitMap[] bm = tablet.bitMaps;
            Object[] cellArrays = tablet.values;
            int r = 0;
            for (Map.Entry<Long, Object[]> e : rows.entrySet()) {
                tablet.addTimestamp(r, e.getKey());
                Object[] row = e.getValue();
                for (int c = 0; c < cols; c++) {
                    Object v = c < row.length ? row[c] : null;
                    if (v != null) {
                        // 直写类型数组，避免逐格按测点名哈希查找
                        fillCell(cellArrays[c], schemas.get(c).getType(), r, v);
                    } else {
                        bm[c].mark(r);
                        // TEXT/STRING 列必须填占位符：tsfile 的 Tablet.getTotalValueOccupation() 遍历
                        // Binary[] 时不检查 bitmap，数组中的 null 会导致序列化 NPE。两种类型底层都用
                        // Binary[] 承载(STRING 用于 JSON, 见 IotdbSchemaUtil)。tsfile 1.1.3 仍存在此缺陷。
                        TSDataType ct = schemas.get(c).getType();
                        if (ct == TSDataType.TEXT || ct == TSDataType.STRING) {
                            ((org.apache.tsfile.utils.Binary[]) cellArrays[c])[r] =
                                    org.apache.tsfile.utils.Binary.EMPTY_VALUE;
                        }
                    }
                }
                r++;
            }
            tablet.rowSize = r;
            return tablet;
        }

        private static void fillCell(Object colArray, TSDataType type, int r, Object v) {
            switch (type) {
                case DOUBLE:
                    ((double[]) colArray)[r] = (Double) v;
                    break;
                case INT64:
                    ((long[]) colArray)[r] = (Long) v;
                    break;
                case BOOLEAN:
                    ((boolean[]) colArray)[r] = (Boolean) v;
                    break;
                case FLOAT:
                    ((float[]) colArray)[r] = (Float) v;
                    break;
                case INT32:
                    ((int[]) colArray)[r] = (Integer) v;
                    break;
                case TEXT:
                default:
                    ((org.apache.tsfile.utils.Binary[]) colArray)[r] = (org.apache.tsfile.utils.Binary) v;
                    break;
            }
        }

        int pointCount() {
            return pointCount;
        }

        void complete() {
            for (Map.Entry<BatchCompletion, List<WritePoint>> completion : completionPoints.entrySet()) {
                completion.getKey().complete(completion.getValue().size());
            }
        }

        void fail(Throwable t) {
            for (Map.Entry<BatchCompletion, List<WritePoint>> completion : completionPoints.entrySet()) {
                completion.getKey().fail(completion.getValue().size(), t);
            }
        }

        void dropAll() {
            for (Map.Entry<BatchCompletion, List<WritePoint>> completion : completionPoints.entrySet()) {
                int droppedDataPoints = completion.getValue().stream().mapToInt(WritePoint::dataPoints).sum();
                completion.getKey().drop(completion.getValue().size(), droppedDataPoints);
            }
        }

        Map<String, DeviceBatch> splitByMeasurement() {
            Map<String, DeviceBatch> result = new LinkedHashMap<>();
            for (Map.Entry<BatchCompletion, List<WritePoint>> completion : completionPoints.entrySet()) {
                for (WritePoint point : completion.getValue()) {
                    DeviceBatch single = result.computeIfAbsent(point.measurement, ignored -> new DeviceBatch());
                    if (!single.add(point, completion.getKey())) {
                        throw new IllegalStateException(
                                "Unexpected type drift while splitting measurement " + point.measurement);
                    }
                }
            }
            return result;
        }

        WritePoint firstPoint() {
            return completionPoints.values().iterator().next().get(0);
        }
    }

    static TSEncoding encodingFor(TSDataType type) {
        switch (type) {
            case DOUBLE:
            case FLOAT:
                return TSEncoding.GORILLA;
            case INT64:
            case INT32:
                return TSEncoding.TS_2DIFF;
            case BOOLEAN:
                return TSEncoding.RLE;
            default:
                return TSEncoding.PLAIN;
        }
    }

    public record WritePoint(String measurement, long ts, TSDataType type, Object value, int dataPoints) {
        public WritePoint(String measurement, long ts, TSDataType type, Object value) {
            this(measurement, ts, type, value, 1);
        }
    }

    private static final class BatchCompletion {
        final SettableFuture<Integer> future = SettableFuture.create();
        final AtomicInteger remaining;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger acceptedDataPoints;

        BatchCompletion(int pointCount, int dataPoints) {
            this.remaining = new AtomicInteger(pointCount);
            this.acceptedDataPoints = new AtomicInteger(dataPoints);
        }

        void complete(int count) {
            finish(count, null);
        }

        void fail(int count, Throwable error) {
            finish(count, error);
        }

        void drop(int count, int droppedDataPoints) {
            acceptedDataPoints.addAndGet(-droppedDataPoints);
            finish(count, null);
        }

        private void finish(int count, Throwable error) {
            if (error != null) {
                failure.compareAndSet(null, error);
            }
            if (remaining.addAndGet(-count) == 0) {
                Throwable cause = failure.get();
                if (cause == null) {
                    future.set(Math.max(0, acceptedDataPoints.get()));
                } else {
                    future.setException(cause);
                }
            }
        }
    }

    private record WriteBatch(String device, List<WritePoint> points, BatchCompletion completion) {
    }

    private void warnTypeDriftThrottled(String device, WritePoint point, String reason) {
        long now = System.nanoTime();
        long prev = lastTypeDriftWarnNs.get();
        if (now - prev > 1_000_000_000L && lastTypeDriftWarnNs.compareAndSet(prev, now)) {
            log.warn("[IoTDB] type-drift point dropped as non-retryable: device={}, measurement={}, incomingType={}, reason={}. "
                            + "The telemetry message continues so Kafka RETRY strategy cannot form a poison pill.",
                    device, point.measurement(), point.type(), reason);
        }
    }
}
