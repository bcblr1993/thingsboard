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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.dao.edge.RelatedEdgesService;

import java.util.List;

import static org.thingsboard.server.dao.edge.BaseRelatedEdgesService.FIRST_PAGE;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAttributeUpdateResultTrackingService implements AttributeUpdateResultTrackingService {

    private final RelatedEdgesService relatedEdgesService;
    private final AttributeUpdateResultService resultService;

    @Override
    public void clearPreviousResult(TenantId tenantId, AttributeUpdateRequest request) {
        AttributeUpdateResultKey key = AttributeUpdateResultKey.from(tenantId, request);
        try {
            resultService.evictResult(key);
        } catch (RuntimeException e) {
            log.warn("[{}][{}] Failed to clear previous attribute update result",
                    tenantId, request.requestId(), e);
        }
    }

    @Override
    public boolean shouldTrackEdgeResult(TenantId tenantId, EntityId entityId, AttributeUpdateRequest request) {
        try {
            PageData<EdgeId> relatedEdges = relatedEdgesService.findEdgeIdsByEntityId(tenantId, entityId, FIRST_PAGE);
            List<EdgeId> edgeIds = relatedEdges.getData();
            if (edgeIds.isEmpty()) {
                log.debug("[{}][{}][{}] Entity is not assigned to an Edge; Cloud save is the final result",
                        tenantId, entityId, request.requestId());
                saveResult(tenantId, request, true);
                return false;
            }
            if (edgeIds.size() > 1 || relatedEdges.hasNext()) {
                log.error("[{}][{}][{}] Entity is assigned to multiple Edges",
                        tenantId, entityId, request.requestId());
                saveResult(tenantId, request, false);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.error("[{}][{}][{}] Failed to resolve related Edge",
                    tenantId, entityId, request.requestId(), e);
            saveResult(tenantId, request, false);
            return false;
        }
    }

    @Override
    public void saveResult(TenantId tenantId, AttributeUpdateRequest request, boolean success) {
        AttributeUpdateResultKey key = AttributeUpdateResultKey.from(tenantId, request);
        try {
            resultService.saveResult(key, success);
            log.debug("[{}][{}] Stored attribute update result [{}]",
                    tenantId, request.requestId(), success);
        } catch (RuntimeException e) {
            log.warn("[{}][{}] Failed to store attribute update result [{}]",
                    tenantId, request.requestId(), success, e);
        }
    }

}
