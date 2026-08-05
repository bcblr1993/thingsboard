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
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class EdgeAttributeSyncSettings {

    @Value("${edges.attribute_sync.default_result_ttl_seconds:300}")
    private long defaultResultTtlSeconds;

    @Value("${edges.attribute_sync.min_result_ttl_seconds:120}")
    private long minResultTtlSeconds;

    @Value("${edges.attribute_sync.max_result_ttl_seconds:3600}")
    private long maxResultTtlSeconds;

    @Value("${edges.attribute_sync.recommended_poll_interval_ms:3000}")
    private long recommendedPollIntervalMs;

    @Value("${edges.attribute_sync.uid_prefix:edge-attr-sync:}")
    private String uidPrefix;

    @PostConstruct
    public void validate() {
        if (minResultTtlSeconds <= 0) {
            throw new IllegalStateException("edges.attribute_sync.min_result_ttl_seconds must be positive");
        }
        if (maxResultTtlSeconds < minResultTtlSeconds) {
            throw new IllegalStateException("edges.attribute_sync.max_result_ttl_seconds must not be less than min_result_ttl_seconds");
        }
        if (defaultResultTtlSeconds < minResultTtlSeconds || defaultResultTtlSeconds > maxResultTtlSeconds) {
            throw new IllegalStateException("edges.attribute_sync.default_result_ttl_seconds must be within the configured TTL range");
        }
        long minResultTtlMillis;
        try {
            minResultTtlMillis = Math.multiplyExact(minResultTtlSeconds, 1000L);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("edges.attribute_sync.min_result_ttl_seconds is too large", e);
        }
        if (recommendedPollIntervalMs <= 0 || recommendedPollIntervalMs >= minResultTtlMillis) {
            throw new IllegalStateException("edges.attribute_sync.recommended_poll_interval_ms must be positive and less than min_result_ttl_seconds");
        }
        if (uidPrefix == null || uidPrefix.isBlank()) {
            throw new IllegalStateException("edges.attribute_sync.uid_prefix must not be blank");
        }
    }

}
