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

import org.thingsboard.server.common.data.id.TenantId;

import java.io.Serializable;
import java.util.UUID;

public record AttributeUpdateResultKey(TenantId tenantId, UUID requestId) implements Serializable {

    public AttributeUpdateResultKey {
        if (tenantId == null || tenantId.isNullUid()) {
            throw new IllegalArgumentException("tenantId must be specified");
        }
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must be specified");
        }
    }

    public static AttributeUpdateResultKey from(TenantId tenantId, AttributeUpdateRequest request) {
        return new AttributeUpdateResultKey(tenantId, request.requestId());
    }

    @Override
    public String toString() {
        return ":" + tenantId + ":" + requestId;
    }

}
