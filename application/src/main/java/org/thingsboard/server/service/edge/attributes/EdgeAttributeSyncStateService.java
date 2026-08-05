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
package org.thingsboard.server.service.edge.attributes;

import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncState;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.Optional;
import java.util.UUID;

public interface EdgeAttributeSyncStateService {

    boolean isEnabled();

    void createPending(TenantId tenantId, EdgeAttributeSyncState state, long resultTtlSeconds);

    Optional<EdgeAttributeSyncState> get(TenantId tenantId, UUID requestId);

    void markSuccess(TenantId tenantId, UUID requestId, long now);

    void recordRetryFailure(TenantId tenantId, UUID requestId, String error);

    void markFailed(TenantId tenantId, UUID requestId, long now, String errorCode, String error);


}
