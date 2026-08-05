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

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonParser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.adaptor.JsonConverter;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.edge.EdgeEvent;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.edge.EdgeEventType;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncResponse;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncState;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncStatus;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EdgeEventId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.dao.edge.EdgeEventService;
import org.thingsboard.server.dao.edge.EdgeService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultEdgeAttributeSyncService implements EdgeAttributeSyncService {

    private static final String QUERY_URL_PREFIX = "/api/plugins/telemetry/attribute-sync/";

    private final TelemetrySubscriptionService telemetrySubscriptionService;
    private final EdgeEventService edgeEventService;
    private final EdgeService edgeService;
    private final EdgeAttributeSyncStateService stateService;
    private final EdgeAttributeSyncSettings settings;

    @Override
    public ListenableFuture<EdgeAttributeSyncResponse> submit(TenantId tenantId, EntityId entityId, AttributeScope scope,
                                                              Long resultTtlSeconds, JsonNode attributesNode) {
        validateRequest(tenantId, entityId, scope, attributesNode);
        EdgeId edgeId = resolveAssignedEdge(tenantId, entityId);

        long now = System.currentTimeMillis();
        long effectiveResultTtlSeconds = resolveResultTtlSeconds(resultTtlSeconds);
        UUID requestId = UUID.randomUUID();
        List<AttributeKvEntry> attributes = convertAttributes(attributesNode, now);

        EdgeAttributeSyncState initialState = EdgeAttributeSyncState.builder()
                .requestId(requestId)
                .tenantId(tenantId.getId())
                .edgeId(edgeId.getId())
                .entityType(entityId.getEntityType())
                .entityId(entityId.getId())
                .scope(scope)
                .status(EdgeAttributeSyncStatus.PENDING)
                .createdTime(now)
                .expireAt(now + effectiveResultTtlSeconds * 1000L)
                .build();

        stateService.createPending(tenantId, initialState, effectiveResultTtlSeconds);
        log.info("[{}][{}][{}][{}] Created Edge attribute synchronization request, scope [{}], resultTtlSeconds [{}], expireAt [{}]",
                tenantId, edgeId, entityId, requestId, scope, effectiveResultTtlSeconds, initialState.getExpireAt());

        SettableFuture<EdgeAttributeSyncResponse> result = SettableFuture.create();
        try {
            telemetrySubscriptionService.saveAttributes(AttributesSaveRequest.builder()
                    .tenantId(tenantId)
                    .entityId(entityId)
                    .scope(scope)
                    .entries(attributes)
                    .callback(new FutureCallback<>() {
                        @Override
                        public void onSuccess(@Nullable Void ignored) {
                            log.info("[{}][{}][{}][{}] Cloud attributes saved; creating EdgeEvent",
                                    tenantId, edgeId, entityId, initialState.getRequestId());
                            saveEdgeEvent(tenantId, edgeId, entityId, scope, attributesNode, initialState, result);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            failSubmission(tenantId, initialState, result, "CLOUD_ATTRIBUTE_SAVE_FAILED",
                                    "Failed to save attributes in Cloud", t);
                        }
                    })
                    .build());
        } catch (RuntimeException e) {
            failSubmission(tenantId, initialState, result, "CLOUD_ATTRIBUTE_SAVE_FAILED",
                    "Failed to save attributes in Cloud", e);
        }
        return result;
    }

    @Override
    public EdgeAttributeSyncResponse getResult(TenantId tenantId, UUID requestId) {
        EdgeAttributeSyncState state = stateService.get(tenantId, requestId)
                .orElseThrow(() -> new EdgeAttributeSyncException(HttpStatus.GONE, "SYNC_RESULT_EXPIRED",
                        "Edge attribute synchronization result does not exist or has expired"));
        log.debug("[{}][{}] Read Edge attribute synchronization state [{}]",
                tenantId, requestId, state.getStatus());
        return toResponse(state);
    }

    private void saveEdgeEvent(TenantId tenantId, EdgeId edgeId, EntityId entityId,
                               AttributeScope scope, JsonNode attributesNode, EdgeAttributeSyncState state,
                               SettableFuture<EdgeAttributeSyncResponse> result) {
        UUID eventUuid = Uuids.timeBased();
        EdgeEventId edgeEventId = new EdgeEventId(eventUuid);
        EdgeEvent edgeEvent = new EdgeEvent(edgeEventId);
        edgeEvent.setCreatedTime(Uuids.unixTimestamp(eventUuid));
        edgeEvent.setTenantId(tenantId);
        edgeEvent.setEdgeId(edgeId);
        edgeEvent.setEntityId(entityId.getId());
        edgeEvent.setType(EdgeEventType.valueOf(entityId.getEntityType().name()));
        edgeEvent.setAction(EdgeEventActionType.ATTRIBUTES_UPDATED);
        edgeEvent.setUid(EdgeAttributeSyncUidUtils.build(settings.getUidPrefix(), state.getRequestId()));
        edgeEvent.setBody(buildEdgeEventBody(scope, attributesNode, state.getCreatedTime()));

        ListenableFuture<Void> saveFuture;
        try {
            saveFuture = edgeEventService.saveAsync(edgeEvent);
        } catch (RuntimeException e) {
            failSubmission(tenantId, state, result, "EDGE_EVENT_SAVE_FAILED",
                    "Failed to save EdgeEvent", e);
            return;
        }
        Futures.addCallback(saveFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable Void ignored) {
                log.info("[{}][{}][{}][{}][{}] EdgeEvent saved and queued for Edge delivery",
                        tenantId, edgeId, entityId, state.getRequestId(), edgeEventId);
                result.set(toResponse(state));
            }

            @Override
            public void onFailure(Throwable t) {
                failSubmission(tenantId, state, result, "EDGE_EVENT_SAVE_FAILED",
                        "Failed to save EdgeEvent", t);
            }
        }, Runnable::run);
    }

    private void validateRequest(TenantId tenantId, EntityId entityId, AttributeScope scope,
                                 JsonNode attributesNode) {
        if (tenantId == null || tenantId.isNullUid() || entityId == null) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Tenant and entity must be specified");
        }
        if (entityId.getEntityType() != EntityType.DEVICE && entityId.getEntityType() != EntityType.ASSET) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ENTITY_TYPE",
                    "Edge attribute synchronization only supports DEVICE and ASSET entities");
        }
        if (attributesNode == null || !attributesNode.isObject() || attributesNode.isEmpty()) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_ATTRIBUTES",
                    "Attributes must be a non-empty JSON object");
        }
        if (scope != AttributeScope.SERVER_SCOPE && scope != AttributeScope.SHARED_SCOPE) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE",
                    "Only SERVER_SCOPE and SHARED_SCOPE are supported");
        }
        attributesNode.fieldNames().forEachRemaining(key -> {
            if (StringUtils.isEmpty(key) || key.trim().isEmpty()) {
                throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_ATTRIBUTE_KEY",
                        "Attribute key cannot be empty");
            }
        });
    }

    private long resolveResultTtlSeconds(Long requestedResultTtlSeconds) {
        long resultTtlSeconds = requestedResultTtlSeconds == null
                ? settings.getDefaultResultTtlSeconds() : requestedResultTtlSeconds;
        if (resultTtlSeconds < settings.getMinResultTtlSeconds()
                || resultTtlSeconds > settings.getMaxResultTtlSeconds()) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_RESULT_TTL",
                    "resultTtlSeconds must be between " + settings.getMinResultTtlSeconds()
                            + " and " + settings.getMaxResultTtlSeconds() + " seconds");
        }
        return resultTtlSeconds;
    }

    private EdgeId resolveAssignedEdge(TenantId tenantId, EntityId entityId) {
        List<EdgeId> edgeIds = edgeService.findAllRelatedEdgeIds(tenantId, entityId);
        if (edgeIds == null || edgeIds.isEmpty()) {
            throw new EdgeAttributeSyncException(HttpStatus.NOT_FOUND, "ENTITY_NOT_ASSIGNED_TO_EDGE",
                    "Entity is not assigned to an Edge");
        }
        if (edgeIds.size() > 1) {
            throw new EdgeAttributeSyncException(HttpStatus.CONFLICT, "MULTIPLE_EDGES_ASSIGNED",
                    "Entity is assigned to multiple Edges; exactly one Edge is required");
        }
        return edgeIds.get(0);
    }

    private List<AttributeKvEntry> convertAttributes(JsonNode attributesNode, long timestamp) {
        try {
            Set<AttributeKvEntry> converted = JsonConverter.convertToAttributes(
                    JsonParser.parseString(JacksonUtil.toString(attributesNode)), timestamp);
            if (converted.isEmpty()) {
                throw new IllegalArgumentException("No supported attribute values were found");
            }
            return new ArrayList<>(converted);
        } catch (RuntimeException e) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_ATTRIBUTES",
                    "Failed to parse attributes: " + e.getMessage(), e);
        }
    }


    private ObjectNode buildEdgeEventBody(AttributeScope scope, JsonNode attributesNode, long timestamp) {
        ObjectNode body = JacksonUtil.newObjectNode();
        body.set("kv", attributesNode.deepCopy());
        body.put("ts", timestamp);
        body.put(DataConstants.SCOPE, scope.name());
        return body;
    }

    private void failSubmission(TenantId tenantId, EdgeAttributeSyncState state,
                                SettableFuture<EdgeAttributeSyncResponse> result, String errorCode,
                                String message, Throwable cause) {
        log.warn("[{}][{}][{}] {}", tenantId, state.getEdgeId(), state.getRequestId(), message, cause);
        try {
            stateService.markFailed(tenantId, state.getRequestId(), System.currentTimeMillis(),
                    errorCode, cause != null && cause.getMessage() != null ? cause.getMessage() : message);
        } catch (RuntimeException stateError) {
            log.warn("[{}][{}] Failed to update synchronization failure state", tenantId,
                    state.getRequestId(), stateError);
        }
        result.setException(new EdgeAttributeSyncException(HttpStatus.INTERNAL_SERVER_ERROR,
                errorCode, message, cause));
    }

    private EdgeAttributeSyncResponse toResponse(EdgeAttributeSyncState state) {
        return EdgeAttributeSyncResponse.builder()
                .requestId(state.getRequestId())
                .status(state.getStatus())
                .edgeId(state.getEdgeId())
                .entityType(state.getEntityType())
                .entityId(state.getEntityId())
                .scope(state.getScope())
                .createdTime(state.getCreatedTime())
                .expireAt(state.getExpireAt())
                .recommendedPollIntervalMs(settings.getRecommendedPollIntervalMs())
                .completedTime(state.getCompletedTime())
                .retryCount(state.getRetryCount())
                .errorCode(state.getErrorCode())
                .message(state.getLastError())
                .queryUrl(QUERY_URL_PREFIX + state.getRequestId())
                .build();
    }

}
