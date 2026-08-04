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

import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.utils.Binary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.TsKvLatestRemovingResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boundary regression for {@code removeLatest}: the latest-in-range check must use the same
 * half-open interval {@code [startTs, endTs)} as the delete statement it guards (and as the
 * SQL reference implementation). A mismatch leaves a dangling latest when the latest sits
 * exactly on {@code startTs}, or rewrites latest without deleting anything at {@code endTs}.
 */
public class IotdbTimeseriesLatestDaoTest {

    private static final DeviceId DEVICE = new DeviceId(new UUID(0x3000L, 1));
    private static final String KEY = "temp";

    private SessionPool pool;
    private IotdbTimeseriesLatestDao dao;

    @BeforeEach
    public void setUp() throws Exception {
        pool = mock(SessionPool.class);
        IotdbSessionPoolConfig config = mock(IotdbSessionPoolConfig.class);
        // 读路径现使用独立读连接池(与写入物理隔离)
        when(config.getReadSessionPool()).thenReturn(pool);
        when(config.getDatabase()).thenReturn("root.tb");

        dao = new IotdbTimeseriesLatestDao();
        java.lang.reflect.Field f = IotdbTimeseriesLatestDao.class.getDeclaredField("iotdb");
        f.setAccessible(true);
        f.set(dao, config);
        java.lang.reflect.Field qf = IotdbTimeseriesLatestDao.class.getDeclaredField("queryMetrics");
        qf.setAccessible(true);
        qf.set(dao, TestMetrics.create());
        dao.init();
    }

    @AfterEach
    public void tearDown() {
        dao.stop();
    }

    private void mockLastAt(long latestTs) throws Exception {
        // SELECT LAST row: Timeseries | Value | DataType (row timestamp = latest ts)
        RowRecord row = new RowRecord(latestTs);
        row.addField(text("root.tb.DEVICE.u_x.`" + KEY + "`"));
        row.addField(text("42.0"));
        row.addField(text("DOUBLE"));
        SessionDataSetWrapper last = mock(SessionDataSetWrapper.class);
        when(last.hasNext()).thenReturn(true, false);
        when(last.next()).thenReturn(row);
        when(pool.executeQueryStatement(startsWith("select last"))).thenReturn(last);
        // rewrite path (previous value lookup) — empty result is fine for these cases
        SessionDataSetWrapper empty = mock(SessionDataSetWrapper.class);
        when(empty.hasNext()).thenReturn(false);
        when(pool.executeQueryStatement(contains("order by time desc limit 1"))).thenReturn(empty);
    }

    private static Field text(String v) {
        Field f = new Field(TSDataType.TEXT);
        f.setBinaryV(new Binary(v, StandardCharsets.UTF_8));
        return f;
    }

    private TsKvLatestRemovingResult removeRange(long startTs, long endTs) throws Exception {
        return dao.removeLatest(null, DEVICE, new BaseDeleteTsKvQuery(KEY, startTs, endTs, true))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    public void latestExactlyOnStartTsMustBeRemoved() throws Exception {
        mockLastAt(1000L);
        TsKvLatestRemovingResult result = removeRange(1000L, 2000L);
        // delete range is [1000, 2000): ts=1000 is deleted, so latest must be marked removed
        verify(pool).executeNonQueryStatement(contains("time >= 1000 and time < 2000"));
        assertTrue(result.isRemoved() || result.getData() != null,
                "latest at startTs is inside the delete range and must be treated as removed");
    }

    @Test
    public void latestExactlyOnEndTsMustNotBeRemoved() throws Exception {
        mockLastAt(2000L);
        TsKvLatestRemovingResult result = removeRange(1000L, 2000L);
        // delete range is [1000, 2000): ts=2000 survives, nothing may be deleted or rewritten
        verify(pool, never()).executeNonQueryStatement(org.mockito.ArgumentMatchers.anyString());
        assertFalse(result.isRemoved());
    }

    @Test
    public void latestInsideRangeIsRemoved() throws Exception {
        mockLastAt(1500L);
        TsKvLatestRemovingResult result = removeRange(1000L, 2000L);
        verify(pool).executeNonQueryStatement(contains("time >= 1000 and time < 2000"));
        assertTrue(result.isRemoved() || result.getData() != null);
    }

    @Test
    public void latestOnlyIllegalKeyIsDroppedWithoutFailedFuture() throws Exception {
        Long version = dao.saveLatest(null, DEVICE,
                        new BasicTsKvEntry(1L, new DoubleDataEntry("time", 42.0)))
                .get(5, TimeUnit.SECONDS);

        assertNull(version);
    }

    // ---- 查询故障不再被伪装成"没有数据" ----

    @Test
    public void findLatestRealFaultMustFailFuture() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.startsWith("select last")))
                .thenThrow(new RuntimeException("Fail to reconnect to server / query timeout"));
        java.util.concurrent.ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> dao.findLatest(null, DEVICE, KEY).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("查询失败"),
                "connection/timeout fault must surface, not be turned into empty telemetry");
    }

    @Test
    public void findLatestPathAbsentReturnsEmpty() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.startsWith("select last")))
                .thenThrow(new StatementExecutionException("Path [root.tb.DEVICE.u_x.temp] does not exist"));
        // path-absent (new device) is normal empty: findLatest returns a null-value entry, no throw
        org.thingsboard.server.common.data.kv.TsKvEntry e =
                dao.findLatest(null, DEVICE, KEY).get(5, TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(e);
        org.junit.jupiter.api.Assertions.assertNull(e.getValue());
    }

    @Test
    public void findAllLatestRealFaultMustFailFuture() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.startsWith("select last *")))
                .thenThrow(new RuntimeException("801: Authentication failed."));
        java.util.concurrent.ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> dao.findAllLatest(null, DEVICE).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("查询失败"));
    }

    @Test
    public void databaseAbsentMustNotBeTreatedAsEmpty() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.startsWith("select last")))
                .thenThrow(new StatementExecutionException("Database root.tb does not exist"));

        java.util.concurrent.ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> dao.findLatest(null, DEVICE, KEY).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().contains("查询失败"));
    }

    @Test
    public void plainRuntimePathMessageMustNotBeTreatedAsEmpty() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.startsWith("select last")))
                .thenThrow(new RuntimeException("Path [root.tb.DEVICE.u_x.temp] does not exist"));

        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                () -> dao.findLatest(null, DEVICE, KEY).get(5, TimeUnit.SECONDS));
    }

    @Test
    public void batchLatestReadsAllRequestedKeysInOneRpc() throws Exception {
        RowRecord temp = new RowRecord(1000L);
        temp.addField(text("root.tb.DEVICE.u_x.`temp`"));
        temp.addField(text("42.0"));
        temp.addField(text("DOUBLE"));
        RowRecord status = new RowRecord(1001L);
        status.addField(text("root.tb.DEVICE.u_x.`status`"));
        status.addField(text("ok"));
        status.addField(text("TEXT"));
        SessionDataSetWrapper rows = mock(SessionDataSetWrapper.class);
        when(rows.hasNext()).thenReturn(true, true, false);
        when(rows.next()).thenReturn(temp, status);
        when(pool.executeQueryStatement(startsWith("select last `temp`,`status`,`missing`")))
                .thenReturn(rows);

        List<org.thingsboard.server.common.data.kv.TsKvEntry> result =
                dao.findLatest(null, DEVICE, List.of("temp", "status", "missing"))
                        .get(5, TimeUnit.SECONDS);

        org.junit.jupiter.api.Assertions.assertEquals(3, result.size());
        org.junit.jupiter.api.Assertions.assertEquals("temp", result.get(0).getKey());
        org.junit.jupiter.api.Assertions.assertEquals("status", result.get(1).getKey());
        org.junit.jupiter.api.Assertions.assertEquals("missing", result.get(2).getKey());
        assertNull(result.get(2).getValue());
        verify(pool, times(1)).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void batchLatestPreservesDuplicateKeysWithoutDuplicateRpc() throws Exception {
        RowRecord temp = new RowRecord(1000L);
        temp.addField(text("root.tb.DEVICE.u_x.`temp`"));
        temp.addField(text("42.0"));
        temp.addField(text("DOUBLE"));
        SessionDataSetWrapper rows = mock(SessionDataSetWrapper.class);
        when(rows.hasNext()).thenReturn(true, false);
        when(rows.next()).thenReturn(temp);
        when(pool.executeQueryStatement(startsWith("select last `temp`"))).thenReturn(rows);

        List<org.thingsboard.server.common.data.kv.TsKvEntry> result =
                dao.findLatest(null, DEVICE, List.of("temp", "temp")).get(5, TimeUnit.SECONDS);

        org.junit.jupiter.api.Assertions.assertEquals(2, result.size());
        org.junit.jupiter.api.Assertions.assertEquals(42.0, result.get(0).getDoubleValue().orElseThrow());
        org.junit.jupiter.api.Assertions.assertEquals(42.0, result.get(1).getDoubleValue().orElseThrow());
        verify(pool, times(1)).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void batchLatestReadsThreeHundredFiftyKeysInOneRpc() throws Exception {
        List<String> keys = IntStream.range(0, 350)
                .mapToObj(i -> "key_" + i)
                .collect(Collectors.toList());
        SessionDataSetWrapper empty = mock(SessionDataSetWrapper.class);
        when(empty.hasNext()).thenReturn(false);
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(empty);

        List<org.thingsboard.server.common.data.kv.TsKvEntry> result =
                dao.findLatest(null, DEVICE, keys).get(5, TimeUnit.SECONDS);

        org.junit.jupiter.api.Assertions.assertEquals(350, result.size());
        assertTrue(result.stream().allMatch(entry -> entry.getValue() == null));
        verify(pool, times(1)).executeQueryStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void batchLatestRealFaultMustFailFuture() throws Exception {
        when(pool.executeQueryStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("801: Authentication failed."));

        java.util.concurrent.ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> dao.findLatest(null, DEVICE, List.of("temp", "status")).get(5, TimeUnit.SECONDS));

        assertTrue(ex.getCause().getMessage().contains("查询失败"));
    }
}
