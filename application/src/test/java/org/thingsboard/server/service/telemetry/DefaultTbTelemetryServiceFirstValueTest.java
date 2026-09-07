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
package org.thingsboard.server.service.telemetry;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.exception.AccessDeniedException;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultTbTelemetryServiceFirstValueTest {

    private static final long START_TS = 1_700_000_000_000L;
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);

    @Mock
    private TimeseriesService tsService;
    @Mock
    private AccessValidator accessValidator;
    @Mock
    private DbCallbackExecutorService dbCallbackExecutorService;

    private DefaultTbTelemetryService service;
    private TenantId tenantId;
    private DeviceId deviceId;
    private SecurityUser user;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        service = new DefaultTbTelemetryService(tsService, accessValidator, dbCallbackExecutorService);
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        deviceId = new DeviceId(UUID.randomUUID());
        user = new SecurityUser();
        user.setTenantId(tenantId);
        doAnswer(invocation -> {
            FutureCallback<ValidationResult<Object>> callback = invocation.getArgument(3);
            callback.onSuccess(ValidationResult.ok(null));
            return null;
        }).when(accessValidator).validate(eq(user), any(), eq(deviceId), any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(dbCallbackExecutorService).execute(any(Runnable.class));
    }

    @Test
    public void testRequestValidationLimits() {
        assertBadRequest("keys can't be empty", Collections.emptyList(),
                START_TS, START_TS + MINUTE, MINUTE);
        assertBadRequest("Time range can't be more than 31 days", List.of("temperature"),
                START_TS, START_TS + TimeUnit.DAYS.toMillis(31) + 1, TimeUnit.DAYS.toMillis(1));
        assertBadRequest("keys can't be more than 200",
                IntStream.range(0, 201).mapToObj(i -> "key" + i).toList(),
                START_TS, START_TS + MINUTE, MINUTE);
        assertBadRequest("keys can't be more than 200",
                Collections.nCopies(201, "temperature"),
                START_TS, START_TS + MINUTE, MINUTE);
        assertBadRequest("keys can't contain empty values", List.of("temperature", ""),
                START_TS, START_TS + MINUTE, MINUTE);
        assertBadRequest("Requested time buckets can't be more than 500000",
                IntStream.range(0, 200).mapToObj(i -> "key" + i).toList(),
                START_TS, START_TS + 3001 * MINUTE, MINUTE);
        assertBadRequest("interval can't be less than 60000", List.of("temperature"),
                START_TS, START_TS + MINUTE, MINUTE - 1);
        assertBadRequest("endTs must be greater than startTs", List.of("temperature"),
                START_TS, START_TS, MINUTE);
        verifyNoInteractions(tsService);
    }

    @Test
    public void testExactThirtyOneDayRangeIsAccepted() throws Exception {
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenReturn(Futures.immediateFuture(Collections.emptyList()));

        Map<String, List<FormattedTsData>> result = service.getTimeseriesFirstValue(deviceId, List.of("temperature"),
                START_TS, START_TS + TimeUnit.DAYS.toMillis(31), TimeUnit.DAYS.toMillis(1), true, user).get();

        Assert.assertTrue(result.isEmpty());
        verify(tsService).findAll(eq(tenantId), eq(deviceId), any());
    }

    @Test
    public void testExactlyTwoHundredKeysAreAccepted() throws Exception {
        List<String> keys = IntStream.range(0, 200).mapToObj(i -> "key" + i).toList();
        when(tsService.findAll(eq(tenantId), eq(deviceId), any()))
                .thenReturn(Futures.immediateFuture(Collections.emptyList()));

        Map<String, List<FormattedTsData>> result = service.getTimeseriesFirstValue(deviceId, keys,
                START_TS, START_TS + MINUTE, MINUTE, true, user).get();

        Assert.assertTrue(result.isEmpty());
        verify(tsService).findAll(eq(tenantId), eq(deviceId), any());
    }

    @Test
    public void testQueriesAreDeduplicatedAndSubmittedInBoundedBatches() throws Exception {
        List<Integer> batchSizes = new ArrayList<>();
        List<ReadTsKvQuery> allQueries = new ArrayList<>();
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenAnswer(invocation -> {
            List<ReadTsKvQuery> queries = invocation.getArgument(2);
            batchSizes.add(queries.size());
            allQueries.addAll(queries);
            List<TsKvEntry> entries = queries.stream()
                    .map(query -> (TsKvEntry) new BasicTsKvEntry(query.getStartTs(), new LongDataEntry(query.getKey(), query.getStartTs())))
                    .toList();
            return Futures.immediateFuture(entries);
        });

        Map<String, List<FormattedTsData>> result = service.getTimeseriesFirstValue(deviceId,
                List.of("temperature", "temperature"), START_TS, START_TS + 257 * MINUTE, MINUTE, true, user).get();

        Assert.assertEquals(List.of(256, 1), batchSizes);
        Assert.assertEquals(257, allQueries.size());
        Assert.assertEquals(257, result.get("temperature").size());
        ReadTsKvQuery first = allQueries.get(0);
        ReadTsKvQuery last = allQueries.get(256);
        Assert.assertEquals("temperature", first.getKey());
        Assert.assertEquals(START_TS, first.getStartTs());
        Assert.assertEquals(START_TS + MINUTE, first.getEndTs());
        Assert.assertEquals(1, first.getLimit());
        Assert.assertEquals("ASC", first.getOrder());
        Assert.assertEquals(Aggregation.NONE, first.getAggregation());
        Assert.assertEquals(START_TS + 256 * MINUTE, last.getStartTs());
        Assert.assertEquals(START_TS + 257 * MINUTE, last.getEndTs());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAccessDeniedDoesNotQueryTimeseries() {
        doAnswer(invocation -> {
            FutureCallback<ValidationResult<Object>> callback = invocation.getArgument(3);
            callback.onSuccess(ValidationResult.accessDenied("Permission denied"));
            return null;
        }).when(accessValidator).validate(eq(user), any(), eq(deviceId), any());

        ExecutionException error = Assert.assertThrows(ExecutionException.class, () ->
                service.getTimeseriesFirstValue(deviceId, List.of("temperature"),
                        START_TS, START_TS + MINUTE, MINUTE, true, user).get());

        Assert.assertTrue(error.getCause() instanceof AccessDeniedException);
        Assert.assertEquals("Permission denied", error.getCause().getMessage());
        verifyNoInteractions(tsService);
    }

    @Test
    public void testBatchFailureStopsFollowingBatches() {
        AtomicInteger invocationCount = new AtomicInteger();
        RuntimeException databaseError = new RuntimeException("database failure");
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenAnswer(invocation -> {
            if (invocationCount.incrementAndGet() == 1) {
                return Futures.immediateFuture(Collections.emptyList());
            }
            return Futures.immediateFailedFuture(databaseError);
        });

        ExecutionException error = Assert.assertThrows(ExecutionException.class, () ->
                service.getTimeseriesFirstValue(deviceId, List.of("temperature"),
                        START_TS, START_TS + 600 * MINUTE, MINUTE, true, user).get());

        Assert.assertSame(databaseError, error.getCause());
        Assert.assertEquals(2, invocationCount.get());
    }

    @Test
    public void testDuplicateBucketResultsKeepEarliestValue() throws Exception {
        long bucketStart = START_TS;
        TsKvEntry later = new BasicTsKvEntry(bucketStart + 20, new LongDataEntry("temperature", 20L));
        TsKvEntry earlier = new BasicTsKvEntry(bucketStart + 10, new LongDataEntry("temperature", 10L));
        when(tsService.findAll(eq(tenantId), eq(deviceId), any()))
                .thenReturn(Futures.immediateFuture(List.of(later, earlier)));

        Map<String, List<FormattedTsData>> result = service.getTimeseriesFirstValue(deviceId,
                List.of("temperature"), START_TS, START_TS + MINUTE, MINUTE, true, user).get();

        Assert.assertEquals(1, result.get("temperature").size());
        Assert.assertEquals(bucketStart + 10, result.get("temperature").get(0).getOriginalTs());
        Assert.assertEquals(10L, result.get("temperature").get(0).getValue());
    }

    private void assertBadRequest(String expectedMessage, List<String> keys, long startTs, long endTs, long interval) {
        ThingsboardException error = Assert.assertThrows(ThingsboardException.class, () ->
                service.getTimeseriesFirstValue(deviceId, keys, startTs, endTs, interval, true, user));
        Assert.assertEquals(expectedMessage, error.getMessage());
    }

}
