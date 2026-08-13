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
package org.thingsboard.server.service.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CacheValueQueryService {

    private final List<CacheValueQueryHandler> handlers;

    public CacheValueQueryResponse query(SecurityUser user, String resultType, String key) {
        return handlers.stream()
                .filter(handler -> handler.getResultType().equals(resultType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported cache result type: " + resultType))
                .query(user, key);
    }

}
