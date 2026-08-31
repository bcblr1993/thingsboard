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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.tsfile.enums.TSDataType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.stats.StatsFactory;
import org.thingsboard.server.dao.util.IotdbAnyDao;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single shared entry point for all IoTDB writes. Owning one {@link IotdbTimeseriesWriteBuffer}
 * for both the historical and latest DAOs means that when both use IoTDB, the same
 * {@code (device, ts, key)} enqueued by {@code save()} and {@code saveLatest()} merges into one
 * physical write instead of doubling the write load.
 */
@Component
@IotdbAnyDao
@Slf4j
public class IotdbWriteService {

    private static final String STATS_TYPE = "iotdbWriteBuffer";

    @Autowired
    private IotdbSessionPoolConfig iotdb;
    @Autowired
    private StatsFactory statsFactory;

    @Value("${iotdb.write.shards:0}")
    private int writeShards;
    @Value("${iotdb.write.batch_size:1000}")
    private int batchSize;
    @Value("${iotdb.write.flush_interval_ms:1000}")
    private long flushIntervalMs;
    @Value("${iotdb.write.max_pending_per_shard:200000}")
    private int maxPendingPerShard;
    @Value("${iotdb.write.max_backpressure_wait_ms:0}")
    private long maxBackpressureWaitMs;
    @Value("${iotdb.write.stats_interval_ms:10000}")
    private long statsIntervalMs;

    private IotdbTimeseriesWriteBuffer buffer;
    private ScheduledExecutorService statsExecutor;
    // previous cumulative snapshot for per-window deltas in the periodic log
    private long lastAdded;
    private long lastWritten;
    private long lastFailed;
    private long lastRpcs;
    private long lastRpcFailures;
    private long lastBackpressure;
    private long lastTypeDrift;
    private long lastFlushNanos;

    @PostConstruct
    public void init() {
        buffer = new IotdbTimeseriesWriteBuffer(iotdb.getSessionPool(), writeShards, batchSize,
                flushIntervalMs, maxPendingPerShard, maxBackpressureWaitMs);
        registerMetrics();
        if (statsIntervalMs > 0) {
            statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iotdb-write-stats");
                t.setDaemon(true);
                return t;
            });
            statsExecutor.scheduleAtFixedRate(this::logStats, statsIntervalMs, statsIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void registerMetrics() {
        if (statsFactory == null) {
            // standalone harness (benchmarks) wires this bean by reflection without Spring
            return;
        }
        IotdbTimeseriesWriteBuffer.Stats s = buffer.getStats();
        statsFactory.createGauge(STATS_TYPE, "pendingPoints", buffer, IotdbTimeseriesWriteBuffer::pendingPoints);
        statsFactory.createGauge(STATS_TYPE, "addedPoints", s, IotdbTimeseriesWriteBuffer.Stats::addedPoints);
        statsFactory.createGauge(STATS_TYPE, "writtenPoints", s, IotdbTimeseriesWriteBuffer.Stats::writtenPoints);
        statsFactory.createGauge(STATS_TYPE, "failedPoints", s, IotdbTimeseriesWriteBuffer.Stats::failedPoints);
        statsFactory.createGauge(STATS_TYPE, "flushRpcs", s, IotdbTimeseriesWriteBuffer.Stats::flushRpcs);
        statsFactory.createGauge(STATS_TYPE, "rpcFailures", s, IotdbTimeseriesWriteBuffer.Stats::rpcFailures);
        statsFactory.createGauge(STATS_TYPE, "backpressureEvents", s, IotdbTimeseriesWriteBuffer.Stats::backpressureEvents);
        statsFactory.createGauge(STATS_TYPE, "typeDriftPoints", s, IotdbTimeseriesWriteBuffer.Stats::typeDriftPoints);
    }

    private void logStats() {
        try {
            IotdbTimeseriesWriteBuffer.Stats s = buffer.getStats();
            long added = s.addedPoints();
            long written = s.writtenPoints();
            long failed = s.failedPoints();
            long rpcs = s.flushRpcs();
            long rpcFailures = s.rpcFailures();
            long backpressure = s.backpressureEvents();
            long typeDrift = s.typeDriftPoints();
            long flushNanos = s.flushNanos();
            long pending = buffer.pendingPoints();

            long dAdded = added - lastAdded;
            long dWritten = written - lastWritten;
            long dFailed = failed - lastFailed;
            long dRpcs = rpcs - lastRpcs;
            long dRpcFailures = rpcFailures - lastRpcFailures;
            long dBackpressure = backpressure - lastBackpressure;
            long dTypeDrift = typeDrift - lastTypeDrift;
            long dFlushNanos = flushNanos - lastFlushNanos;
            lastAdded = added;
            lastWritten = written;
            lastFailed = failed;
            lastRpcs = rpcs;
            lastRpcFailures = rpcFailures;
            lastBackpressure = backpressure;
            lastTypeDrift = typeDrift;
            lastFlushNanos = flushNanos;

            long maxFlushMs = TimeUnit.NANOSECONDS.toMillis(s.maxFlushNanosAndReset());
            if (dAdded == 0 && dWritten == 0 && dFailed == 0 && pending == 0) {
                return; // fully idle window — keep the log quiet
            }
            long avgFlushMs = dRpcs > 0 ? TimeUnit.NANOSECONDS.toMillis(dFlushNanos / dRpcs) : 0;
            log.info("[IoTDB] write buffer: pending [{}] added [{}] written [{}] failed [{}] rpcs [{}] "
                            + "rpcFailures [{}] backpressure [{}] typeDrift [{}] flushAvg [{}ms] flushMax [{}ms]",
                    pending, dAdded, dWritten, dFailed, dRpcs, dRpcFailures, dBackpressure, dTypeDrift,
                    avgFlushMs, maxFlushMs);
            if (dRpcFailures > 0 || dBackpressure > 0 || dTypeDrift > 0) {
                log.warn("[IoTDB] write buffer degraded: rpcFailures [{}] backpressure [{}] typeDrift [{}] in the last window"
                                + " — check IoTDB health for RPC/backpressure; fix device telemetry types for typeDrift",
                        dRpcFailures, dBackpressure, dTypeDrift);
            }
        } catch (Throwable t) {
            log.warn("[IoTDB] failed to log write buffer stats", t);
        }
    }

    @PreDestroy
    public void stop() {
        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
        }
        if (buffer != null) {
            buffer.stop();
        }
    }

    public ListenableFuture<Integer> write(String device, String measurement, long ts,
                                           TSDataType type, Object value, int dataPoints) {
        return buffer.add(device, measurement, ts, type, value, dataPoints);
    }

    public ListenableFuture<Integer> writeBatch(String device,
                                                List<IotdbTimeseriesWriteBuffer.WritePoint> points,
                                                int dataPoints) {
        return buffer.addBatch(device, points, dataPoints);
    }
}
