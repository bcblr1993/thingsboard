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

import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.Map;

public interface TbTelemetryService {

    ListenableFuture<List<TsKvEntry>> getTimeseries(EntityId entityId,
                                                   List<String> keys,
                                                   Long startTs,
                                                   Long endTs,
                                                   IntervalType intervalType,
                                                   Long interval,
                                                   String timeZone,
                                                   Integer limit,
                                                   Aggregation agg,
                                                   String orderBy,
                                                   Boolean useStrictDataTypes,
                                                   SecurityUser currentUser) throws ThingsboardException;

    ListenableFuture<List<TsKvEntry>> getTimeseriesFill(EntityId entityId,
                                                        List<String> keys,
                                                        Long startTs,
                                                        Long endTs,
                                                        Long interval,
                                                        Aggregation agg,
                                                        Boolean fillMissing,
                                                        String orderBy,
                                                        SecurityUser currentUser) throws ThingsboardException;

    /**
     * 获取单设备单测点从{@code startTs}到{@code endTs}的每个{@code interval}内的第一个值。
     *
     * @param entityId the entity ID
     * @param keys the time series keys
     * @param startTs the start timestamp (inclusive)
     * @param endTs the end timestamp (inclusive)
     * @param interval the interval size in milliseconds
     * @param useStrictDataTypes whether to use strict data types
     * @param currentUser the current user for permission check
     * @return a future containing the first values in each interval, sorted by timestamp
     */
    ListenableFuture<Map<String, List<FormattedTsData>>> getTimeseriesFirstValue(EntityId entityId,
                                                                                 List<String> keys,
                                                                                 Long startTs,
                                                                                 Long endTs,
                                                                                 Long interval,
                                                                                 Boolean useStrictDataTypes,
                                                                                 SecurityUser currentUser) throws ThingsboardException;


}
