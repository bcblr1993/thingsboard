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

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.stats.StatsFactory;
import org.thingsboard.server.dao.util.IotdbAnyDao;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-path observability for the IoTDB DAOs. The write path already exposes rich metrics
 * (see {@link IotdbWriteService}); before this, query latency/errors were invisible, so a
 * degraded read path could only be discovered from user-facing "no telemetry" reports.
 * <p>
 * Shared by {@link IotdbBaseTimeseriesDao} (raw/aggregation) and
 * {@link IotdbTimeseriesLatestDao} (latest). Exposes Micrometer gauges under {@code iotdbQuery}
 * and warns on slow queries.
 */
@Component
@IotdbAnyDao
@Slf4j
public class IotdbQueryMetrics {

    private static final String STATS_TYPE = "iotdbQuery";

    @Autowired(required = false)
    private StatsFactory statsFactory;

    @Value("${iotdb.read_slow_query_ms:1000}")
    private long slowQueryMs;

    private final LongAdder total = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder slow = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final LongAccumulator maxLatencyNanos = new LongAccumulator(Math::max, 0);

    @PostConstruct
    public void init() {
        if (statsFactory == null) {
            return; // standalone/benchmark wiring without Spring stats
        }
        statsFactory.createGauge(STATS_TYPE, "total", total, LongAdder::sum);
        statsFactory.createGauge(STATS_TYPE, "errors", errors, LongAdder::sum);
        statsFactory.createGauge(STATS_TYPE, "slow", slow, LongAdder::sum);
        statsFactory.createGauge(STATS_TYPE, "maxLatencyMs", maxLatencyNanos,
                a -> TimeUnit.NANOSECONDS.toMillis(a.get()));
    }

    /** Time a query, recording latency/error and warning if slow. Exceptions propagate. */
    public <T> T timed(String op, Object target, ThrowingSupplier<T> action) throws Exception {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            T result = action.get();
            ok = true;
            return result;
        } finally {
            long elapsed = System.nanoTime() - start;
            total.increment();
            latencyNanos.add(elapsed);
            maxLatencyNanos.accumulate(elapsed);
            if (!ok) {
                errors.increment();
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(elapsed);
            if (ms >= slowQueryMs) {
                slow.increment();
                log.warn("[IoTDB] slow {} query for {}: {}ms (>= {}ms)", op, target, ms, slowQueryMs);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // accessors for standalone assertions / tests
    public long total() {
        return total.sum();
    }

    public long errors() {
        return errors.sum();
    }

    public long slow() {
        return slow.sum();
    }
}
