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
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
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
import static org.mockito.ArgumentMatchers.argThat;
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

    // ==================== countEntitiesByQuery 计数查询测试 ====================

    /**
     * 当查询中没有 TIME_SERIES 类型的 KeyFilter 时，应直接委托给底层 delegate 执行，
     * 不需要从 TimeseriesLatestDao 获取遥测数据进行内存过滤。
     */
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

    /**
     * 当查询中包含 TIME_SERIES 类型的 KeyFilter 时，应从 TimeseriesLatestDao 获取最新遥测值，
     * 在内存中根据过滤条件进行筛选。
     * 场景：3 个设备，device1(temperature=25.5)、device2(temperature=18.0)、device3(无遥测数据)，
     * 过滤条件 temperature > 20，预期只有 device1 匹配，计数为 1。
     */
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

    /**
     * 当 delegate 返回空候选列表时（即没有匹配的基础实体），应直接返回 0，
     * 不应调用 TimeseriesLatestDao 查询遥测数据。
     */
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

    /**
     * 多个 TIME_SERIES KeyFilter 应取交集（AND 语义），实体必须同时满足所有过滤条件。
     * 场景：device1(temperature=25.5, humidity=60)、device2(temperature=25.5, humidity=30)，
     * 过滤条件 temperature > 20 AND humidity >= 50，预期只有 device1 同时满足两个条件。
     */
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

    // ==================== findEntityDataByQuery 数据查询测试 ====================

    /**
     * 当查询中没有 TIME_SERIES 类型的 keyFilters 和 latestValues 时，
     * 应直接委托给底层 delegate 处理，不涉及遥测数据填充。
     */
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

    /**
     * 当查询的 latestValues 中包含 TIME_SERIES 类型的 key，但没有 ts keyFilters 时，
     * 应从 TimeseriesLatestDao 获取最新遥测值并填充到 EntityData 的 latest 映射中。
     * 验证：返回的 EntityData 中 latest[TIME_SERIES]["temperature"] 的 ts 和值正确。
     */
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

    /**
     * 验证带 TIME_SERIES KeyFilter 的数据查询能正确过滤并进行分页。
     * 场景：5 个设备，temperature 分别为 25/15/30/10/35，过滤条件 temperature > 20，
     * 匹配 d1(25)、d3(30)、d5(35) 共 3 个。分页参数 pageSize=2, page=0，
     * 预期返回 2 条数据，totalElements=3，hasNext=true。
     */
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

    /**
     * 当请求的分页页码超出实际匹配结果范围时，应返回空数据列表，
     * 但 totalElements 仍反映实际匹配总数，hasNext=false。
     * 场景：1 个匹配设备，请求 page=5，预期 data 为空，totalElements=1。
     */
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

    // ==================== 过滤谓词求值测试 ====================

    /**
     * 验证字符串类型过滤谓词（StringFilterPredicate）的求值逻辑。
     * 场景：device1(status="active")、device2(status="inactive")，
     * 过滤条件 status EQUAL "active"，预期只有 device1 匹配。
     */
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

    /**
     * 验证布尔类型过滤谓词（BooleanFilterPredicate）的求值逻辑。
     * 场景：device1(enabled=true)、device2(enabled=false)，
     * 过滤条件 enabled EQUAL true，预期只有 device1 匹配。
     */
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

    /**
     * 验证复合过滤谓词（ComplexFilterPredicate）AND 操作的求值逻辑。
     * 场景：device1(temperature=25.0)、device2(temperature=35.0)，
     * 过滤条件 temperature > 20 AND temperature < 30，
     * 预期只有 device1(25.0) 满足 20 < temp < 30 的范围条件。
     */
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

    /**
     * 验证字符串 CONTAINS 操作的求值逻辑。
     * 场景：device1(description="temperature sensor")、device2(description="humidity sensor")，
     * 过滤条件 description CONTAINS "temperature"，预期只有 device1 匹配。
     */
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

    /**
     * 批量验证数值过滤谓词（NumericFilterPredicate）所有 6 种比较操作的正确性：
     * - EQUAL / NOT_EQUAL：等于/不等于
     * - GREATER / LESS：大于/小于
     * - GREATER_OR_EQUAL / LESS_OR_EQUAL：大于等于/小于等于
     * 每种操作测试匹配和不匹配两种情况。
     */
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

    /**
     * 当遥测值为 null 时（即该 key 没有数据），任何过滤条件都不应匹配。
     * 验证 null 值不会导致过滤求值异常，且计数结果为 0。
     */
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

    /**
     * 验证字符串过滤谓词的忽略大小写（ignoreCase=true）功能。
     * 场景：device1(status="Active")，过滤条件 status EQUAL "active"（ignoreCase=true），
     * 预期匹配成功，计数为 1。
     */
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

    /**
     * 验证复合过滤谓词（ComplexFilterPredicate）OR 操作的求值逻辑。
     * 场景：d1(temperature=25.0)、d2(temperature=35.0)、d3(temperature=5.0)，
     * 过滤条件 temperature > 30 OR temperature < 10，
     * 预期 d2(35.0) 和 d3(5.0) 匹配，d1(25.0) 不匹配。
     */
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

    // ==================== 多页迭代扫描测试 ====================

    /**
     * 验证 count 查询在候选实体跨多页时的正确性。
     * 模拟：第 0 页有 2 个设备（hasNext=true），第 1 页有 1 个设备（hasNext=false）。
     * 过滤条件 temperature > 20，预期只有 d1(25.5) 和 d3(30.0) 匹配，计数为 2。
     * 此测试验证了修复前 MAX_CANDIDATES 截断导致的计数不准问题已解决。
     */
    @Test
    void countEntitiesByQuery_candidatesSpanMultiplePages_shouldScanAllPages() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.5));
        TsKvEntry temp10 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 10.0));
        TsKvEntry temp30 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 30.0));

        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId1), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId2), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp10));
        when(timeseriesLatestDao.findLatest(eq(TENANT_ID), eq(deviceId3), eq("temperature")))
                .thenReturn(Futures.immediateFuture(temp30));

        // Page 0: deviceId1, deviceId2, hasNext=true
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID),
                argThat(q -> q instanceof EntityDataQuery && ((EntityDataQuery) q).getPageLink().getPage() == 0)))
                .thenReturn(new PageData<>(List.of(buildEntityData(deviceId1), buildEntityData(deviceId2)), 2, 3, true));
        // Page 1: deviceId3, hasNext=false
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID),
                argThat(q -> q instanceof EntityDataQuery && ((EntityDataQuery) q).getPageLink().getPage() == 1)))
                .thenReturn(new PageData<>(List.of(buildEntityData(deviceId3)), 2, 3, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));
        EntityCountQuery query = new EntityCountQuery(buildEntityListFilter(), List.of(tsFilter));

        long result = tsLatestAwareEntityQueryDao.countEntitiesByQuery(TENANT_ID, CUSTOMER_ID, query);

        // d1(25.5>20) and d3(30.0>20) match; d2(10.0) does not
        assertThat(result).isEqualTo(2L);
    }

    /**
     * 验证数据查询在候选实体跨多页时能正确收集匹配结果并进行分页。
     * 模拟：第 0 页 3 个设备（hasNext=true），第 1 页 2 个设备（hasNext=false）。
     * 过滤条件 temperature > 20，匹配 d1(25)、d4(30)、d5(35) 共 3 个。
     * 分页参数 pageSize=2, page=0，预期返回 2 条，totalElements=3，hasNext=true。
     */
    @Test
    void findEntityDataByQuery_candidatesSpanMultiplePages_shouldCollectAllMatches() {
        DeviceId d4 = new DeviceId(UUID.randomUUID());
        DeviceId d5 = new DeviceId(UUID.randomUUID());

        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp10 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 10.0));
        TsKvEntry temp15 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 15.0));
        TsKvEntry temp30 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 30.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(temp10));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId3, "temperature")).thenReturn(Futures.immediateFuture(temp15));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d4, "temperature")).thenReturn(Futures.immediateFuture(temp30));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d5, "temperature")).thenReturn(Futures.immediateFuture(temp35));

        // Page 0: 3 devices, hasNext=true
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID),
                argThat(q -> q instanceof EntityDataQuery && ((EntityDataQuery) q).getPageLink().getPage() == 0)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(deviceId1), buildEntityData(deviceId2), buildEntityData(deviceId3)),
                        2, 5, true));
        // Page 1: 2 devices, hasNext=false
        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID),
                argThat(q -> q instanceof EntityDataQuery && ((EntityDataQuery) q).getPageLink().getPage() == 1)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(d4), buildEntityData(d5)),
                        2, 5, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));

        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(2, 0, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                List.of(tsFilter)
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        // d1(25), d4(30), d5(35) match across two candidate pages
        assertThat(result.getData()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.hasNext()).isTrue();
    }

    // ==================== textSearch / sortOrder 保留测试 ====================

    /**
     * 带遥测过滤时, 原始 textSearch 与非遥测 sortOrder(按名称)必须下推给 delegate 扫描,
     * 否则搜索框静默失效、排序丢失。验证传给 delegate 的扫描查询 pageLink 携带二者。
     */
    @Test
    void findEntityDataByQuery_withTsFilter_shouldPushTextSearchAndNonTsSortToScan() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));

        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(List.of(buildEntityData(deviceId1)), 1, 1, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 20.0));
        EntityDataSortOrder nameSort = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.ENTITY_FIELD, "name"), EntityDataSortOrder.Direction.ASC);
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, "sensor-A", nameSort),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                List.of(tsFilter)
        );

        tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        org.mockito.ArgumentCaptor<EntityDataQuery> captor = org.mockito.ArgumentCaptor.forClass(EntityDataQuery.class);
        verify(delegate).findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), captor.capture());
        EntityDataPageLink scanLink = captor.getValue().getPageLink();
        assertThat(scanLink.getTextSearch()).isEqualTo("sensor-A");
        assertThat(scanLink.getSortOrder()).isNotNull();
        assertThat(scanLink.getSortOrder().getKey().getKey()).isEqualTo("name");
        assertThat(scanLink.getSortOrder().getDirection()).isEqualTo(EntityDataSortOrder.Direction.ASC);
    }

    /**
     * 按遥测 latest 值排序时, delegate 无法排序(latest 在外部存储), 应在 Java 侧对匹配结果排序。
     * 场景: d1=25, d2=35, d3=15, 过滤 temperature>0, 按 temperature DESC —— 预期顺序 d2,d1,d3;
     * 同时验证下推给 delegate 的扫描 sortOrder 被剥离为 null(避免委托层对未知列排序报错)。
     */
    @Test
    void findEntityDataByQuery_withTsValueSort_shouldSortInJavaDescending() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));
        TsKvEntry temp15 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 15.0));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(temp35));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId3, "temperature")).thenReturn(Futures.immediateFuture(temp15));

        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(deviceId1), buildEntityData(deviceId2), buildEntityData(deviceId3)),
                        1, 3, false));

        KeyFilter tsFilter = buildKeyFilter("temperature", EntityKeyType.TIME_SERIES, EntityKeyValueType.NUMERIC,
                buildNumericPredicate(NumericFilterPredicate.NumericOperation.GREATER, 0.0));
        EntityDataSortOrder tsSort = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.TIME_SERIES, "temperature"), EntityDataSortOrder.Direction.DESC);
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, tsSort),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                List.of(tsFilter)
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result.getData()).extracting(EntityData::getEntityId)
                .containsExactly(deviceId2, deviceId1, deviceId3); // 35, 25, 15 降序

        org.mockito.ArgumentCaptor<EntityDataQuery> captor = org.mockito.ArgumentCaptor.forClass(EntityDataQuery.class);
        verify(delegate).findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), captor.capture());
        assertThat(captor.getValue().getPageLink().getSortOrder()).isNull(); // 遥测排序不下推
    }

    /**
     * 只按遥测 latest 值排序、没有任何遥测过滤条件时, 也必须由本类接管为 Java 侧排序 —— delegate
     * (SQL 层)排不了外部存储的 latest, 直接下推会得到不可靠顺序。
     * 场景: d1=25, d2=35, d3=15, 无过滤, 按 temperature DESC, 预期 d2,d1,d3; 且下推的扫描
     * sortOrder 被剥离为 null。
     */
    @Test
    void findEntityDataByQuery_tsValueSortWithoutFilter_shouldTakeOverAndSort() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry temp35 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 35.0));
        TsKvEntry temp15 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 15.0));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(temp35));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId3, "temperature")).thenReturn(Futures.immediateFuture(temp15));

        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(deviceId1), buildEntityData(deviceId2), buildEntityData(deviceId3)),
                        1, 3, false));

        EntityDataSortOrder tsSort = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.TIME_SERIES, "temperature"), EntityDataSortOrder.Direction.DESC);
        // 无遥测过滤、无遥测 latest 输出, 仅按遥测值排序
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, tsSort),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                Collections.emptyList()
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        assertThat(result.getData()).extracting(EntityData::getEntityId)
                .containsExactly(deviceId2, deviceId1, deviceId3);

        org.mockito.ArgumentCaptor<EntityDataQuery> captor = org.mockito.ArgumentCaptor.forClass(EntityDataQuery.class);
        verify(delegate).findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), captor.capture());
        assertThat(captor.getValue().getPageLink().getSortOrder()).isNull(); // 遥测排序不下推给 SQL
    }

    /**
     * 按遥测值排序时, 某设备该键"存在但值为 null"(latest DAO 可能返回 value 为 null 的条目),
     * 不得抛 NPE —— 无值实体排在最后。回归 compareTsValues 对 getValueAsString()==null 的崩溃。
     */
    @Test
    void findEntityDataByQuery_tsValueSort_nullValue_shouldNotThrowAndSortLast() {
        TsKvEntry temp25 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 25.0));
        TsKvEntry tempNull = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("temperature", null));
        TsKvEntry temp15 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 15.0));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(temp25));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(tempNull));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId3, "temperature")).thenReturn(Futures.immediateFuture(temp15));

        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(deviceId1), buildEntityData(deviceId2), buildEntityData(deviceId3)),
                        1, 3, false));

        EntityDataSortOrder tsSort = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.TIME_SERIES, "temperature"), EntityDataSortOrder.Direction.DESC);
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, tsSort),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                Collections.emptyList()
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        // 25, 15 降序在前, 值为 null 的 d2 排最后 —— 且全程不抛异常
        assertThat(result.getData()).extracting(EntityData::getEntityId)
                .containsExactly(deviceId1, deviceId3, deviceId2);
    }

    /**
     * 同一 key 在不同设备上混有数值与非数值时, 比较器必须是全序(可传递), 否则 TimSort 抛
     * "Comparison method violates its general contract" 使整个查询崩溃。验证结果稳定有序:
     * 数值(按值)整体排在非数值(按字符串)之前, 不出现比较环。
     */
    @Test
    void findEntityDataByQuery_tsValueSort_mixedNumericAndString_totalOrderNoThrow() {
        DeviceId d4 = new DeviceId(UUID.randomUUID());
        TsKvEntry num2 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 2.0));
        TsKvEntry num10 = new BasicTsKvEntry(System.currentTimeMillis(), new DoubleDataEntry("temperature", 10.0));
        TsKvEntry str15x = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("temperature", "15x"));
        TsKvEntry strAbc = new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry("temperature", "abc"));

        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId1, "temperature")).thenReturn(Futures.immediateFuture(num2));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId2, "temperature")).thenReturn(Futures.immediateFuture(num10));
        when(timeseriesLatestDao.findLatest(TENANT_ID, deviceId3, "temperature")).thenReturn(Futures.immediateFuture(str15x));
        when(timeseriesLatestDao.findLatest(TENANT_ID, d4, "temperature")).thenReturn(Futures.immediateFuture(strAbc));

        when(delegate.findEntityDataByQuery(eq(TENANT_ID), eq(CUSTOMER_ID), any(EntityDataQuery.class)))
                .thenReturn(new PageData<>(
                        List.of(buildEntityData(deviceId1), buildEntityData(deviceId2),
                                buildEntityData(deviceId3), buildEntityData(d4)),
                        1, 4, false));

        EntityDataSortOrder tsSort = new EntityDataSortOrder(
                new EntityKey(EntityKeyType.TIME_SERIES, "temperature"), EntityDataSortOrder.Direction.ASC);
        EntityDataQuery query = new EntityDataQuery(
                buildEntityListFilter(),
                new EntityDataPageLink(10, 0, null, tsSort),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                Collections.emptyList()
        );

        PageData<EntityData> result = tsLatestAwareEntityQueryDao.findEntityDataByQuery(TENANT_ID, CUSTOMER_ID, query);

        // 数值(2,10 按值) 整体在前, 非数值(15x,abc 按字符串) 在后 —— 全序, 无异常
        assertThat(result.getData()).extracting(EntityData::getEntityId)
                .containsExactly(deviceId1, deviceId2, deviceId3, d4);
    }

    // ==================== 辅助方法 ====================

    /**
     * 辅助方法：构造一个包含单个设备和指定数值过滤条件的计数查询，执行并返回对结果的断言对象。
     * 用于批量测试不同数值比较操作的场景。
     *
     * @param operation   数值比较操作（EQUAL, GREATER 等）
     * @param actualValue 设备实际的遥测值
     * @param threshold   过滤条件的阈值
     * @return 对计数值的 AbstractLongAssert，可链式调用进行断言
     */
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

    /** 构建实体列表过滤器，筛选设备类型，默认包含 deviceId1 */
    private EntityListFilter buildEntityListFilter() {
        EntityListFilter filter = new EntityListFilter();
        filter.setEntityType(EntityType.DEVICE);
        filter.setEntityList(List.of(deviceId1.getId().toString()));
        return filter;
    }

    /** 构建包含指定实体 ID、空的 latest 映射和空 label 的 EntityData */
    private EntityData buildEntityData(EntityId entityId) {
        return new EntityData(entityId, new HashMap<>(), null);
    }

    /** 构建指定 key 类型、值类型和过滤谓词的 KeyFilter */
    private KeyFilter buildKeyFilter(String key, EntityKeyType type, EntityKeyValueType valueType, KeyFilterPredicate predicate) {
        KeyFilter filter = new KeyFilter();
        filter.setKey(new EntityKey(type, key));
        filter.setValueType(valueType);
        filter.setPredicate(predicate);
        return filter;
    }

    /** 构建数值比较过滤谓词 */
    private NumericFilterPredicate buildNumericPredicate(NumericFilterPredicate.NumericOperation operation, double value) {
        NumericFilterPredicate pred = new NumericFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromDouble(value));
        return pred;
    }

    /** 构建字符串比较过滤谓词，默认不忽略大小写 */
    private StringFilterPredicate buildStringPredicate(StringFilterPredicate.StringOperation operation, String value) {
        StringFilterPredicate pred = new StringFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromString(value));
        pred.setIgnoreCase(false);
        return pred;
    }

    /** 构建布尔比较过滤谓词 */
    private BooleanFilterPredicate buildBooleanPredicate(BooleanFilterPredicate.BooleanOperation operation, boolean value) {
        BooleanFilterPredicate pred = new BooleanFilterPredicate();
        pred.setOperation(operation);
        pred.setValue(FilterPredicateValue.fromBoolean(value));
        return pred;
    }

}
