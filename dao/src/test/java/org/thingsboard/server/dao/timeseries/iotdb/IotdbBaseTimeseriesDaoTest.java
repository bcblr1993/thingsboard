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

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.iotdb.session.pool.SessionPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.AggregationParams;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Raw-query limit handling must match the reference stores: {@code limit <= 0} MUST NOT be
 * interpreted as "fetch everything" (which would materialize an unbounded result set into the
 * TB heap and diverge from Cassandra/SQL). Cassandra returns empty (cursor is full at
 * limit<=0); SQL rejects it (PageRequest.ofSize requires >=1). We align with Cassandra:
 * empty result, and the store is never even queried.
 */
public class IotdbBaseTimeseriesDaoTest {

    private static final DeviceId DEVICE = new DeviceId(new UUID(0x3000L, 1));

    private SessionPool pool;
    private IotdbBaseTimeseriesDao dao;

    @BeforeEach
    public void setUp() throws Exception {
        pool = mock(SessionPool.class);
        dao = new IotdbBaseTimeseriesDao();
        set("pool", pool);
        set("database", "root.tb");
        ListeningExecutorService direct =
                MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
        set("readExecutor", direct);
        set("queryMetrics", TestMetrics.create());
    }

    private void set(String field, Object value) throws Exception {
        java.lang.reflect.Field f = IotdbBaseTimeseriesDao.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(dao, value);
    }

    private ReadTsKvQuery rawQuery(int limit) {
        return new BaseReadTsKvQuery("temp", 0L, 1000L, 0L, limit, Aggregation.NONE, "ASC");
    }

    @Test
    public void limitZeroReturnsEmptyAndDoesNotQuery() throws Exception {
        ReadTsKvQueryResult r = dao.findAllAsync(null, DEVICE, rawQuery(0)).get(5, TimeUnit.SECONDS);
        assertTrue(r.getData().isEmpty(), "limit=0 must return empty (aligned with Cassandra)");
        // the store must not even be hit — no risk of unbounded materialization
        verify(pool, never()).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void negativeLimitReturnsEmptyAndDoesNotQuery() throws Exception {
        ReadTsKvQueryResult r = dao.findAllAsync(null, DEVICE, rawQuery(-1)).get(5, TimeUnit.SECONDS);
        assertTrue(r.getData().isEmpty());
        verify(pool, never()).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void singleSaveIllegalKeyIsDroppedWithoutFailedFuture() throws Exception {
        TsKvEntry illegal = new BasicTsKvEntry(1L, new DoubleDataEntry("time", 42.0));

        assertEquals(0, dao.save(null, DEVICE, illegal, 0L).get(5, TimeUnit.SECONDS));
    }

    @Test
    public void positiveLimitIsAppliedToSql() throws Exception {
        org.apache.iotdb.isession.pool.SessionDataSetWrapper empty =
                mock(org.apache.iotdb.isession.pool.SessionDataSetWrapper.class);
        org.mockito.Mockito.when(empty.hasNext()).thenReturn(false);
        org.mockito.Mockito.when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(empty);

        dao.findAllAsync(null, DEVICE, rawQuery(100)).get(5, TimeUnit.SECONDS);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pool).executeQueryStatement(sql.capture());
        assertTrue(sql.getValue().contains("limit 100"), "positive limit must be pushed to the SQL: " + sql.getValue());
    }

    /**
     * 读队列饱和(AbortPolicy 拒绝)时必须 fast-fail: submitRead 把 RejectedExecutionException
     * 转成立即失败的 future, 绝不在调用线程内联执行(CallerRuns)或阻塞。回归"读队列满阻塞
     * 规则引擎/状态/订阅线程"的隐患。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void readQueueSaturationFailsFastWithoutBlocking() throws Exception {
        com.google.common.util.concurrent.ListeningExecutorService rejecting =
                mock(com.google.common.util.concurrent.ListeningExecutorService.class);
        org.mockito.Mockito.when(rejecting.submit(org.mockito.ArgumentMatchers.any(java.util.concurrent.Callable.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("queue full"));
        set("readExecutor", rejecting);

        long t0 = System.currentTimeMillis();
        ReadTsKvQuery q = new BaseReadTsKvQuery("temp", 0L, 1000L, 0L, 100, Aggregation.NONE, "ASC");
        java.util.concurrent.ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> dao.findAllAsync(null, DEVICE, q).get(5, TimeUnit.SECONDS));
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(ex.getCause().getMessage().contains("读队列已满"), ex.getCause().getMessage());
        assertTrue(elapsed < 2000, "must fail fast, not block the caller; was " + elapsed + "ms");
        // 被拒绝的任务从未运行 → 存储层没有被触碰
        verify(pool, never()).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 聚合查询的 lastEntryTs 契约(见 {@link ReadTsKvQueryResult} 字段注释)要求返回"匹配区间内
     * 原始记录的最大时间戳", 而非聚合窗口的 ts。因此 SQL 必须带上 max_time(key), 且结果的
     * lastEntryTs 取自 max_time 而不是窗口中点。
     */
    @Test
    public void aggregatedQueryUsesMaxTimeForLastEntryTs() throws Exception {
        // 单窗口: 窗口起点 ts=0, avg=10.0, 该窗口原始记录最大 ts=950
        org.apache.tsfile.read.common.Field avg =
                new org.apache.tsfile.read.common.Field(org.apache.tsfile.enums.TSDataType.DOUBLE);
        avg.setDoubleV(10.0);
        org.apache.tsfile.read.common.Field maxTime =
                new org.apache.tsfile.read.common.Field(org.apache.tsfile.enums.TSDataType.INT64);
        maxTime.setLongV(950L);
        org.apache.tsfile.read.common.RowRecord row =
                new org.apache.tsfile.read.common.RowRecord(0L, java.util.List.of(avg, maxTime));

        org.apache.iotdb.isession.pool.SessionDataSetWrapper wrapper =
                mock(org.apache.iotdb.isession.pool.SessionDataSetWrapper.class);
        org.mockito.Mockito.when(wrapper.hasNext()).thenReturn(true, false);
        org.mockito.Mockito.when(wrapper.next()).thenReturn(row);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(wrapper);

        ReadTsKvQuery agg = new BaseReadTsKvQuery("temp", 0L, 1000L, 100L, 1000, Aggregation.AVG, "ASC");
        ReadTsKvQueryResult r = dao.findAllAsync(null, DEVICE, agg).get(5, TimeUnit.SECONDS);

        verify(pool).executeQueryStatement(sql.capture());
        assertTrue(sql.getValue().contains("max_time("), "aggregation SQL must select max_time: " + sql.getValue());
        // 数据点 ts 仍是窗口中点(0 + 100/2 = 50), 与 SQL 后端一致
        assertEquals(1, r.getData().size());
        assertEquals(50L, r.getData().get(0).getTs());
        // 但 lastEntryTs 必须是原始记录最大 ts(950), 而非窗口中点(50)
        assertEquals(950L, r.getLastEntryTs());
    }

    @Test
    public void calendarMonthAggregationUsesRealTimezoneBoundaries() throws Exception {
        org.apache.iotdb.isession.pool.SessionDataSetWrapper january =
                mock(org.apache.iotdb.isession.pool.SessionDataSetWrapper.class);
        org.apache.iotdb.isession.pool.SessionDataSetWrapper february =
                mock(org.apache.iotdb.isession.pool.SessionDataSetWrapper.class);
        when(january.hasNext()).thenReturn(false);
        when(february.hasNext()).thenReturn(false);
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(january, february);

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long start = ZonedDateTime.of(2024, 1, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli();
        long februaryStart = ZonedDateTime.of(2024, 2, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli();
        long marchStart = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli();
        ReadTsKvQuery query = new BaseReadTsKvQuery("temp", start, marchStart,
                AggregationParams.calendar(Aggregation.AVG, IntervalType.MONTH, zone), 1000, "ASC");

        dao.findAllAsync(null, DEVICE, query).get(5, TimeUnit.SECONDS);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pool, org.mockito.Mockito.times(2)).executeQueryStatement(sql.capture());
        assertTrue(sql.getAllValues().get(0).contains(
                "where time >= " + start + " and time < " + februaryStart), sql.getAllValues().get(0));
        assertTrue(sql.getAllValues().get(1).contains(
                "where time >= " + februaryStart + " and time < " + marchStart), sql.getAllValues().get(1));
        assertTrue(sql.getAllValues().stream().noneMatch(value -> value.contains("group by")),
                "calendar windows must not be approximated as fixed millisecond GROUP BY windows");
    }

    @Test
    public void batchIsolatesIllegalKeyAndWritesRemainingPoints() throws Exception {
        IotdbWriteService writeService = mock(IotdbWriteService.class);
        set("writeService", writeService);
        List<TsKvEntry> entries = List.of(
                new BasicTsKvEntry(1L, new DoubleDataEntry("time", 1.0)),
                new BasicTsKvEntry(1L, new DoubleDataEntry("temperature", 25.5)));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<IotdbTimeseriesWriteBuffer.WritePoint>> pointsCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        when(writeService.writeBatch(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(Futures.immediateFuture(1));

        int saved = dao.saveBatch(null, DEVICE, entries, 0L).get(5, TimeUnit.SECONDS);

        assertEquals(1, saved, "only the valid point contributes to the persisted data-point count");
        verify(writeService).writeBatch(
                org.mockito.ArgumentMatchers.anyString(), pointsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(1));
        assertEquals(1, pointsCaptor.getValue().size());
        assertEquals("`temperature`", pointsCaptor.getValue().get(0).measurement());
    }

    @Test
    public void batchWithOnlyIllegalKeysCompletesWithoutCreatingKafkaPoison() throws Exception {
        IotdbWriteService writeService = mock(IotdbWriteService.class);
        set("writeService", writeService);
        List<TsKvEntry> entries = List.of(
                new BasicTsKvEntry(1L, new DoubleDataEntry("time", 1.0)),
                new BasicTsKvEntry(1L, new DoubleDataEntry("timestamp", 2.0)));

        int saved = dao.saveBatch(null, DEVICE, entries, 0L).get(5, TimeUnit.SECONDS);

        assertEquals(0, saved);
        verify(writeService, never()).writeBatch(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
