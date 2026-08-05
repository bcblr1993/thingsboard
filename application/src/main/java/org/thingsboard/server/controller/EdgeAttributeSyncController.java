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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.edge.attributes.EdgeAttributeSyncResponse;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.edge.attributes.EdgeAttributeSyncException;
import org.thingsboard.server.service.edge.attributes.EdgeAttributeSyncService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.thingsboard.server.controller.ControllerConstants.ATTRIBUTES_JSON_REQUEST_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ATTRIBUTES_SCOPE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ENTITY_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ENTITY_TYPE_PARAM_DESCRIPTION;

@RestController
@TbCoreComponent
@RequestMapping("/api/plugins/telemetry")
@Slf4j
public class EdgeAttributeSyncController extends BaseController {

    @Autowired
    private AccessValidator accessValidator;
    @Autowired
    private EdgeAttributeSyncService edgeAttributeSyncService;

    @ApiOperation(value = "Save entity attributes and synchronize them to Edge",
            notes = "Uses the same entityType, entityId, scope and JSON body as the standard telemetry attributes API. " +
                    "The entity's unique assigned Edge is resolved automatically. Supports DEVICE and ASSET. Returns a request ID for polling.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Synchronization request accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid entity type, scope or request"),
            @ApiResponse(responseCode = "404", description = "Entity has no assigned Edge"),
            @ApiResponse(responseCode = "409", description = "Entity has multiple assigned Edges"),
            @ApiResponse(responseCode = "503", description = "Redis synchronization state is unavailable")
    })
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping("/{entityType}/{entityId}/attributes/{scope}/edge-sync")
    public DeferredResult<ResponseEntity> submitAttributeSync(
            @Parameter(description = ENTITY_TYPE_PARAM_DESCRIPTION, required = true, schema = @Schema(defaultValue = "DEVICE")) @PathVariable("entityType") String entityType,
            @Parameter(description = ENTITY_ID_PARAM_DESCRIPTION, required = true) @PathVariable("entityId") String entityIdStr,
            @Parameter(description = ATTRIBUTES_SCOPE_DESCRIPTION, schema = @Schema(allowableValues = {"SERVER_SCOPE", "SHARED_SCOPE"}, requiredMode = Schema.RequiredMode.REQUIRED)) @PathVariable("scope") AttributeScope scope,
            @Parameter(description = "Redis synchronization result retention time in seconds",
                    schema = @Schema(defaultValue = "300", minimum = "120", maximum = "3600"))
            @RequestParam(name = "resultTtlSeconds", required = false) Long resultTtlSeconds,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = ATTRIBUTES_JSON_REQUEST_DESCRIPTION, required = true) @RequestBody JsonNode attributes) throws ThingsboardException {
        EntityId entityId = parseSupportedEntityId(entityType, entityIdStr);
        SecurityUser user = getCurrentUser();
        log.info("[{}][{}] Received Edge attribute synchronization request", user.getTenantId(), entityId);
        return accessValidator.validateEntityAndCallback(user, Operation.WRITE_ATTRIBUTES, entityId,
                (result, tenantId, ignored) -> {
                    try {
                        Futures.addCallback(edgeAttributeSyncService.submit(tenantId, entityId, scope,
                                resultTtlSeconds, attributes), new FutureCallback<>() {
                            @Override
                            public void onSuccess(@Nullable EdgeAttributeSyncResponse response) {
                                log.info("[{}][{}][{}] Edge attribute synchronization request accepted with status [{}]",
                                        tenantId, entityId, response.getRequestId(), response.getStatus());
                                result.setResult(new ResponseEntity<>(response, HttpStatus.ACCEPTED));
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                setErrorResult(result, t);
                            }
                        }, MoreExecutors.directExecutor());
                    } catch (RuntimeException e) {
                        setErrorResult(result, e);
                    }
                }, this::setErrorResult);
    }

    @ApiOperation(value = "Get Edge attribute synchronization result",
            notes = "Returns PENDING, SUCCESS or FAILED for a previously submitted request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Synchronization status returned"),
            @ApiResponse(responseCode = "410", description = "Synchronization result expired")
    })
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/attribute-sync/{requestId}")
    public DeferredResult<ResponseEntity> getAttributeSyncResult(
            @Parameter(required = true) @PathVariable("requestId") String requestIdStr) throws ThingsboardException {
        SecurityUser user = getCurrentUser();
        log.debug("[{}][{}] Querying Edge attribute synchronization result", user.getTenantId(), requestIdStr);
        EdgeAttributeSyncResponse syncResponse;
        try {
            syncResponse = edgeAttributeSyncService.getResult(user.getTenantId(), UUID.fromString(requestIdStr));
            log.debug("[{}][{}] Edge attribute synchronization status is [{}]",
                    user.getTenantId(), syncResponse.getRequestId(), syncResponse.getStatus());
        } catch (RuntimeException e) {
            DeferredResult<ResponseEntity> result = new DeferredResult<>();
            setErrorResult(result, e);
            return result;
        }
        EntityId entityId = EntityIdFactory.getByTypeAndUuid(syncResponse.getEntityType(), syncResponse.getEntityId());
        return accessValidator.validateEntityAndCallback(user, Operation.READ_ATTRIBUTES, entityId,
                (result, tenantId, ignored) -> result.setResult(new ResponseEntity<>(syncResponse, HttpStatus.OK)),
                this::setErrorResult);
    }

    private EntityId parseSupportedEntityId(String entityTypeStr, String entityIdStr) {
        try {
            EntityType entityType = EntityType.valueOf(entityTypeStr.toUpperCase(Locale.ROOT));
            if (entityType != EntityType.DEVICE && entityType != EntityType.ASSET) {
                throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ENTITY_TYPE",
                        "Edge attribute synchronization only supports DEVICE and ASSET entities");
            }
            return EntityIdFactory.getByTypeAndUuid(entityType, entityIdStr);
        } catch (EdgeAttributeSyncException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EdgeAttributeSyncException(HttpStatus.BAD_REQUEST, "INVALID_ENTITY_ID_OR_TYPE",
                    "Invalid entityType or entityId", e);
        }
    }

    private void setErrorResult(DeferredResult<ResponseEntity> result, Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && !(cause instanceof EdgeAttributeSyncException)) {
            cause = cause.getCause();
        }
        if (cause instanceof EdgeAttributeSyncException syncException) {
            log.warn("Edge attribute synchronization request failed: errorCode [{}], message [{}]",
                    syncException.getErrorCode(), syncException.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("errorCode", syncException.getErrorCode());
            body.put("message", syncException.getMessage());
            result.setResult(new ResponseEntity<>(body, syncException.getStatus()));
        } else if (cause instanceof IllegalArgumentException) {
            log.warn("Invalid Edge attribute synchronization request: {}", cause.getMessage());
            Map<String, Object> body = Map.of("success", false, "errorCode", "INVALID_REQUEST",
                    "message", cause.getMessage() == null ? "Invalid request" : cause.getMessage());
            result.setResult(new ResponseEntity<>(body, HttpStatus.BAD_REQUEST));
        } else {
            log.error("Unexpected Edge attribute synchronization error", cause);
            AccessValidator.handleError(cause, result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
