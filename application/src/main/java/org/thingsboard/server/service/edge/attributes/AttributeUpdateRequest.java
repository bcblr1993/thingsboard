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

import java.util.UUID;

public record AttributeUpdateRequest(UUID requestId) {

    public AttributeUpdateRequest {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must be a canonical UUID string");
        }
    }

    public static AttributeUpdateRequest parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("requestId must be a canonical UUID string");
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(value);
            if (!requestId.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("requestId must be a canonical UUID string");
        }
        return new AttributeUpdateRequest(requestId);
    }

    public String encode() {
        return requestId.toString();
    }

}
