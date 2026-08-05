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

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncState;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnExpression("!'${cache.type:caffeine}'.equalsIgnoreCase('redis')")
public class NoopEdgeAttributeSyncStateService implements EdgeAttributeSyncStateService {

    @PostConstruct
    public void init() {
        log.warn("Edge attribute synchronization API is registered, but state storage is unavailable because cache.type is not redis");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void createPending(TenantId tenantId, EdgeAttributeSyncState state, long resultTtlSeconds) {
        throw new EdgeAttributeSyncException(HttpStatus.SERVICE_UNAVAILABLE,
                "SYNC_STATE_UNAVAILABLE", "Edge attribute synchronization requires cache.type=redis");
    }

    @Override
    public Optional<EdgeAttributeSyncState> get(TenantId tenantId, UUID requestId) {
        return Optional.empty();
    }

    @Override
    public void markSuccess(TenantId tenantId, UUID requestId, long now) {
    }

    @Override
    public void recordRetryFailure(TenantId tenantId, UUID requestId, String error) {
    }

    @Override
    public void markFailed(TenantId tenantId, UUID requestId, long now, String errorCode, String error) {
    }

}
