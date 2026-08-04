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

import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.util.List;

/**
 * Optional fast path for backends that can acknowledge one telemetry message as a batch.
 * Implementations must complete the returned future only after every storable entry is durably
 * accepted by the backend, and return the sum of accepted {@link TsKvEntry#getDataPoints()}.
 * Backend-specific illegal entries may be isolated and reported separately so one poison key
 * does not prevent valid entries in the same telemetry message from being persisted.
 */
public interface BatchedTimeseriesDao {

    ListenableFuture<Integer> saveBatch(TenantId tenantId, EntityId entityId, List<TsKvEntry> entries, long ttl);

}
