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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.edqs.LatestTsKv;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.TimeseriesSaveResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.msg.edqs.EdqsService;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.dao.timeseries.fast.RedisFastTimeseriesLatestDao;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class BaseTimeseriesServiceRedisFastTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE_ID = new DeviceId(UUID.randomUUID());
    private static final List<TsKvEntry> ENTRIES = List.of(
            entry(300L, "temperature", 30L), entry(200L, "pressure", 20L));

    @Mock
    private TimeseriesDao timeseriesDao;

    @Mock
    private RedisFastTimeseriesLatestDao latestDao;

    @Mock
    private EdqsService edqsService;

    private BaseTimeseriesService service;

    @BeforeEach
    void setUp() {
        service = new BaseTimeseriesService();
        ReflectionTestUtils.setField(service, "timeseriesDao", timeseriesDao);
        ReflectionTestUtils.setField(service, "timeseriesLatestDao", latestDao);
        ReflectionTestUtils.setField(service, "edqsService", edqsService);
    }

    @Test
    void saveRoutesHistoricalWritesAndOneRedisBatchWithOriginalTtl() throws Exception {
        when(timeseriesDao.savePartition(eq(TENANT_ID), eq(DEVICE_ID), anyLong(), anyString()))
                .thenReturn(Futures.immediateFuture(0));
        when(timeseriesDao.save(eq(TENANT_ID), eq(DEVICE_ID), any(TsKvEntry.class), eq(3600L)))
                .thenReturn(Futures.immediateFuture(1));
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES))
                .thenReturn(Futures.immediateFuture(List.of(300L, 200L)));

        TimeseriesSaveResult result = service.save(TENANT_ID, DEVICE_ID, ENTRIES, 3600L)
                .get(1, TimeUnit.SECONDS);

        assertThat(result.getDataPoints()).isEqualTo(2);
        assertThat(result.getVersions()).containsExactly(300L, 200L);
        for (TsKvEntry entry : ENTRIES) {
            verify(timeseriesDao).savePartition(TENANT_ID, DEVICE_ID, entry.getTs(), entry.getKey());
            verify(timeseriesDao).save(TENANT_ID, DEVICE_ID, entry, 3600L);
        }
        verify(latestDao).saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES);
        verify(latestDao, never()).saveLatest(any(), any(), any(TsKvEntry.class));
    }

    @Test
    void iotdbHistoricalBatchDoesNotSkipRedisLatestWrite() throws Exception {
        TimeseriesDao batchedDao = mock(TimeseriesDao.class,
                withSettings().extraInterfaces(BatchedTimeseriesDao.class));
        ReflectionTestUtils.setField(service, "timeseriesDao", batchedDao);
        ReflectionTestUtils.setField(service, "tsType", "iotdb");
        when(((BatchedTimeseriesDao) batchedDao).saveBatch(TENANT_ID, DEVICE_ID, ENTRIES, 60L))
                .thenReturn(Futures.immediateFuture(2));
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES))
                .thenReturn(Futures.immediateFuture(List.of(300L, 200L)));

        TimeseriesSaveResult result = service.save(TENANT_ID, DEVICE_ID, ENTRIES, 60L)
                .get(1, TimeUnit.SECONDS);

        assertThat(result.getDataPoints()).isEqualTo(2);
        assertThat(result.getVersions()).containsExactly(300L, 200L);
        verify(latestDao).saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES);
        verify(batchedDao, never()).save(any(), any(), any(), anyLong());
        verify(batchedDao, never()).savePartition(any(), any(), anyLong(), anyString());
    }

    @Test
    void latestVersionsAndEdqsKeepInputPositionsForRepeatedAndOutOfOrderKeys() throws Exception {
        List<TsKvEntry> entries = List.of(entry(300L, "temperature", 30L),
                entry(100L, "temperature", 10L), entry(200L, "pressure", 20L),
                entry(300L, "temperature", 31L));
        List<Long> versions = Arrays.asList(300L, null, 200L, 300L);
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, entries))
                .thenReturn(Futures.immediateFuture(versions));

        TimeseriesSaveResult result = service.saveLatest(TENANT_ID, DEVICE_ID, entries)
                .get(1, TimeUnit.SECONDS);

        assertThat(result.getDataPoints()).isZero();
        assertThat(result.getVersions()).containsExactly(300L, null, 200L, 300L);
        ArgumentCaptor<LatestTsKv> captor = ArgumentCaptor.forClass(LatestTsKv.class);
        verify(edqsService, times(3)).onUpdate(eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), captor.capture());
        List<LatestTsKv> notifications = captor.getAllValues();
        assertThat(notifications).extracting(LatestTsKv::getEntityId).containsOnly(DEVICE_ID);
        assertThat(notifications).extracting(LatestTsKv::getKey)
                .containsExactly("temperature", "pressure", "temperature");
        assertThat(notifications).extracting(LatestTsKv::getTs).containsExactly(300L, 200L, 300L);
        assertThat(notifications).extracting(LatestTsKv::getVersion).containsExactly(300L, 200L, 300L);
        assertThat(notifications).extracting(notification -> notification.getValue().getValue())
                .containsExactly(30L, 20L, 31L);
        verifyNoInteractions(timeseriesDao);
        verify(latestDao).saveLatestBatch(TENANT_ID, DEVICE_ID, entries);
        verify(latestDao, never()).saveLatest(any(), any(), any(TsKvEntry.class));
    }

    @Test
    void latestWriteWaitsForRedisAcknowledgementBeforeReturningOrNotifyingEdqs() throws Exception {
        SettableFuture<List<Long>> redisWrite = SettableFuture.create();
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES)).thenReturn(redisWrite);

        ListenableFuture<TimeseriesSaveResult> result = service.saveLatest(TENANT_ID, DEVICE_ID, ENTRIES);

        assertThat(result.isDone()).isFalse();
        verifyNoInteractions(edqsService);
        redisWrite.set(List.of(300L, 200L));
        assertThat(result.get(1, TimeUnit.SECONDS).getVersions()).containsExactly(300L, 200L);
        verify(edqsService, times(2)).onUpdate(eq(TENANT_ID), eq(ObjectType.LATEST_TS_KV), any(LatestTsKv.class));
    }

    @Test
    void redisWriteFailureFailsTheSaveWithoutEdqsNotifications() {
        RuntimeException failure = new IllegalStateException("Redis unavailable");
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES))
                .thenReturn(Futures.immediateFailedFuture(failure));

        ListenableFuture<TimeseriesSaveResult> result = service.saveLatest(TENANT_ID, DEVICE_ID, ENTRIES);

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).hasRootCause(failure);
        verifyNoInteractions(edqsService, timeseriesDao);
    }

    @Test
    void combinedSaveDoesNotHideRedisFailureWhenHistoricalWriteSucceeds() {
        TimeseriesDao batchedDao = mock(TimeseriesDao.class,
                withSettings().extraInterfaces(BatchedTimeseriesDao.class));
        ReflectionTestUtils.setField(service, "timeseriesDao", batchedDao);
        when(((BatchedTimeseriesDao) batchedDao).saveBatch(TENANT_ID, DEVICE_ID, ENTRIES, 0L))
                .thenReturn(Futures.immediateFuture(2));
        RuntimeException failure = new IllegalStateException("Redis script failed");
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES))
                .thenReturn(Futures.immediateFailedFuture(failure));

        ListenableFuture<TimeseriesSaveResult> result = service.save(TENANT_ID, DEVICE_ID, ENTRIES, 0L);

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).hasRootCause(failure);
        verifyNoInteractions(edqsService);
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void invalidBatchResultFailsBeforePublishingAnyEdqsUpdate(List<Long> versions) {
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES))
                .thenReturn(Futures.immediateFuture(versions));

        ListenableFuture<TimeseriesSaveResult> result = service.saveLatest(TENANT_ID, DEVICE_ID, ENTRIES);

        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Latest batch result size does not match telemetry entries");
        verifyNoInteractions(edqsService);
    }

    @Test
    void emptyLatestBatchSucceedsWithoutEdqsUpdates() throws Exception {
        when(latestDao.saveLatestBatch(TENANT_ID, DEVICE_ID, List.of()))
                .thenReturn(Futures.immediateFuture(List.of()));

        TimeseriesSaveResult result = service.saveLatest(TENANT_ID, DEVICE_ID, List.of())
                .get(1, TimeUnit.SECONDS);

        assertThat(result.getDataPoints()).isZero();
        assertThat(result.getVersions()).isEmpty();
        verifyNoInteractions(edqsService, timeseriesDao);
    }

    @Test
    void historicalOnlySaveDoesNotWriteRedisOrNotifyEdqs() throws Exception {
        when(timeseriesDao.savePartition(eq(TENANT_ID), eq(DEVICE_ID), anyLong(), anyString()))
                .thenReturn(Futures.immediateFuture(0));
        when(timeseriesDao.save(eq(TENANT_ID), eq(DEVICE_ID), any(TsKvEntry.class), eq(60L)))
                .thenReturn(Futures.immediateFuture(1));

        TimeseriesSaveResult result = service.saveWithoutLatest(TENANT_ID, DEVICE_ID, ENTRIES, 60L)
                .get(1, TimeUnit.SECONDS);

        assertThat(result.getDataPoints()).isEqualTo(2);
        assertThat(result.getVersions()).isNull();
        verifyNoInteractions(latestDao, edqsService);
    }

    @Test
    void batchReadPreservesRepeatedKeysAndBackendResultOrder() throws Exception {
        Collection<String> keys = List.of("pressure", "temperature", "pressure");
        List<TsKvEntry> entries = List.of(ENTRIES.get(1), ENTRIES.get(0), ENTRIES.get(1));
        when(latestDao.findLatest(TENANT_ID, DEVICE_ID, keys)).thenReturn(Futures.immediateFuture(entries));

        assertThat(service.findLatest(TENANT_ID, DEVICE_ID, keys).get(1, TimeUnit.SECONDS))
                .containsExactlyElementsOf(entries);

        verify(latestDao).findLatest(TENANT_ID, DEVICE_ID, keys);
        verify(latestDao, never()).findLatest(any(), any(), anyString());
    }

    @Test
    void batchReadFailureIsPropagatedWithoutRetryingIndividualKeys() {
        Collection<String> keys = List.of("temperature", "pressure");
        RuntimeException failure = new IllegalStateException("Redis read failed");
        when(latestDao.findLatest(TENANT_ID, DEVICE_ID, keys))
                .thenReturn(Futures.immediateFailedFuture(failure));

        assertThatThrownBy(() -> service.findLatest(TENANT_ID, DEVICE_ID, keys).get(1, TimeUnit.SECONDS))
                .hasRootCause(failure);

        verify(latestDao, never()).findLatest(any(), any(), anyString());
    }

    @Test
    void invalidBatchReadKeyIsRejectedBeforeRedisAccess() {
        assertThatThrownBy(() -> service.findLatest(TENANT_ID, DEVICE_ID, List.of("temperature", "")))
                .isInstanceOf(IncorrectParameterException.class);

        verifyNoInteractions(latestDao);
    }

    @Test
    void defaultBatchVersionAdapterWaitsAndPreservesAllInputPositions() throws Exception {
        SettableFuture<Long> backendWrite = SettableFuture.create();
        BatchedTimeseriesLatestWriteDao dao = (tenantId, entityId, entries) -> backendWrite;
        List<TsKvEntry> entries = List.of(entry(300L, "temperature", 30L),
                entry(100L, "temperature", 10L), entry(300L, "temperature", 31L));

        ListenableFuture<List<Long>> result = dao.saveLatestBatch(TENANT_ID, DEVICE_ID, entries);
        assertThat(result.isDone()).isFalse();
        backendWrite.set(2L);

        assertThat(result.get(1, TimeUnit.SECONDS)).containsExactly(300L, 100L, 300L);
    }

    @Test
    void defaultBatchVersionAdapterPropagatesBackendFailure() {
        RuntimeException failure = new IllegalStateException("Redis write failed");
        BatchedTimeseriesLatestWriteDao dao = (tenantId, entityId, entries) ->
                Futures.immediateFailedFuture(failure);

        assertThatThrownBy(() -> dao.saveLatestBatch(TENANT_ID, DEVICE_ID, ENTRIES).get(1, TimeUnit.SECONDS))
                .hasRootCause(failure);
    }

    private static Stream<Arguments> invalidVersions() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(List.of()),
                Arguments.of(List.of(300L)), Arguments.of(List.of(300L, 200L, 100L)));
    }

    private static TsKvEntry entry(long ts, String key, long value) {
        return new BasicTsKvEntry(ts, new LongDataEntry(key, value));
    }
}
