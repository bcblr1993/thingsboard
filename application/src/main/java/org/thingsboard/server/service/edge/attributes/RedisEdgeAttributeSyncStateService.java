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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncState;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncStatus;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnExpression("'${cache.type:caffeine}'.equalsIgnoreCase('redis')")
public class RedisEdgeAttributeSyncStateService implements EdgeAttributeSyncStateService {

    private static final String KEY_PREFIX = "tb:edge:attribute-sync:";

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1],
                'requestId', ARGV[1],
                'tenantId', ARGV[2],
                'edgeId', ARGV[3],
                'entityId', ARGV[4],
                'entityType', ARGV[5],
                'scope', ARGV[6],
                'status', 'PENDING',
                'createdTime', ARGV[7],
                'expireAt', ARGV[8],
                'completedTime', '0',
                'retryCount', '0',
                'errorCode', '',
                'lastError', '')
            redis.call('EXPIRE', KEYS[1], ARGV[9])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> SUCCESS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'SUCCESS', 'completedTime', ARGV[1],
                'errorCode', '', 'lastError', '')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RETRY_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then return 0 end
            redis.call('HINCRBY', KEYS[1], 'retryCount', 1)
            redis.call('HSET', KEYS[1], 'lastError', ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'FAILED', 'completedTime', ARGV[1],
                'errorCode', ARGV[2], 'lastError', ARGV[3])
            return 1
            """, Long.class);


    private final RedisTemplate<String, String> redisTemplate;
    private final EdgeAttributeSyncSettings settings;

    public RedisEdgeAttributeSyncStateService(
            @Qualifier("redisTemplateString") RedisTemplate<String, String> redisTemplate,
            EdgeAttributeSyncSettings settings) {
        this.redisTemplate = redisTemplate;
        this.settings = settings;
    }

    @PostConstruct
    public void init() {
        log.info("Edge attribute synchronization state service started with Redis: defaultResultTtlSeconds [{}], " +
                        "resultTtlRange [{}-{}]",
                settings.getDefaultResultTtlSeconds(), settings.getMinResultTtlSeconds(), settings.getMaxResultTtlSeconds());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void createPending(TenantId tenantId, EdgeAttributeSyncState state, long resultTtlSeconds) {
        try {
            Long result = redisTemplate.execute(CREATE_SCRIPT,
                    List.of(stateKey(tenantId, state.getRequestId())),
                    state.getRequestId().toString(), tenantId.toString(), state.getEdgeId().toString(),
                    state.getEntityId().toString(), state.getEntityType().name(), state.getScope().name(),
                    Long.toString(state.getCreatedTime()), Long.toString(state.getExpireAt()),
                    Long.toString(resultTtlSeconds));
            if (!Long.valueOf(1L).equals(result)) {
                throw redisUnavailable("Redis did not create edge attribute synchronization state", null);
            }
            log.debug("[{}][{}] Created Edge attribute synchronization state in Redis with TTL [{}] seconds",
                    tenantId, state.getRequestId(), resultTtlSeconds);
        } catch (EdgeAttributeSyncException e) {
            throw e;
        } catch (DataAccessException | IllegalArgumentException e) {
            throw redisUnavailable("Failed to create edge attribute synchronization state", e);
        }
    }

    @Override
    public Optional<EdgeAttributeSyncState> get(TenantId tenantId, UUID requestId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(stateKey(tenantId, requestId));
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toState(values));
        } catch (DataAccessException | IllegalArgumentException e) {
            throw redisUnavailable("Failed to read edge attribute synchronization state", e);
        }
    }

    @Override
    public void markSuccess(TenantId tenantId, UUID requestId, long now) {
        Long result = executeTerminalScript(SUCCESS_SCRIPT, tenantId, requestId, Long.toString(now));
        if (Long.valueOf(1L).equals(result)) {
            log.info("[{}][{}] Edge attribute synchronization state changed to SUCCESS", tenantId, requestId);
        } else {
            log.debug("[{}][{}] Ignored successful Edge ACK because state is missing or terminal, result [{}]",
                    tenantId, requestId, result);
        }
    }

    @Override
    public void recordRetryFailure(TenantId tenantId, UUID requestId, String error) {
        try {
            Long result = redisTemplate.execute(RETRY_FAILURE_SCRIPT,
                    List.of(stateKey(tenantId, requestId)), safeError(error));
            log.debug("[{}][{}] Recorded Edge attribute synchronization retry failure, result [{}]",
                    tenantId, requestId, result);
        } catch (DataAccessException e) {
            throw redisUnavailable("Failed to record Edge retry failure", e);
        }
    }

    @Override
    public void markFailed(TenantId tenantId, UUID requestId, long now, String errorCode, String error) {
        Long result = executeTerminalScript(FAILURE_SCRIPT, tenantId, requestId,
                Long.toString(now), errorCode, safeError(error));
        if (Long.valueOf(1L).equals(result)) {
            log.warn("[{}][{}] Edge attribute synchronization state changed to FAILED, errorCode [{}]",
                    tenantId, requestId, errorCode);
        } else {
            log.debug("[{}][{}] Ignored Edge attribute synchronization failure because state is missing or terminal, result [{}]",
                    tenantId, requestId, result);
        }
    }


    private Long executeTerminalScript(DefaultRedisScript<Long> script, TenantId tenantId, UUID requestId,
                                       String... args) {
        try {
            return redisTemplate.execute(script, List.of(stateKey(tenantId, requestId)), args);
        } catch (DataAccessException e) {
            throw redisUnavailable("Failed to update edge attribute synchronization state", e);
        }
    }

    private EdgeAttributeSyncState toState(Map<Object, Object> values) {
        return EdgeAttributeSyncState.builder()
                .requestId(UUID.fromString(value(values, "requestId")))
                .tenantId(UUID.fromString(value(values, "tenantId")))
                .edgeId(UUID.fromString(value(values, "edgeId")))
                .entityType(entityType(values))
                .entityId(entityId(values))
                .scope(AttributeScope.valueOf(value(values, "scope")))
                .status(EdgeAttributeSyncStatus.valueOf(value(values, "status")))
                .createdTime(longValue(values, "createdTime"))
                .expireAt(longValue(values, "expireAt"))
                .completedTime(longValue(values, "completedTime"))
                .retryCount((int) longValue(values, "retryCount"))
                .errorCode(value(values, "errorCode"))
                .lastError(value(values, "lastError"))
                .build();
    }

    private EntityType entityType(Map<Object, Object> values) {
        String entityType = value(values, "entityType");
        return entityType.isEmpty() ? EntityType.DEVICE : EntityType.valueOf(entityType);
    }

    private UUID entityId(Map<Object, Object> values) {
        return UUID.fromString(value(values, "entityId"));
    }

    private String stateKey(TenantId tenantId, UUID requestId) {
        return tenantPrefix(tenantId) + "state:" + requestId;
    }


    private String tenantPrefix(TenantId tenantId) {
        return KEY_PREFIX + "{" + tenantId + "}:";
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private long longValue(Map<Object, Object> values, String key) {
        String value = value(values, key);
        return value.isEmpty() ? 0L : Long.parseLong(value);
    }

    private String safeError(String error) {
        if (error == null) {
            return "";
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    private EdgeAttributeSyncException redisUnavailable(String message, Throwable cause) {
        log.warn(message, cause);
        return new EdgeAttributeSyncException(HttpStatus.SERVICE_UNAVAILABLE,
                "SYNC_STATE_UNAVAILABLE", message, cause);
    }

}
