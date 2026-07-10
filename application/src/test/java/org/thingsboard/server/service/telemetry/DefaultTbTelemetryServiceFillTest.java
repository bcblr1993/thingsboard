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
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.exception.InvalidParametersException;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultTbTelemetryServiceFillTest {

    private static final long START_TS = 1_700_000_000_000L;
    private static final long END_TS = START_TS + 2_000L;

    @Mock
    private TimeseriesService tsService;
    @Mock
    private AccessValidator accessValidator;

    private DefaultTbTelemetryService service;
    private TenantId tenantId;
    private DeviceId deviceId;
    private SecurityUser user;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        service = new DefaultTbTelemetryService(tsService, accessValidator);
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        deviceId = new DeviceId(UUID.randomUUID());
        user = new SecurityUser();
        user.setTenantId(tenantId);
        doAnswer(invocation -> {
            FutureCallback<ValidationResult<Object>> callback = invocation.getArgument(3);
            callback.onSuccess(ValidationResult.ok(null));
            return null;
        }).when(accessValidator).validate(eq(user), any(), eq(deviceId), any());
    }

    @Test
    public void testKeysAreQueriedAndProcessedSerially() throws Exception {
        List<String> queriedKeys = new ArrayList<>();
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenAnswer(invocation -> {
            List<ReadTsKvQuery> queries = invocation.getArgument(2);
            String key = queries.get(0).getKey();
            queriedKeys.add(key);
            long value = "first".equals(key) ? 1L : 2L;
            return Futures.immediateFuture(List.of(
                    new BasicTsKvEntry(START_TS, new LongDataEntry(key, value))));
        });

        List<TsKvEntry> result = service.getTimeseriesFill(deviceId, List.of("first", "second"),
                START_TS, END_TS, 1_000L, Aggregation.NONE, false, "ASC", user).get();

        Assert.assertEquals(List.of("first", "second"), queriedKeys);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("first", result.get(0).getKey());
        Assert.assertEquals("second", result.get(1).getKey());
    }

    @Test
    public void testRawPointLimitStopsBeforeNextKey() throws Exception {
        TsKvEntry entry = new BasicTsKvEntry(START_TS, new LongDataEntry("first", 1L));
        when(tsService.findAll(eq(tenantId), eq(deviceId), any()))
                .thenReturn(Futures.immediateFuture(Collections.nCopies(1_000_001, entry)));

        ExecutionException error = Assert.assertThrows(ExecutionException.class, () ->
                service.getTimeseriesFill(deviceId, List.of("first", "second"), START_TS, END_TS,
                        1_000L, Aggregation.NONE, false, "ASC", user).get());

        Assert.assertTrue(error.getCause() instanceof InvalidParametersException);
        Assert.assertEquals("请求数据量过大", error.getCause().getMessage());
    }

    @Test
    public void testFilledQueryLoadsRawDataBeforeSeed() throws Exception {
        List<String> queryRanges = new ArrayList<>();
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenAnswer(invocation -> {
            List<ReadTsKvQuery> queries = invocation.getArgument(2);
            ReadTsKvQuery query = queries.get(0);
            queryRanges.add(query.getStartTs() + "-" + query.getEndTs());
            if (query.getStartTs() == 0L) {
                return Futures.immediateFuture(List.of(
                        new BasicTsKvEntry(START_TS - 1, new LongDataEntry("temperature", 10L))));
            }
            return Futures.immediateFuture(Collections.emptyList());
        });

        List<TsKvEntry> result = service.getTimeseriesFill(deviceId, List.of("temperature"), START_TS, END_TS,
                1_000L, Aggregation.NONE, true, "ASC", user).get();

        Assert.assertEquals(List.of(START_TS + "-" + END_TS, "0-" + START_TS), queryRanges);
        Assert.assertEquals(2, result.size());
    }

    @Test
    public void testNumericAggregationSkipsNonNumericKey() throws Exception {
        when(tsService.findAll(eq(tenantId), eq(deviceId), any())).thenReturn(Futures.immediateFuture(List.of(
                new BasicTsKvEntry(START_TS, new StringDataEntry("temperature", "warm")))));

        List<TsKvEntry> result = service.getTimeseriesFill(deviceId, List.of("temperature"), START_TS, END_TS,
                1_000L, Aggregation.AVG, false, "ASC", user).get();

        Assert.assertTrue(result.isEmpty());
    }
}
