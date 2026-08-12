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
import com.google.common.util.concurrent.MoreExecutors;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.List;

/**
 * Optional latest DAO capability for backends that persist all measurements of one telemetry
 * message with a single backend operation.
 */
public interface BatchedTimeseriesLatestWriteDao {

    ListenableFuture<Long> saveLatest(TenantId tenantId, EntityId entityId, List<TsKvEntry> tsKvEntries);

    /**
     * Persists the batch and returns one version per input entry. Redis latest storage has no
     * database-generated row version, so the telemetry timestamp is used as its monotonic version.
     */
    default ListenableFuture<List<Long>> saveLatestBatch(TenantId tenantId, EntityId entityId,
                                                          List<TsKvEntry> tsKvEntries) {
        return Futures.transform(saveLatest(tenantId, entityId, tsKvEntries), ignored ->
                        tsKvEntries.stream().map(TsKvEntry::getTs).toList(),
                MoreExecutors.directExecutor());
    }

}
