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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.edqs.LatestTsKv;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.msg.edqs.EdqsService;
import org.thingsboard.server.dao.entityview.EntityViewService;
import org.thingsboard.server.dao.sqlts.SqlTimeseriesLatestDao;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseTimeseriesServiceEdqsTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE_ID = new DeviceId(UUID.randomUUID());

    @Mock
    private TimeseriesDao timeseriesDao;

    @Mock
    private EntityViewService entityViewService;

    @Mock
    private EdqsService edqsService;

    private BaseTimeseriesService timeseriesService;

    private TsKvEntry tsEntry1;
    private TsKvEntry tsEntry2;

    @BeforeEach
    void setUp() {
        timeseriesService = new BaseTimeseriesService();
        ReflectionTestUtils.setField(timeseriesService, "timeseriesDao", timeseriesDao);
        ReflectionTestUtils.setField(timeseriesService, "entityViewService", entityViewService);
        ReflectionTestUtils.setField(timeseriesService, "edqsService", edqsService);

        tsEntry1 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.5));
        tsEntry2 = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("status", "active"));
    }

    @Test
    void save_withRedisTimeseriesLatestDao_shouldNotifyEdqsForEachEntry() {
        RedisTimeseriesLatestDao redisLatestDao = mock(RedisTimeseriesLatestDao.class);
        ReflectionTestUtils.setField(timeseriesService, "timeseriesLatestDao", redisLatestDao);

        when(timeseriesDao.savePartition(any(), any(), any(long.class), any(String.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(timeseriesDao.save(any(), any(), any(), any(long.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(redisLatestDao.saveLatest(eq(TENANT_ID), eq(DEVICE_ID), any(List.class)))
                .thenReturn(Futures.immediateFuture(42L));

        timeseriesService.save(TENANT_ID, DEVICE_ID, List.of(tsEntry1, tsEntry2), 0L);

        ArgumentCaptor<LatestTsKv> captor = ArgumentCaptor.forClass(LatestTsKv.class);
        verify(edqsService, times(2)).onUpdate(
                eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), captor.capture());

        assertThat(captor.getAllValues())
                .extracting(LatestTsKv::getKey)
                .containsExactlyInAnyOrder("temperature", "status");
    }

    @Test
    void save_withRedisClusterTimeseriesLatestDao_shouldNotifyEdqsForEachEntry() {
        RedisClusterTimeseriesLatestDao redisClusterLatestDao = mock(RedisClusterTimeseriesLatestDao.class);
        ReflectionTestUtils.setField(timeseriesService, "timeseriesLatestDao", redisClusterLatestDao);

        when(timeseriesDao.savePartition(any(), any(), any(long.class), any(String.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(timeseriesDao.save(any(), any(), any(), any(long.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(redisClusterLatestDao.saveLatest(eq(TENANT_ID), eq(DEVICE_ID), any(List.class)))
                .thenReturn(Futures.immediateFuture(99L));

        timeseriesService.save(TENANT_ID, DEVICE_ID, List.of(tsEntry1, tsEntry2), 0L);

        verify(edqsService, times(2)).onUpdate(
                eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), any(LatestTsKv.class));
    }

    @Test
    void save_withSqlTimeseriesLatestDao_shouldNotifyEdqsPerEntry() {
        SqlTimeseriesLatestDao sqlLatestDao = mock(SqlTimeseriesLatestDao.class);
        ReflectionTestUtils.setField(timeseriesService, "timeseriesLatestDao", sqlLatestDao);

        when(timeseriesDao.savePartition(any(), any(), any(long.class), any(String.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(timeseriesDao.save(any(), any(), any(), any(long.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(sqlLatestDao.saveLatest(eq(TENANT_ID), eq(DEVICE_ID), any(TsKvEntry.class)))
                .thenReturn(Futures.immediateFuture(1L));

        timeseriesService.save(TENANT_ID, DEVICE_ID, List.of(tsEntry1), 0L);

        ArgumentCaptor<LatestTsKv> captor = ArgumentCaptor.forClass(LatestTsKv.class);
        verify(edqsService).onUpdate(eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void saveLatest_withRedisTimeseriesLatestDao_shouldNotifyEdqs() {
        RedisTimeseriesLatestDao redisLatestDao = mock(RedisTimeseriesLatestDao.class);
        ReflectionTestUtils.setField(timeseriesService, "timeseriesLatestDao", redisLatestDao);

        when(redisLatestDao.saveLatest(eq(TENANT_ID), eq(DEVICE_ID), any(List.class)))
                .thenReturn(Futures.immediateFuture(42L));

        timeseriesService.saveLatest(TENANT_ID, DEVICE_ID, List.of(tsEntry1, tsEntry2));

        verify(edqsService, times(2)).onUpdate(
                eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), any(LatestTsKv.class));
    }

    @Test
    void save_withCassandraTimeseriesLatestDao_shouldNotUseRedisPath() {
        CassandraBaseTimeseriesLatestDao cassandraLatestDao = mock(CassandraBaseTimeseriesLatestDao.class);
        ReflectionTestUtils.setField(timeseriesService, "timeseriesLatestDao", cassandraLatestDao);

        when(timeseriesDao.savePartition(any(), any(), any(long.class), any(String.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(timeseriesDao.save(any(), any(), any(), any(long.class)))
                .thenReturn(Futures.immediateFuture(1));
        when(cassandraLatestDao.saveLatest(eq(TENANT_ID), eq(DEVICE_ID), any(TsKvEntry.class)))
                .thenReturn(Futures.immediateFuture(1L));

        timeseriesService.save(TENANT_ID, DEVICE_ID, List.of(tsEntry1), 0L);

        verify(edqsService).onUpdate(eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), any(LatestTsKv.class));
        verify(cassandraLatestDao, never()).saveLatest(any(), any(), any(List.class));
    }

}
