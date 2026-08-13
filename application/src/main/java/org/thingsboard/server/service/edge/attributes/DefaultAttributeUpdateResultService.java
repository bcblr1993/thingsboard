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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thingsboard.server.cache.TbCacheValueWrapper;
import org.thingsboard.server.cache.TbTransactionalCache;

import java.util.Optional;

@Service
public class DefaultAttributeUpdateResultService implements AttributeUpdateResultService {

    private final TbTransactionalCache<AttributeUpdateResultKey, Boolean> cache;

    public DefaultAttributeUpdateResultService(
            @Qualifier("AttributeUpdateResultsCache")
            TbTransactionalCache<AttributeUpdateResultKey, Boolean> cache) {
        this.cache = cache;
    }

    @Override
    public void saveResult(AttributeUpdateResultKey key, boolean success) {
        cache.put(key, success);
    }

    @Override
    public Optional<Boolean> findResult(AttributeUpdateResultKey key) {
        TbCacheValueWrapper<Boolean> value = cache.get(key);
        return value == null ? Optional.empty() : Optional.ofNullable(value.get());
    }

    @Override
    public void evictResult(AttributeUpdateResultKey key) {
        cache.evict(key);
    }

}
