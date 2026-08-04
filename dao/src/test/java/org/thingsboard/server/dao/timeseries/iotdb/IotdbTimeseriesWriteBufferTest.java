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
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.utils.Binary;
import org.apache.tsfile.write.record.Tablet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IotdbTimeseriesWriteBufferTest {

    /**
     * Pending drains slightly after a future completes ({@code complete()} wakes the getter
     * before {@code onFlushed} decrements the counter), so poll briefly instead of asserting
     * the exact instant — this is a test timing artifact, not a production concern.
     */
    private static void awaitPendingDrained(IotdbTimeseriesWriteBuffer buffer) throws InterruptedException {
        for (int i = 0; i < 200 && buffer.pendingPoints() != 0; i++) {
            Thread.sleep(5);
        }
        assertEquals(0, buffer.pendingPoints());
    }

    /**
     * Reproduces the sparse-write NPE: a batch with two rows (two timestamps) where a TEXT
     * measurement is present in only one row. tsfile's
     * {@code Tablet.getTotalValueOccupation()} iterates the Binary[] column without checking
     * the null bitmap, so any null slot crashes serialization inside insertAlignedTablet(s).
     * Verified still present in tsfile 1.1.3 (IoTDB 1.3.7).
     * The buffer must therefore fill {@link Binary#EMPTY_VALUE} placeholders for absent
     * TEXT cells.
     */
    @Test
    public void sparseTextColumnMustSerialize() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            String device = "root.tb.devices.test";
            // row ts=1: text + double; row ts=2: double only -> TEXT column has a hole
            buffer.add(device, "s1", 1L, TSDataType.TEXT, new Binary("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1);
            buffer.add(device, "d1", 1L, TSDataType.DOUBLE, 1.0, 1);
            ListenableFuture<Integer> last = buffer.add(device, "d1", 2L, TSDataType.DOUBLE, 2.0, 1);
            last.get(5, TimeUnit.SECONDS);

            ArgumentCaptor<Tablet> captor = ArgumentCaptor.forClass(Tablet.class);
            verify(pool, atLeastOnce()).insertAlignedTablet(captor.capture());
            Tablet tablet = captor.getValue();

            assertEquals(2, tablet.rowSize);
            // this is the exact call that threw NPE in production before the fix
            assertTrue(tablet.getTotalValueOccupation() > 0);

            // the absent TEXT cell must still be flagged null via the bitmap
            int textCol = -1;
            for (int c = 0; c < tablet.getSchemas().size(); c++) {
                if (tablet.getSchemas().get(c).getType() == TSDataType.TEXT) {
                    textCol = c;
                }
            }
            assertTrue(textCol >= 0);
            assertTrue(tablet.bitMaps[textCol].isMarked(1), "missing TEXT cell must be bitmap-marked");

            // stats: every accepted point was flushed, nothing pending, no failures
            IotdbTimeseriesWriteBuffer.Stats stats = buffer.getStats();
            assertEquals(3, stats.addedPoints());
            assertEquals(3, stats.writtenPoints());
            assertEquals(0, stats.failedPoints());
            assertEquals(0, stats.rpcFailures());
            awaitPendingDrained(buffer);
            assertTrue(stats.flushRpcs() >= 1);
        } finally {
            buffer.stop();
        }
    }

    /**
     * Poison isolation (build stage): a batch whose tablet cannot be built (e.g. type drift
     * causing ClassCastException in fillCell) must fail alone — same-cycle batches of other
     * devices still flush, and the shard thread must survive to serve subsequent writes.
     * Without isolation the CCE escaped flushAll() and killed the shard thread, permanently
     * stalling the whole shard and leaking backpressure permits.
     */
    @org.junit.jupiter.api.Test
    public void poisonBatchIsolatedAndShardSurvives() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            // poison: DOUBLE column fed a String value -> ClassCastException inside toTablet
            ListenableFuture<Integer> poison =
                    buffer.add("root.tb.devices.poison", "d1", 1L, TSDataType.DOUBLE, "not-a-double", 1);
            ListenableFuture<Integer> good =
                    buffer.add("root.tb.devices.good", "d1", 1L, TSDataType.DOUBLE, 1.0, 1);

            assertEquals(1, (int) good.get(5, TimeUnit.SECONDS), "innocent device must still flush");
            Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> poison.get(5, TimeUnit.SECONDS)).getCause();
            assertTrue(cause instanceof ClassCastException);

            // shard thread must still be alive: a later write on this shard must complete
            ListenableFuture<Integer> after =
                    buffer.add("root.tb.devices.after", "d1", 2L, TSDataType.DOUBLE, 2.0, 1);
            assertEquals(1, (int) after.get(5, TimeUnit.SECONDS), "shard thread must survive the poison batch");
            awaitPendingDrained(buffer);
        } finally {
            buffer.stop();
        }
    }

    /**
     * Poison isolation (write stage): when the multi-device chunk RPC fails, the buffer must
     * degrade to per-device retries so innocent devices still land instead of failing with
     * the poison device.
     */
    @org.junit.jupiter.api.Test
    public void chunkFailureDegradesToPerDeviceRetry() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doThrow(new RuntimeException("chunk-fail"))
                .when(pool).insertAlignedTablets(org.mockito.ArgumentMatchers.anyMap());
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            ListenableFuture<Integer> a =
                    buffer.add("root.tb.devices.a", "d1", 1L, TSDataType.DOUBLE, 1.0, 1);
            ListenableFuture<Integer> b =
                    buffer.add("root.tb.devices.b", "d1", 1L, TSDataType.DOUBLE, 2.0, 1);
            assertEquals(1, (int) a.get(5, TimeUnit.SECONDS));
            assertEquals(1, (int) b.get(5, TimeUnit.SECONDS));
            verify(pool, org.mockito.Mockito.times(2))
                    .insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
            awaitPendingDrained(buffer);
        } finally {
            buffer.stop();
        }
    }

    /**
     * Systemic failure must fail fast: when per-device retries keep hitting *connection*
     * errors (node/network down), stop probing after 3 consecutive failures instead of eating
     * (devices x rpc-timeout) on the shard thread; remaining batches fail with the chunk error.
     */
    @org.junit.jupiter.api.Test
    public void systemicConnectionFailureFailsFastAfterProbes() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doThrow(new org.apache.iotdb.rpc.IoTDBConnectionException("all-down"))
                .when(pool).insertAlignedTablets(org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.doThrow(new org.apache.iotdb.rpc.IoTDBConnectionException("all-down"))
                .when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            java.util.List<ListenableFuture<Integer>> futures = new java.util.ArrayList<>();
            for (int d = 0; d < 5; d++) {
                futures.add(buffer.add("root.tb.devices.d" + d, "k", 1L, TSDataType.DOUBLE, 1.0, 1));
            }
            for (ListenableFuture<Integer> f : futures) {
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            }
            // only 3 probe retries despite 5 devices — fail-fast kicked in on connection errors
            verify(pool, org.mockito.Mockito.times(3))
                    .insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
            awaitPendingDrained(buffer);
        } finally {
            buffer.stop();
        }
    }

    /**
     * 跨批类型冲突是不可重试数据错误：冲突点返回 0、消息成功，后面的健康设备继续落库；
     * 不能失败 Future 让 RETRY_* 队列永久重放。
     */
    @org.junit.jupiter.api.Test
    public void consecutivePoisonDevicesDoNotAbortRemaining() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doThrow(new org.apache.iotdb.rpc.StatementExecutionException("chunk-poison"))
                .when(pool).insertAlignedTablets(org.mockito.ArgumentMatchers.anyMap());
        // 前 4 个设备(d0..d3)始终类型冲突，第 5 个(d4)成功。冲突设备会按 measurement
        // 再试一次以精确隔离，因此 mock 必须按 device 判断，而不是依赖调用顺序。
        org.mockito.Mockito.doAnswer(invocation -> {
            Tablet tablet = invocation.getArgument(0);
            if (!tablet.deviceId.contains("d4")) {
                throw new org.apache.iotdb.rpc.StatementExecutionException(
                        "Data type is not consistent, registered type INT64, inserting type TEXT");
            }
            return null;
        }).when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            java.util.List<ListenableFuture<Integer>> futures = new java.util.ArrayList<>();
            for (int d = 0; d < 5; d++) {
                futures.add(buffer.add("root.tb.devices.d" + d, "k", 1L, TSDataType.DOUBLE, 1.0, 1));
            }
            // d0..d3 冲突点被不可重试丢弃并成功结算为0，d4正常写入1。
            for (int d = 0; d < 4; d++) {
                assertEquals(0, (int) futures.get(d).get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, (int) futures.get(4).get(5, TimeUnit.SECONDS), "healthy device after poisons must still land");
            // 5次逐设备定位 + 4次冲突 measurement 精确复核。
            verify(pool, org.mockito.Mockito.times(9))
                    .insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
            awaitPendingDrained(buffer);
            assertEquals(4, buffer.getStats().typeDriftPoints());
        } finally {
            buffer.stop();
        }
    }

    /**
     * 批内类型漂移隔离: 同设备同 key 先 LONG 后 STRING, 只丢弃漂移点, 同设备其他 key 与
     * 该 key 的首见类型点正常落库; 背压计数不泄漏(丢弃点显式释放)。
     */
    @Test
    public void intraBatchTypeDriftIsolatesOnlyOffendingPoint() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            String device = "root.tb.devices.drift";
            // k 首见 LONG -> 建 INT64 列; 其后 k 上报 TEXT -> 类型漂移, 应只失败该点
            ListenableFuture<Integer> good = buffer.add(device, "k", 1L, TSDataType.INT64, 1L, 1);
            ListenableFuture<Integer> other = buffer.add(device, "other", 1L, TSDataType.DOUBLE, 2.0, 1);
            ListenableFuture<Integer> drift = buffer.add(device, "k", 2L, TSDataType.TEXT,
                    new Binary("oops".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1);

            assertEquals(0, (int) drift.get(5, TimeUnit.SECONDS),
                    "drift is non-retryable: point is dropped but message future succeeds");

            // 同 key 首见类型点 + 同设备其他 key 正常落库
            assertEquals(1, (int) good.get(5, TimeUnit.SECONDS));
            assertEquals(1, (int) other.get(5, TimeUnit.SECONDS));

            IotdbTimeseriesWriteBuffer.Stats stats = buffer.getStats();
            assertEquals(3, stats.addedPoints());
            assertEquals(2, stats.writtenPoints());
            assertEquals(1, stats.failedPoints());
            assertEquals(1, stats.typeDriftPoints());
            assertEquals(0, stats.rpcFailures(), "drift is isolated before flush, no RPC failure");
            awaitPendingDrained(buffer); // 丢弃点已释放背压计数, 不泄漏
        } finally {
            buffer.stop();
        }
    }

    @Test
    public void intraBatchTypeDriftKeepsWholeTelemetryMessageSuccessful() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            List<IotdbTimeseriesWriteBuffer.WritePoint> points = List.of(
                    new IotdbTimeseriesWriteBuffer.WritePoint("k", 1L, TSDataType.INT64, 1L, 1),
                    new IotdbTimeseriesWriteBuffer.WritePoint("k", 2L, TSDataType.TEXT,
                            new Binary("drift".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1),
                    new IotdbTimeseriesWriteBuffer.WritePoint("other", 1L, TSDataType.DOUBLE, 2.0, 1));

            ListenableFuture<Integer> result = buffer.addBatch("root.tb.devices.same-message", points, 3);

            assertEquals(2, (int) result.get(5, TimeUnit.SECONDS));
            awaitPendingDrained(buffer);
            assertEquals(2, buffer.getStats().writtenPoints());
            assertEquals(1, buffer.getStats().failedPoints());
            assertEquals(1, buffer.getStats().typeDriftPoints());
        } finally {
            buffer.stop();
        }
    }

    /**
     * 一个原始消息中存在跨批类型冲突时，只丢弃冲突 measurement，其余 measurement 必须写入；
     * 整条消息成功返回实际写入的数据点数，避免 RETRY_* Kafka 永久毒丸。
     */
    @Test
    public void persistedTypeConflictDropsOnlyOffendingMeasurementAndMessageSucceeds() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Tablet tablet = invocation.getArgument(0);
            if (tablet.getSchemas().size() > 1
                    || "bad".equals(tablet.getSchemas().get(0).getMeasurementId())) {
                throw new org.apache.iotdb.rpc.StatementExecutionException(
                        "Data type of bad is not consistent, registered type INT64, inserting type TEXT");
            }
            return null;
        }).when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));

        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            List<IotdbTimeseriesWriteBuffer.WritePoint> points = List.of(
                    new IotdbTimeseriesWriteBuffer.WritePoint("bad", 1L, TSDataType.TEXT,
                            new Binary("x".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1),
                    new IotdbTimeseriesWriteBuffer.WritePoint("good", 1L, TSDataType.DOUBLE, 2.0, 1));

            ListenableFuture<Integer> result = buffer.addBatch("root.tb.devices.persisted-drift", points, 2);

            assertEquals(1, (int) result.get(5, TimeUnit.SECONDS));
            awaitPendingDrained(buffer);
            assertEquals(2, buffer.getStats().addedPoints());
            assertEquals(1, buffer.getStats().writtenPoints());
            assertEquals(1, buffer.getStats().failedPoints());
            assertEquals(1, buffer.getStats().typeDriftPoints());
            verify(pool, org.mockito.Mockito.times(3))
                    .insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        } finally {
            buffer.stop();
        }
    }

    /**
     * 停止后写入必须 fast-fail: 分片线程已退出, 若仍入队则 future 永不完成(上游 offset 提交
     * 不了 / 停机卡住)。add() 在 stop() 之后应立即返回失败的 future。
     */
    @Test
    public void addAfterStopFailsFastNotHang() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        buffer.stop();

        ListenableFuture<Integer> f =
                buffer.add("root.tb.devices.x", "k", 1L, TSDataType.DOUBLE, 1.0, 1);
        Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> f.get(2, TimeUnit.SECONDS)).getCause();
        assertTrue(cause instanceof IllegalStateException, "must fail with IllegalStateException");
        assertTrue(cause.getMessage().contains("stopped"), cause.getMessage());
    }

    /**
     * 兜底清队: 即使某点因竞态在停止后才滞留队列, stop() 的 join 后清队也必须失败其 future,
     * 绝不遗留永不完成的 future。这里直接向已停止 buffer 的分片队列注入一个残留点, 再触发清队。
     */
    @Test
    public void stopDrainsStragglerPointsSoFuturesNeverHang() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        buffer.stop(); // 线程退出

        // 反射注入一个"线程退出后才入队"的残留点(模拟竞态), 其 future 尚未完成
        java.lang.reflect.Field shardField = IotdbTimeseriesWriteBuffer.class.getDeclaredField("shard");
        shardField.setAccessible(true);
        Object[] shards = (Object[]) shardField.get(buffer);
        Object shard0 = shards[0];
        java.lang.reflect.Field queueField = shard0.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Queue<Object> queue = (java.util.Queue<Object>) queueField.get(shard0);

        Class<?> completionClass = Class.forName(
                "org.thingsboard.server.dao.timeseries.iotdb.IotdbTimeseriesWriteBuffer$BatchCompletion");
        java.lang.reflect.Constructor<?> completionCtor = completionClass.getDeclaredConstructors()[0];
        completionCtor.setAccessible(true);
        Object completion = completionCtor.newInstance(1, 1);
        java.lang.reflect.Field futureField = completionClass.getDeclaredField("future");
        futureField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ListenableFuture<Integer> straggler = (ListenableFuture<Integer>) futureField.get(completion);
        java.lang.reflect.Constructor<?> batchCtor = Class.forName(
                "org.thingsboard.server.dao.timeseries.iotdb.IotdbTimeseriesWriteBuffer$WriteBatch")
                .getDeclaredConstructors()[0];
        batchCtor.setAccessible(true);
        Object batch = batchCtor.newInstance("root.tb.devices.z", java.util.List.of(
                new IotdbTimeseriesWriteBuffer.WritePoint("k", 9L, TSDataType.DOUBLE, 1.0)), completion);
        queue.add(batch);

        assertTrue(!straggler.isDone(), "precondition: straggler future not yet completed");
        buffer.stop(); // 再次 stop 触发 join 后清队

        Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> straggler.get(2, TimeUnit.SECONDS)).getCause();
        assertTrue(cause instanceof IllegalStateException, "straggler future must be failed, not left hanging");
    }

    /**
     * 停止与写入并发时的记账一致性: 入队后复核用 queue.remove 与消费端 queue.poll 互斥, 保证每个
     * 已接受的点恰好被处理一次。回归"点已写库却报失败 + size 双减为负"的竞态: 停止全部settle后,
     * pendingPoints 必须恰为 0(双减会为负), 且每个返回的 future 都必须完成(不挂起)。
     */
    @Test
    public void concurrentAddDuringStopKeepsAccountingConsistent() throws Exception {
        SessionPool pool = mock(SessionPool.class); // flush 立即成功(void)
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 4, 64, 2, 1_000_000);
        java.util.List<ListenableFuture<Integer>> futures = new java.util.concurrent.CopyOnWriteArrayList<>();
        final int total = 5000;
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                futures.add(buffer.add("root.tb.d" + (i % 16), "k", i, TSDataType.DOUBLE, 1.0, 1));
            }
        });
        producer.start();
        Thread.sleep(2);   // 让部分点先入队/在途, 再与停止并发
        buffer.stop();     // 与 producer 并发
        producer.join();

        // 双减会让 size 变负; 泄漏会让其为正。正确记账下所有点结算后恰为 0。
        assertEquals(0, buffer.pendingPoints(), "pendingPoints must settle to exactly 0 (no double-decrement/leak)");
        for (ListenableFuture<Integer> f : futures) {
            assertTrue(f.isDone(), "every returned future must complete (no hang)");
        }
    }

    /**
     * Bounded backpressure: when a shard stays saturated and a stuck flush never drains it,
     * add() must not park the caller (a rule-engine thread) forever. With
     * maxBackpressureWaitMs > 0 the point fails fast and the caller is released.
     */
    @org.junit.jupiter.api.Test
    public void backpressureTimeoutReleasesCaller() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        // flush blocks forever → shard never drains → size stays at the cap
        final java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            block.await();
            return null;
        }).when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        // maxPendingPerShard=1 so the 2nd point hits backpressure; timeout 300ms
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 1, 300);
        try {
            buffer.add("root.tb.devices.x", "d1", 1L, TSDataType.DOUBLE, 1.0, 1); // fills the shard
            long t0 = System.currentTimeMillis();
            ListenableFuture<Integer> blocked =
                    buffer.add("root.tb.devices.x", "d1", 2L, TSDataType.DOUBLE, 2.0, 1);
            Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> blocked.get(5, TimeUnit.SECONDS)).getCause();
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(cause.getMessage().contains("backpressure wait exceeded"));
            assertTrue(elapsed >= 250 && elapsed < 3000, "must fail fast around the 300ms timeout, was " + elapsed);
        } finally {
            block.countDown();
            buffer.stop();
        }
    }

    /**
     * A failed flush must complete the futures exceptionally, count the points as failed and
     * release them from the pending counter (otherwise backpressure would leak permits and
     * eventually block producers forever).
     */
    @Test
    public void failedFlushReleasesPendingAndCountsFailure() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            ListenableFuture<Integer> f =
                    buffer.add("root.tb.devices.fail", "d1", 1L, TSDataType.DOUBLE, 1.0, 1);
            Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> f.get(5, TimeUnit.SECONDS)).getCause();
            assertEquals("boom", cause.getMessage());

            IotdbTimeseriesWriteBuffer.Stats stats = buffer.getStats();
            assertEquals(1, stats.addedPoints());
            assertEquals(0, stats.writtenPoints());
            assertEquals(1, stats.failedPoints());
            assertTrue(stats.rpcFailures() >= 1);
            awaitPendingDrained(buffer);
        } finally {
            buffer.stop();
        }
    }

    @Test
    public void nonTypeStatementErrorStillFailsMessage() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        org.mockito.Mockito.doThrow(new org.apache.iotdb.rpc.StatementExecutionException("No permissions"))
                .when(pool).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            ListenableFuture<Integer> result =
                    buffer.add("root.tb.devices.denied", "k", 1L, TSDataType.DOUBLE, 1.0, 1);
            Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)).getCause();
            assertTrue(cause instanceof org.apache.iotdb.rpc.StatementExecutionException);
            assertEquals(0, buffer.getStats().typeDriftPoints(),
                    "only explicit type incompatibility may be swallowed as non-retryable data error");
            awaitPendingDrained(buffer);
        } finally {
            buffer.stop();
        }
    }

    @Test
    public void batchUsesOneFutureAndReturnsCombinedDataPointCount() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 10, 100000);
        try {
            java.util.List<IotdbTimeseriesWriteBuffer.WritePoint> points = java.util.List.of(
                    new IotdbTimeseriesWriteBuffer.WritePoint("k1", 1L, TSDataType.DOUBLE, 1.0),
                    new IotdbTimeseriesWriteBuffer.WritePoint("k2", 1L, TSDataType.INT64, 2L),
                    new IotdbTimeseriesWriteBuffer.WritePoint("k3", 1L, TSDataType.BOOLEAN, true));

            ListenableFuture<Integer> result = buffer.addBatch("root.tb.devices.batch", points, 3);

            assertEquals(3, (int) result.get(5, TimeUnit.SECONDS));
            assertEquals(3, buffer.getStats().addedPoints()); // 入队即计, get 返回后已稳定
            // writtenPoints/failedPoints 在 onFlushed 累加, 而 complete()(唤醒 future)先于 onFlushed,
            // 故必须等 pending 排空后再断言(否则与记账线程竞态, 偶发读到 0)。
            awaitPendingDrained(buffer);
            assertEquals(3, buffer.getStats().writtenPoints());
            assertEquals(0, buffer.getStats().failedPoints());
            verify(pool, atLeastOnce()).insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        } finally {
            buffer.stop();
        }
    }

    /**
     * A telemetry message larger than the per-shard pending cap used to wait forever because
     * {@code pointCount > maxPendingPerShard} could never become false, even on an empty shard.
     * It must now be admitted in bounded chunks while retaining one completion future.
     */
    @Test
    public void oversizedBatchIsChunkedAndCompletesWithOneFuture() throws Exception {
        SessionPool pool = mock(SessionPool.class);
        IotdbTimeseriesWriteBuffer buffer =
                new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 5, 2);
        try {
            java.util.List<IotdbTimeseriesWriteBuffer.WritePoint> points = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                points.add(new IotdbTimeseriesWriteBuffer.WritePoint(
                        "k" + i, 1L, TSDataType.DOUBLE, (double) i));
            }

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(3), () -> {
                        ListenableFuture<Integer> result =
                                buffer.addBatch("root.tb.devices.oversized", points, 5);
                        assertEquals(5, (int) result.get(2, TimeUnit.SECONDS));
                    });

            awaitPendingDrained(buffer);
            assertEquals(5, buffer.getStats().addedPoints());
            assertEquals(5, buffer.getStats().writtenPoints());
            assertEquals(0, buffer.getStats().failedPoints());
            verify(pool, org.mockito.Mockito.atLeast(3))
                    .insertAlignedTablet(org.mockito.ArgumentMatchers.any(Tablet.class));
        } finally {
            buffer.stop();
        }
    }

    @Test
    public void nonPositivePendingCapIsRejectedAtStartup() {
        SessionPool pool = mock(SessionPool.class);
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new IotdbTimeseriesWriteBuffer(pool, 1, 1000, 5, 0));
        assertTrue(error.getMessage().contains("maxPendingPerShard"));
    }
}
