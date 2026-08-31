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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.BooleanFilterPredicate;
import org.thingsboard.server.common.data.query.ComplexFilterPredicate;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.FilterPredicateType;
import org.thingsboard.server.common.data.query.KeyFilter;
import org.thingsboard.server.common.data.query.KeyFilterPredicate;
import org.thingsboard.server.common.data.query.NumericFilterPredicate;
import org.thingsboard.server.common.data.query.StringFilterPredicate;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.dao.entity.EntityQueryDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Primary
@ConditionalOnExpression(
    "'${database.ts_latest.type:sql}' != 'sql' and '${database.ts_latest.type:sql}' != 'timescale'"
)
@Slf4j
public class TsLatestAwareEntityQueryDao implements EntityQueryDao {

    static final int SCAN_PAGE_SIZE = 1000;

    @Autowired
    @Qualifier("jpaEntityQueryDao")
    private EntityQueryDao delegate;

    @Autowired
    @Lazy
    private TimeseriesLatestDao timeseriesLatestDao;

    // ========== countEntitiesByQuery ==========

    @Override
    public long countEntitiesByQuery(TenantId tenantId, CustomerId customerId, EntityCountQuery query) {
        List<KeyFilter> tsFilters = extractTsKeyFilters(query.getKeyFilters());
        if (tsFilters.isEmpty()) {
            return delegate.countEntitiesByQuery(tenantId, customerId, query);
        }

        List<KeyFilter> nonTsFilters = extractNonTsKeyFilters(query.getKeyFilters());
        Set<String> tsKeys = tsFilters.stream()
                .map(f -> f.getKey().getKey())
                .collect(Collectors.toSet());

        // Iterate candidate pages and evaluate TS filters in Java
        long count = 0;
        int scanPage = 0;
        PageData<EntityData> candidates;
        do {
            EntityDataQuery scanQuery = new EntityDataQuery(
                    query.getEntityFilter(),
                    new EntityDataPageLink(SCAN_PAGE_SIZE, scanPage, null, null),
                    List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                    Collections.emptyList(),
                    nonTsFilters
            );
            candidates = delegate.findEntityDataByQuery(tenantId, customerId, scanQuery);
            for (EntityData entity : candidates.getData()) {
                Map<String, TsKvEntry> latestMap = fetchLatestForKeys(tenantId, entity.getEntityId(), tsKeys);
                if (evaluateFilters(latestMap, tsFilters)) {
                    count++;
                }
            }
            scanPage++;
        } while (candidates.hasNext());
        return count;
    }

    // ========== findEntityDataByQuery ==========

    @Override
    public PageData<EntityData> findEntityDataByQuery(TenantId tenantId, CustomerId customerId, EntityDataQuery query) {
        List<KeyFilter> tsFilters = extractTsKeyFilters(query.getKeyFilters());
        boolean hasTsLatestValues = hasTimeseriesLatestValues(query);

        // 是否按遥测 latest 值排序: 外部 latest 存储(IoTDB/Cassandra/Redis)的值不在 SQL 层,
        // delegate 无法据此排序, 必须由本类接管为"扫描全部候选 → Java 排序 → 手工分页"。
        // 因此即便没有遥测过滤条件、也没有遥测 latest 输出, 只要按遥测值排序就不能走 delegate 快路径。
        EntityDataSortOrder sortOrder = query.getPageLink().getSortOrder();
        boolean tsSort = sortOrder != null && sortOrder.getKey() != null
                && sortOrder.getKey().getType() == EntityKeyType.TIME_SERIES;

        if (tsFilters.isEmpty() && !hasTsLatestValues && !tsSort) {
            return delegate.findEntityDataByQuery(tenantId, customerId, query);
        }

        List<KeyFilter> nonTsFilters = extractNonTsKeyFilters(query.getKeyFilters());
        Set<String> filterTsKeys = tsFilters.stream()
                .map(f -> f.getKey().getKey())
                .collect(Collectors.toSet());

        // TS keys needed for latestValues output
        Set<String> latestTsKeys = new HashSet<>();
        if (query.getLatestValues() != null) {
            for (EntityKey ek : query.getLatestValues()) {
                if (ek.getType() == EntityKeyType.TIME_SERIES) {
                    latestTsKeys.add(ek.getKey());
                }
            }
        }

        // All TS keys we need to fetch (union of filter keys and latest output keys)
        Set<String> allTsKeys = new HashSet<>(filterTsKeys);
        allTsKeys.addAll(latestTsKeys);

        // Build query without TS key filters
        EntityDataQuery candidateQuery = new EntityDataQuery(
                query.getEntityFilter(),
                query.getPageLink(),
                query.getEntityFields(),
                // Remove TIME_SERIES from latestValues since we'll handle them
                query.getLatestValues() != null
                        ? query.getLatestValues().stream()
                            .filter(ek -> ek.getType() != EntityKeyType.TIME_SERIES)
                            .collect(Collectors.toList())
                        : null,
                nonTsFilters
        );

        if (tsFilters.isEmpty() && !tsSort) {
            // 无遥测过滤且不按遥测值排序: 只需回填 latest 值, delegate 的分页/排序即最终结果
            return fillTsLatestValues(tenantId, delegate.findEntityDataByQuery(tenantId, customerId, candidateQuery), latestTsKeys);
        }

        // 有遥测过滤条件, 或按遥测值排序: 扫描所有候选页, (过滤)、(排序), 再手工分页。
        int pageSize = query.getPageLink().getPageSize();
        int page = query.getPageLink().getPage();

        // 保留原始 textSearch 与 sortOrder(否则搜索框静默失效、排序丢失导致手工分页翻页不稳定)。
        //  - textSearch: 始终下推给 delegate(实体字段级过滤)。
        //  - sortOrder 指向非遥测键(名称/属性): delegate 能排序, 下推后按扫描页顺序累积,
        //    matched 天然保持全局有序, 手工分页正确。
        //  - sortOrder 指向遥测 latest 值(tsSort): delegate 无法排序(latest 在外部存储),
        //    扫描时旁路收集该键最新值, 全部匹配后在 Java 侧排序; 扫描 sortOrder 剥离为 null。
        String textSearch = query.getPageLink().getTextSearch();
        EntityDataSortOrder scanSort = tsSort ? null : sortOrder;
        String tsSortKey = tsSort ? sortOrder.getKey().getKey() : null;
        Set<String> fetchTsKeys = allTsKeys;
        if (tsSort && !fetchTsKeys.contains(tsSortKey)) {
            fetchTsKeys = new HashSet<>(allTsKeys);
            fetchTsKeys.add(tsSortKey);
        }
        Map<EntityId, TsKvEntry> tsSortValues = new HashMap<>();

        List<EntityData> matched = new ArrayList<>();
        int scanPage = 0;
        PageData<EntityData> candidates;
        do {
            EntityDataQuery scanQuery = new EntityDataQuery(
                    query.getEntityFilter(),
                    new EntityDataPageLink(SCAN_PAGE_SIZE, scanPage, textSearch, scanSort),
                    query.getEntityFields(),
                    candidateQuery.getLatestValues(),
                    nonTsFilters
            );
            candidates = delegate.findEntityDataByQuery(tenantId, customerId, scanQuery);
            for (EntityData entity : candidates.getData()) {
                Map<String, TsKvEntry> latestMap = fetchLatestForKeys(tenantId, entity.getEntityId(), fetchTsKeys);
                if (evaluateFilters(latestMap, tsFilters)) {
                    fillLatestValues(entity, latestMap, latestTsKeys);
                    if (tsSort) {
                        tsSortValues.put(entity.getEntityId(), latestMap.get(tsSortKey));
                    }
                    matched.add(entity);
                }
            }
            scanPage++;
        } while (candidates.hasNext());

        if (tsSort) {
            sortByTsValue(matched, tsSortValues, sortOrder.getDirection());
        }

        // Manual pagination
        int totalElements = matched.size();
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        int fromIndex = page * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalElements);

        if (fromIndex >= totalElements) {
            return new PageData<>(Collections.emptyList(), totalPages, totalElements, false);
        }

        List<EntityData> pageData = matched.subList(fromIndex, toIndex);
        return new PageData<>(pageData, totalPages, totalElements, toIndex < totalElements);
    }

    // ========== Helper methods ==========

    private List<KeyFilter> extractTsKeyFilters(List<KeyFilter> keyFilters) {
        if (keyFilters == null) {
            return Collections.emptyList();
        }
        return keyFilters.stream()
                .filter(f -> f.getKey().getType() == EntityKeyType.TIME_SERIES)
                .collect(Collectors.toList());
    }

    private List<KeyFilter> extractNonTsKeyFilters(List<KeyFilter> keyFilters) {
        if (keyFilters == null) {
            return Collections.emptyList();
        }
        return keyFilters.stream()
                .filter(f -> f.getKey().getType() != EntityKeyType.TIME_SERIES)
                .collect(Collectors.toList());
    }

    private boolean hasTimeseriesLatestValues(EntityDataQuery query) {
        if (query.getLatestValues() == null) {
            return false;
        }
        return query.getLatestValues().stream()
                .anyMatch(ek -> ek.getType() == EntityKeyType.TIME_SERIES);
    }

    private Map<String, TsKvEntry> fetchLatestForKeys(TenantId tenantId, EntityId entityId, Set<String> keys) {
        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ListenableFuture<TsKvEntry>> futures = keys.stream()
                .map(key -> timeseriesLatestDao.findLatest(tenantId, entityId, key))
                .toList();

        List<TsKvEntry> entries = Futures.getUnchecked(Futures.allAsList(futures));
        Map<String, TsKvEntry> result = new HashMap<>();
        for (TsKvEntry entry : entries) {
            if (entry != null) {
                result.put(entry.getKey(), entry);
            }
        }
        return result;
    }

    /**
     * 按遥测 latest 值对已匹配实体排序(delegate 无法排序外部存储的 latest)。数值优先按数值比较,
     * 否则退化为字符串比较; 缺失该键的实体始终排在最后(与升降序无关)。
     */
    private void sortByTsValue(List<EntityData> matched, Map<EntityId, TsKvEntry> values,
                               EntityDataSortOrder.Direction direction) {
        boolean desc = direction == EntityDataSortOrder.Direction.DESC;
        matched.sort((e1, e2) -> {
            // "无值"包含两种情况: 该键缺失(map 无条目)或存在但 value 为 null
            // (fetchLatestForKeys 只判 entry!=null, 会保留 value 为 null 的条目) —— 均排最后,
            // 且不能进入 compareTsValues(否则 getValueAsString() 返回 null 会 NPE)。
            boolean h1 = hasValue(values.get(e1.getEntityId()));
            boolean h2 = hasValue(values.get(e2.getEntityId()));
            if (!h1 && !h2) {
                return 0;
            }
            if (!h1) {
                return 1;   // nulls last, 与升降序无关
            }
            if (!h2) {
                return -1;
            }
            int cmp = compareTsValues(values.get(e1.getEntityId()), values.get(e2.getEntityId()));
            return desc ? -cmp : cmp;
        });
    }

    private static boolean hasValue(TsKvEntry e) {
        return e != null && e.getValue() != null;
    }

    /**
     * 全序比较器(必须满足传递性, 否则 TimSort 会抛 "Comparison method violates its general
     * contract" 使整个实体查询崩溃)。同一 key 在不同设备上可能混有数值与非数值(如个别设备
     * 上报字符串哨兵值), 这里定义确定的全序: 所有"可转数值"归为一类按数值比较, 且**整体排在**
     * 非数值之前; 非数值之间按字符串比较。这样避免"2<10、10<'15x'、'2'>'15x'"式的比较环。
     */
    private int compareTsValues(TsKvEntry a, TsKvEntry b) {
        Double da = asDouble(a);
        Double db = asDouble(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }
        if (da != null) {
            return -1;  // 数值整体排在非数值之前(固定, 保证传递性)
        }
        if (db != null) {
            return 1;
        }
        return a.getValueAsString().compareTo(b.getValueAsString());
    }

    private Double asDouble(TsKvEntry e) {
        if (e.getDoubleValue().isPresent()) {
            return e.getDoubleValue().get();
        }
        if (e.getLongValue().isPresent()) {
            return e.getLongValue().get().doubleValue();
        }
        try {
            return Double.parseDouble(e.getValueAsString());
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }

    private PageData<EntityData> fillTsLatestValues(TenantId tenantId, PageData<EntityData> result, Set<String> tsKeys) {
        if (tsKeys.isEmpty() || result.getData().isEmpty()) {
            return result;
        }
        for (EntityData entity : result.getData()) {
            Map<String, TsKvEntry> latestMap = fetchLatestForKeys(tenantId, entity.getEntityId(), tsKeys);
            fillLatestValues(entity, latestMap, tsKeys);
        }
        return result;
    }

    private void fillLatestValues(EntityData entity, Map<String, TsKvEntry> latestMap, Set<String> tsKeys) {
        if (tsKeys.isEmpty()) {
            return;
        }
        Map<EntityKeyType, Map<String, TsValue>> latest = entity.getLatest();
        if (latest == null) {
            latest = new HashMap<>();
            entity.setLatest(latest);
        }
        Map<String, TsValue> tsValues = latest.computeIfAbsent(EntityKeyType.TIME_SERIES, k -> new LinkedHashMap<>());
        for (String key : tsKeys) {
            TsKvEntry entry = latestMap.get(key);
            if (entry != null && entry.getValue() != null) {
                tsValues.put(key, entry.toTsValue());
            }
        }
    }

    // ========== Java-side KeyFilter evaluation ==========

    private boolean evaluateFilters(Map<String, TsKvEntry> latestMap, List<KeyFilter> tsFilters) {
        for (KeyFilter filter : tsFilters) {
            if (!evaluateFilter(latestMap, filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateFilter(Map<String, TsKvEntry> latestMap, KeyFilter filter) {
        TsKvEntry entry = latestMap.get(filter.getKey().getKey());
        return evaluatePredicate(entry, filter.getPredicate(), filter.getValueType());
    }

    private boolean evaluatePredicate(TsKvEntry entry, KeyFilterPredicate predicate, org.thingsboard.server.common.data.query.EntityKeyValueType valueType) {
        switch (predicate.getType()) {
            case NUMERIC:
                return evaluateNumeric(entry, (NumericFilterPredicate) predicate);
            case STRING:
                return evaluateString(entry, (StringFilterPredicate) predicate);
            case BOOLEAN:
                return evaluateBoolean(entry, (BooleanFilterPredicate) predicate);
            case COMPLEX:
                return evaluateComplex(entry, (ComplexFilterPredicate) predicate, valueType);
            default:
                return false;
        }
    }

    private boolean evaluateNumeric(TsKvEntry entry, NumericFilterPredicate pred) {
        if (entry == null || entry.getValue() == null) {
            return false;
        }
        double value;
        if (entry.getDoubleValue().isPresent()) {
            value = entry.getDoubleValue().get();
        } else if (entry.getLongValue().isPresent()) {
            value = entry.getLongValue().get().doubleValue();
        } else {
            try {
                value = Double.parseDouble(entry.getValueAsString());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        double threshold = pred.getValue().getValue();
        return switch (pred.getOperation()) {
            case EQUAL -> Double.compare(value, threshold) == 0;
            case NOT_EQUAL -> Double.compare(value, threshold) != 0;
            case GREATER -> value > threshold;
            case LESS -> value < threshold;
            case GREATER_OR_EQUAL -> value >= threshold;
            case LESS_OR_EQUAL -> value <= threshold;
        };
    }

    private boolean evaluateString(TsKvEntry entry, StringFilterPredicate pred) {
        if (entry == null || entry.getValue() == null) {
            return false;
        }
        String value = entry.getValueAsString();
        String threshold = pred.getValue().getValue();
        boolean ignoreCase = pred.isIgnoreCase();

        if (ignoreCase) {
            value = value.toLowerCase();
            threshold = threshold.toLowerCase();
        }

        return switch (pred.getOperation()) {
            case EQUAL -> value.equals(threshold);
            case NOT_EQUAL -> !value.equals(threshold);
            case STARTS_WITH -> value.startsWith(threshold);
            case ENDS_WITH -> value.endsWith(threshold);
            case CONTAINS -> value.contains(threshold);
            case NOT_CONTAINS -> !value.contains(threshold);
            case IN -> Arrays.asList(threshold.split(",")).contains(value);
            case NOT_IN -> !Arrays.asList(threshold.split(",")).contains(value);
        };
    }

    private boolean evaluateBoolean(TsKvEntry entry, BooleanFilterPredicate pred) {
        if (entry == null || entry.getValue() == null) {
            return false;
        }
        boolean value;
        if (entry.getBooleanValue().isPresent()) {
            value = entry.getBooleanValue().get();
        } else {
            value = Boolean.parseBoolean(entry.getValueAsString());
        }
        boolean threshold = pred.getValue().getValue();
        return switch (pred.getOperation()) {
            case EQUAL -> value == threshold;
            case NOT_EQUAL -> value != threshold;
        };
    }

    private boolean evaluateComplex(TsKvEntry entry, ComplexFilterPredicate pred, org.thingsboard.server.common.data.query.EntityKeyValueType valueType) {
        List<KeyFilterPredicate> predicates = pred.getPredicates();
        if (predicates == null || predicates.isEmpty()) {
            return true;
        }
        return switch (pred.getOperation()) {
            case AND -> predicates.stream().allMatch(p -> evaluatePredicate(entry, p, valueType));
            case OR -> predicates.stream().anyMatch(p -> evaluatePredicate(entry, p, valueType));
        };
    }

}
