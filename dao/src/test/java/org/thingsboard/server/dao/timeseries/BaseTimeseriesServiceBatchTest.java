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
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BaseTimeseriesServiceBatchTest {

    @Test
    void saveWithoutLatestUsesOneBackendBatchInsteadOfPerEntryFutures() throws Exception {
        TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        List<TsKvEntry> entries = List.of(
                new BasicTsKvEntry(1L, new DoubleDataEntry("temperature", 25.5)),
                new BasicTsKvEntry(1L, new LongDataEntry("pressure", 1000L)));
        TimeseriesDao dao = mock(TimeseriesDao.class,
                withSettings().extraInterfaces(BatchedTimeseriesDao.class));
        BatchedTimeseriesDao batchDao = (BatchedTimeseriesDao) dao;
        when(batchDao.saveBatch(tenantId, deviceId, entries, 0L))
                .thenReturn(Futures.immediateFuture(2));
        BaseTimeseriesService service = new BaseTimeseriesService();
        ReflectionTestUtils.setField(service, "timeseriesDao", dao);

        assertThat(service.saveWithoutLatest(tenantId, deviceId, entries, 0L).get().getDataPoints())
                .isEqualTo(2);
        verify(batchDao).saveBatch(tenantId, deviceId, entries, 0L);
        verify(dao, never()).savePartition(any(), any(), any(long.class), any(String.class));
        verify(dao, never()).save(any(), any(), any(), any(long.class));
    }

    @Test
    void findLatestUsesBackendBatchCapability() throws Exception {
        TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        List<String> keys = List.of("temperature", "pressure");
        List<TsKvEntry> entries = List.of(
                new BasicTsKvEntry(1L, new DoubleDataEntry("temperature", 25.5)),
                new BasicTsKvEntry(1L, new LongDataEntry("pressure", 1000L)));
        TimeseriesLatestDao latestDao = mock(TimeseriesLatestDao.class,
                withSettings().extraInterfaces(BatchedTimeseriesLatestDao.class));
        BatchedTimeseriesLatestDao batchLatestDao = (BatchedTimeseriesLatestDao) latestDao;
        when(batchLatestDao.findLatest(tenantId, deviceId, keys))
                .thenReturn(Futures.immediateFuture(entries));
        BaseTimeseriesService service = new BaseTimeseriesService();
        ReflectionTestUtils.setField(service, "timeseriesLatestDao", latestDao);

        assertThat(service.findLatest(tenantId, deviceId, keys).get()).isEqualTo(entries);
        verify(batchLatestDao).findLatest(tenantId, deviceId, keys);
        verify(latestDao, never()).findLatest(any(), any(), any(String.class));
    }

}
