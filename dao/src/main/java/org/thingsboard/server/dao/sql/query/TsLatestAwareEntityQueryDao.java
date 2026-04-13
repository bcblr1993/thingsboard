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

    static final int MAX_CANDIDATES = 10000;

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

        // Build a data query to fetch candidate entities (without TS filters)
        EntityDataQuery dataQuery = new EntityDataQuery(
                query.getEntityFilter(),
                new EntityDataPageLink(MAX_CANDIDATES, 0, null, null),
                List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                Collections.emptyList(),
                nonTsFilters
        );

        PageData<EntityData> candidates = delegate.findEntityDataByQuery(tenantId, customerId, dataQuery);
        if (candidates.getData().isEmpty()) {
            return 0;
        }

        // Fetch TS latest values from the configured backend and evaluate filters in Java
        long count = 0;
        for (EntityData entity : candidates.getData()) {
            Map<String, TsKvEntry> latestMap = fetchLatestForKeys(tenantId, entity.getEntityId(), tsKeys);
            if (evaluateFilters(latestMap, tsFilters)) {
                count++;
            }
        }
        return count;
    }

    // ========== findEntityDataByQuery ==========

    @Override
    public PageData<EntityData> findEntityDataByQuery(TenantId tenantId, CustomerId customerId, EntityDataQuery query) {
        List<KeyFilter> tsFilters = extractTsKeyFilters(query.getKeyFilters());
        boolean hasTsLatestValues = hasTimeseriesLatestValues(query);

        if (tsFilters.isEmpty() && !hasTsLatestValues) {
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

        if (tsFilters.isEmpty()) {
            // No TS filters, just need to fill TS latest values
            return fillTsLatestValues(tenantId, delegate.findEntityDataByQuery(tenantId, customerId, candidateQuery), latestTsKeys);
        }

        // Has TS filters: fetch oversized candidate pages, filter, then manually paginate
        int pageSize = query.getPageLink().getPageSize();
        int page = query.getPageLink().getPage();

        // We need to scan through candidates up to (page + 1) * pageSize matching entities
        // Use a generous over-fetch strategy
        int maxScan = Math.min(MAX_CANDIDATES, pageSize * (page + 1) * 5);
        EntityDataQuery scanQuery = new EntityDataQuery(
                query.getEntityFilter(),
                new EntityDataPageLink(maxScan, 0, null, null),
                query.getEntityFields(),
                candidateQuery.getLatestValues(),
                nonTsFilters
        );

        PageData<EntityData> allCandidates = delegate.findEntityDataByQuery(tenantId, customerId, scanQuery);

        // Filter by TS key filters
        List<EntityData> matched = new ArrayList<>();
        for (EntityData entity : allCandidates.getData()) {
            Map<String, TsKvEntry> latestMap = fetchLatestForKeys(tenantId, entity.getEntityId(), allTsKeys);
            if (evaluateFilters(latestMap, tsFilters)) {
                // Fill TS latest values into EntityData
                fillLatestValues(entity, latestMap, latestTsKeys);
                matched.add(entity);
            }
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
