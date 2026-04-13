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
package org.thingsboard.server.dao.sql.query;

import com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.BooleanFilterPredicate;
import org.thingsboard.server.common.data.query.ComplexFilterPredicate;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.EntityKeyValueType;
import org.thingsboard.server.common.data.query.EntityListFilter;
import org.thingsboard.server.common.data.query.FilterPredicateValue;
import org.thingsboard.server.common.data.query.KeyFilter;
import org.thingsboard.server.common.data.query.KeyFilterPredicate;
import org.thingsboard.server.common.data.query.NumericFilterPredicate;
import org.thingsboard.server.common.data.query.StringFilterPredicate;
import org.thingsboard.server.dao.entity.EntityQueryDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TsLatestAwareEntityQueryDaoTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    @Mock
    private EntityQueryDao delegate;

    @Mock
    private TimeseriesLatestDao timeseriesLatestDao;

    @InjectMocks
    private TsLatestAwareEntityQueryDao tsLatestAwareEntityQueryDao;

    private DeviceId deviceId1;
    private DeviceId deviceId2;
    private DeviceId deviceId3;

    @BeforeEach
    void setUp() {
        deviceId1 = new DeviceId(UUID.randomUUID());
        deviceId2 = new DeviceId(UUID.randomUUID());
        deviceId3 = new DeviceId(UUID.randomUUID());
    }

    // ========== countEntitiesByQuery ==========

    @Test
    void countEntitiesByQuery_noTsKeyFilters_shouldDelegate() {
        KeyFilter attrFilter = buildKeyFilter("status", EntityKeyType.ATTRIBUTE, EntityKeyValueType.STRING,
                buildStringPredicate(StringFilterPredicate.StringOperation.EQUAL, "active"));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(attrFilter));

        when(delegate.countEntitiesByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityCountQuery.class)))
                .thenReturn(5L);

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(5L);
        verify(delegate).countEntitiesByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityCountQuery.class));
        verify(timeseriesLatestDao, never()).findLatest(any(), any(EntityId.class), anyString());
    }

    @Test
    void countEntitiesByQuery_withTsKeyFilter_shouldFilterFromBackend() {
        // Device1: temperature=25.5, Device2: temperature=18.0, Device3: no telemetry
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.5));
        TsKvEntry temp18 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 18.0));
        TsKvEntry tempNull = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("temperature", null));

        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId1), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId2), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp18));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId3), eq("temperature")))
                .thenReturn(Futures.immediateFuture(tempNull));

        // Delegate returns all 3 devices as candidates
        List<EntityData> candidates = List.of(
                buildEntityData(deviceId1),
                buildEntityData(deviceId2),
                buildEntityData(deviceId3)
        );
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 3, false));

        // Filter: temperature > 20
        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(tsFilter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        // Only device1 (temperature=25.5 > 20) should match; device2 (18.0) and device3 (null) should not
        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_noCandidates_shouldReturnZero() {
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(Collections.emptyList(), 0, 0, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 0.0));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(tsFilter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(0L);
        verify(timeseriesLatestDao, never()).findLatest(any(), any(EntityId.class), anyString());
    }

    @Test
    void countEntitiesByQuery_multipleTsFilters_allMustMatch() {
        // Device1: temperature=25.5, humidity=60; Device2: temperature=25.5, humidity=30
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.5));
        TsKvEntry hum60 = new BasicTsKvEntry(System.currentTimeMillis(), new LongDataEntry("humidity", 60L));
        TsKvEntry hum30 = new BasicTsKvEntry(System.currentTimeMillis(), new LongDataEntry("humidity", 30L));

        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId1), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId1), eq("humidity")))
                .thenReturn(Futures.immediateFuture(hum60));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId2), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId2), eq("humidity")))
                .thenReturn(Futures.immediateFuture(hum30));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1), buildEntityData(deviceId2));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 2, false));

        // Filter: temperature > 20 AND humidity >= 50
        KeyFilter tempFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));
        KeyFilter humFilter = buildKeyFilter("humidity", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER_OR_EQUAL, 50.0));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(tempFilter, humFilter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        // Only device1 matches both filters (temp=25.5>20, hum=60>=50); device2 fails humidity
        assertThat(result).isEqualTo(1L);
    }

    // ========== findEntityDataByQuery ==========

    @Test
    void findEntityDataByQuery_noTsKeyFiltersAndNoTsLatestValues_shouldDelegate() {
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                List.of(new EntityKey(EntityKeyType.ATTRIBUTE, "status")),
                Collections.emptyList()
        );

        PageData<EntityData> expected = new PageData<>(Collections.emptyList(), 0, 0, false);
        when(delegate.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query)).thenReturn(expected);

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isSameAs(expected);
        verify(timeseriesLatestDao, never()).findLatest(any(), any(EntityId.class), anyString());
    }

    @Test
    void findEntityDataByQuery_withTsLatestValuesOnly_shouldFillFromBackend() {
        TsKvEntry tempEntry = new BasicTsKvEntry(1000L, new DoubleDataEntry("temperature", 25.5));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature"))
                .thenReturn(Futures.immediateFuture(tempEntry));

        EntityData entityData = buildEntityData(deviceId1);
        PageData<EntityData> delegateResult = new PageData<>(List.of(entityData), 1, 1, false);
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(delegateResult);

        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                List.of(new EntityKey(EntityKeyType.TIME_SERIES, "temperature")),
                Collections.emptyList()
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result.getData()).hasSize(1);
        EntityData data = result.getData().get(0);
        assertThat(data.getLatest()).isNotNull();
        assertThat(data.getLatest().get(EntityKeyType.TIME_SERIES)).isNotNull();
        assertThat(data.getLatest().get(EntityKeyType.TIME_SERIES).get("temperature")).isNotNull();
        assertThat(data.getLatest().get(EntityKeyType.TIME_SERIES).get("temperature").getTs()).isEqualTo(1000L);
    }

    @Test
    void findEntityDataByQuery_withTsFilter_shouldFilterAndPaginate() {
        // 5 devices, only 3 match filter temperature > 20
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp30 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 30.0));
        TsKvEntry temp15 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 15.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));
        TsKvEntry temp10 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 10.0));

        DeviceId d1 = deviceId1, d2 = deviceId2, d3 = deviceId3;
        DeviceId d4 = new DeviceId(UUID.randomUUID());
        DeviceId d5 = new DeviceId(UUID.randomUUID());

        when(timeseriesLatestDao.findLatest(TENANT_ID, d1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d2, "temperature")).thenReturn(Futures.immediateFuture(temp15));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d3, "temperature")).thenReturn(Futures.immediateFuture(temp30));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d4, "temperature")).thenReturn(Futures.immediateFuture(temp10));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d5, "temperature")).thenReturn(Futures.immediateFuture(temp35));

        List<EntityData> candidates = List.of(
                buildEntityData(d1), buildEntityData(d2), buildEntityData(d3),
                buildEntityData(d4), buildEntityData(d5)
        );
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 5, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));

        // Page size 2, page 0 => should return d1 (25.0) and d3 (30.0)
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(2, 0, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                List.of(tsFilter)
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result.getData()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3); // d1, d3, d5 match
        assertThat(result.hasNext()).isTrue(); // 3 total, page size 2
    }

    @Test
    void findEntityDataByQuery_withTsFilter_pageBeyondResults_shouldReturnEmpty() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 1, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));

        // Page 5, well beyond the 1 matching result
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 5, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                List.of(tsFilter)
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
    }

    // ========== Filter evaluation tests ==========

    @Test
    void countEntitiesByQuery_stringFilter_shouldEvaluateCorrectly() {
        TsKvEntry statusActive = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("status", "active"));
        TsKvEntry statusInactive = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("status", "inactive"));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "status")).thenReturn(Futures.immediateFuture(statusActive));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "status")).thenReturn(Futures.immediateFuture(statusInactive));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1), buildEntityData(deviceId2));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 2, false));

        KeyFilter filter = buildKeyFilter("status", EntityKeyType.TIME_SERIES, EntityKeyValueType.STRING,
                buildStringPredicate(StringFilterPredicate.StringOperation.EQUAL, "active"));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_booleanFilter_shouldEvaluateCorrectly() {
        TsKvEntry boolTrue = new BasicTsKvEntry(System.currentTimeMillis(), new BooleanDataEntry("enabled", true));
        TsKvEntry boolFalse = new BasicTsKvEntry(System.currentTimeMillis(), new BooleanDataEntry("enabled", false));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "enabled")).thenReturn(Futures.immediateFuture(boolTrue));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "enabled")).thenReturn(Futures.immediateFuture(boolFalse));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1), buildEntityData(deviceId2));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 2, false));

        KeyFilter filter = buildKeyFilter("enabled", EntityKeyType.TIME_SERIES, EntityKeyValueType.BOOLEAN,
                buildBooleanPredicate(BooleanFilterPredicate.BooleanOperation.EQUAL, true));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_complexFilter_shouldEvaluateCorrectly() {
        // temperature > 20 AND temperature < 30
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(temp35));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1), buildEntityData(deviceId2));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 2, false));

        NumericFilterPredicate greaterThan20 = buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0);
        NumericFilterPredicate lessThan30 = buildNumericPredicate(NumericFilterPredicate.NumericOperation.LESS, 30.0);

        ComplexFilterPredicate complexPredicate = new ComplexFilterPredicate();
        complexPredicate.setOperation(ComplexFilterPredicate.ComplexOperation.AND);
        complexPredicate.setPredicates(List.of(greaterThan20, lessThan30));

        KeyFilter filter = new KeyFilter();
        filter.setKey(new EntityKey(EntityKeyType.TIME_SERIES, "temperature"));
        filter.setValueType(EntityKeyValueType.NUMERIC);
        filter.setPredicate(complexPredicate);

        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        // Only device1 (25.0) matches 20 < temp < 30; device2 (35.0) fails
        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_stringFilter_containsOperation() {
        TsKvEntry desc = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("description", "temperature sensor"));
        TsKvEntry desc2 = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("description", "humidity sensor"));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "description")).thenReturn(Futures.immediateFuture(desc));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "description")).thenReturn(Futures.immediateFuture(desc2));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1), buildEntityData(deviceId2));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 2, false));

        KeyFilter filter = buildKeyFilter("description", EntityKeyType.TIME_SERIES, EntityKeyValueType.STRING,
                buildStringPredicate(StringFilterPredicate.StringOperation.CONTAINS, "temperature"));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_numericFilter_allOperations() {
        // Test EQUAL
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.EQUAL, 25.0, 25.0).isEqualTo(1L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.EQUAL, 25.0, 30.0).isEqualTo(0L);

        // Test NOT_EQUAL
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.NOT_EQUAL, 25.0, 25.0).isEqualTo(0L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.NOT_EQUAL, 25.0, 30.0).isEqualTo(1L);

        // Test GREATER
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.GREATER, 25.0, 20.0).isEqualTo(1L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.GREATER, 25.0, 30.0).isEqualTo(0L);

        // Test LESS
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.LESS, 25.0, 30.0).isEqualTo(1L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.LESS, 25.0, 20.0).isEqualTo(0L);

        // Test GREATER_OR_EQUAL
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.GREATER_OR_EQUAL, 25.0, 25.0).isEqualTo(1L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.GREATER_OR_EQUAL, 25.0, 20.0).isEqualTo(1L);

        // Test LESS_OR_EQUAL
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.LESS_OR_EQUAL, 25.0, 25.0).isEqualTo(1L);
        assertThatCountWithNumericOp(NumericFilterPredicate.NumericOperation.LESS_OR_EQUAL, 25.0, 30.0).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_nullEntry_shouldNotMatch() {
        TsKvEntry nullEntry = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("temperature", null));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(nullEntry));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 1, false));

        KeyFilter filter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(0L);
    }

    @Test
    void countEntitiesByQuery_stringFilter_ignoreCase() {
        TsKvEntry statusActive = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("status", "Active"));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "status")).thenReturn(Futures.immediateFuture(statusActive));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 1, false));

        StringFilterPredicate pred = new StringFilterPredicate();
        pred.setOperation(StringFilterPredicate.StringOperation.EQUAL);
        pred.setValue(FilterPredicateValue.fromString("active"));
        pred.setIgnoreCase(true);

        KeyFilter filter = new KeyFilter();
        filter.setKey(new EntityKey(EntityKeyType.TIME_SERIES, "status"));
        filter.setValueType(EntityKeyValueType.STRING);
        filter.setPredicate(pred);

        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result).isEqualTo(1L);
    }

    @Test
    void countEntitiesByQuery_complexFilter_orOperation() {
        // temperature > 30 OR temperature < 10
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));
        TsKvEntry temp5 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 5.0));

        DeviceId d1 = deviceId1, d2 = deviceId2, d3 = deviceId3;

        when(timeseriesLatestDao.findLatest(TENANT_ID, d1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d2, "temperature")).thenReturn(Futures.immediateFuture(temp35));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d3, "temperature")).thenReturn(Futures.immediateFuture(temp5));

        List<EntityData> candidates = List.of(buildEntityData(d1), buildEntityData(d2), buildEntityData(d3));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 3, false));

        NumericFilterPredicate greaterThan30 = buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 30.0);
        NumericFilterPredicate lessThan10 = buildNumericPredicate(NumericFilterPredicate.NumericOperation.LESS, 10.0);

        ComplexFilterPredicate complexPredicate = new ComplexFilterPredicate();
        complexPredicate.setOperation(ComplexFilterPredicate.ComplexOperation.OR);
        complexPredicate.setPredicates(List.of(greaterThan30, lessThan10));

        KeyFilter filter = new KeyFilter();
        filter.setKey(new EntityKey(EntityKeyType.TIME_SERIES, "temperature"));
        filter.setValueType(EntityKeyValueType.NUMERIC);
        filter.setPredicate(complexPredicate);

        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        // d2 (35.0 > 30) and d3 (5.0 < 10) match; d1 (25.0) does not
        assertThat(result).isEqualTo(2L);
    }

    // ========== Helper methods ==========

    private org.assertj.core.api.AbstractLongAssert<?> assertThatCountWithNumericOp(
            NumericFilterPredicate.NumericOperation operation, double actualValue, double threshold) {
        TsKvEntry entry = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", actualValue));

        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId1), eq("temperature")))
                .thenReturn(Futures.immediateFuture(entry));

        List<EntityData> candidates = List.of(buildEntityData(deviceId1));
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(candidates, 1, 1, false));

        KeyFilter filter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(operation, threshold));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(filter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);
        return assertThat(result);
    }

    private EntityListFilter buildEntityListFilter() {
        EntityListFilter filter = new EntityListFilter();
        filter.setEntityType(EntityType.DEVICE);
        filter.setEntityList(List.of(deviceId1.getId().toString()));
        return filter;
    }

    private EntityData buildEntityData(EntityId entityId) {
        return new EntityData(entityId, new HashMap<>(), null);
    }

    private KeyFilter buildKeyFilter(String key, EntityKeyType type, EntityKeyValueType valueType, KeyFilterPredicate predicate) {
        KeyFilter filter = new KeyFilter();
        filter.setKey(new EntityKey(type, key));
        filter.setValueType(valueType);
        filter.setPredicate(predicate);
        return filter;
    }

    private NumericFilterPredicate buildNumericPredicate(NumericFilterPredicate.NumericOperation operation, double value) {
        NumericFilterPredicate pred = new NumericFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromDouble(value));
        return pred;
    }

    private StringFilterPredicate buildStringPredicate(StringFilterPredicate.StringOperation operation, String value) {
        StringFilterPredicate pred = new StringFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromString(value));
        pred.setIgnoreCase(false);
        return pred;
    }

    private BooleanFilterPredicate buildBooleanPredicate(BooleanFilterPredicate.BooleanOperation operation, boolean value) {
        BooleanFilterPredicate pred = new BooleanFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromBoolean(value));
        return pred;
    }

}
