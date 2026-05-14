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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleEngineDeviceRpcRequest;
import org.thingsboard.rule.engine.api.RuleEngineDeviceRpcResponse;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.exception.ToErrorResponseEntity;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.rpc.TbRuleEngineDeviceRpcService;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.thingsboard.server.controller.ControllerConstants.DEVICE_ID;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;

/**
 * ThingsBoard 直接 RPC 控制器
 *
 * 该控制器提供直接 RPC（远程过程调用）端点，允许服务端应用直接向设备发送 RPC 请求，
 * 绕过属性保存和规则链处理，适用于需要即时设备通信的场景。
 *
 * <h2>主要特性：</h2>
 * <ul>
 *   <li>直接与设备通信 - 绕过规则引擎，响应更快</li>
 *   <li>支持单向和双向 RPC 调用</li>
 *   <li>可配置的超时和重试机制</li>
 *   <li>基于 JWT 的认证和授权</li>
 * </ul>
 *
 * <h2>请求流程：</h2>
 * <ol>
 *   <li>客户端发送 POST 请求，包含设备 ID 和 RPC 请求体</li>
 *   <li>AccessValidator 验证用户对设备是否有 RPC_CALL 权限</li>
 *   <li>通过规则引擎 RPC 服务将请求转发到设备</li>
 *   <li>设备处理请求并返回响应</li>
 *   <li>响应异步返回给客户端</li>
 * </ol>
 *
 * <h2>授权说明：</h2>
 * <ul>
 *   <li>SYS_ADMIN: 可向任何设备发送 RPC（不推荐）</li>
 *   <li>TENANT_ADMIN: 可向其租户内的任何设备发送 RPC</li>
 *   <li>CUSTOMER_USER: 只能向分配给其客户的设备发送 RPC</li>
 * </ul>
 *
 * @see AbstractRpcController 基础 RPC 处理逻辑
 * @see TbRuleEngineDeviceRpcService RPC 服务实现
 */
@RestController
@TbCoreComponent
@RequestMapping(TbUrlConstants.RPC_V2_URL_PREFIX + "/direct")
@Slf4j
public class DirectRpcController extends BaseController {

    /**
     * RPC 请求的最小超时时间（毫秒）。
     * 当请求的超时时间小于此值时使用此值。
     */
    @Value("${server.rest.server_side_rpc.min_timeout:5000}")
    private long minRpcTimeout;

    /**
     * RPC 请求的默认超时时间（毫秒）。
     * 当请求中未指定超时时使用此值。
     */
    @Value("${server.rest.server_side_rpc.default_timeout:10000}")
    private long defaultRpcTimeout;

    /**
     * 通过规则引擎向设备发送 RPC 请求的服务。
     */
    @Autowired
    private TbRuleEngineDeviceRpcService tbRuleEngineDeviceRpcService;

    /**
     * 验证用户访问权限的服务。
     */
    @Autowired
    private AccessValidator accessValidator;

    /**
     * 向设备发送直接 RPC 请求。
     *
     * <p>该端点允许直接向设备发送 RPC 请求，绕过属性保存和规则链处理。
     * 适用于需要即时设备通信的场景。</p>
     *
     * <h3>请求体格式：</h3>
     * <pre>
     * {
     *   "method": "setGpio",      // 必填：RPC 方法名
     *   "params": {...},           // 可选：方法参数（JSON 对象或原始类型）
     *   "oneway": false,           // 可选：true 表示不等响应（默认: false）
     *   "timeout": 5000,           // 可选：响应超时时间（毫秒）
     *   "expirationTime": 1234567890, // 可选：绝对过期时间戳
     *   "persistent": true,        // 可选：设备离线时持久化请求
     *   "retries": 3,             // 可选：重试次数
     *   "requestUUID": "uuid",    // 可选：自定义请求 UUID（幂等性）
     *   "additionalInfo": {...}   // 可选：附加元数据
     * }
     * </pre>
     *
     * <h3>响应说明：</h3>
     * <ul>
     *   <li>200 OK: RPC 请求发送成功或设备已响应</li>
     *   <li>400 Bad Request: 请求体无效（缺少 method、UUID 格式错误等）</li>
     *   <li>401 Unauthorized: 用户未认证</li>
     *   <li>504 Gateway Timeout: 设备在超时时间内未响应</li>
     * </ul>
     *
     * @param deviceIdStr 目标设备的唯一标识符
     * @param request JSON RPC 请求体
     * @return 包含 RPC 响应或错误状态的 DeferredResult
     * @throws ThingsboardException 如果输入验证失败
     */
    @ApiOperation(value = "发送直接 RPC 请求",
            notes = "直接向设备发送 RPC 请求，绕过属性保存和规则链。"
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "RPC 请求发送成功。"),
            @ApiResponse(responseCode = "400", description = "请求体格式无效。"),
            @ApiResponse(responseCode = "401", description = "用户无权发送 RPC 请求。"),
            @ApiResponse(responseCode = "504", description = "RPC 调用超时，设备可能离线。")
    })
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public DeferredResult<ResponseEntity> sendDirectRpc(
            @Parameter(description = DEVICE_ID_PARAM_DESCRIPTION, required = true)
            @PathVariable(DEVICE_ID) String deviceIdStr,
            @Parameter(description = "JSON 格式的 RPC 请求体。")
            @RequestBody JsonNode request) throws ThingsboardException {
        return sendDeviceRpcDirectly(new DeviceId(toUUID(deviceIdStr)), request);
    }

    /**
     * 验证 RPC 请求体的必填字段和数据类型。
     *
     * <p>该方法对 RPC 请求体执行全面验证，包括：</p>
     * <ul>
     *   <li>必填字段存在性（method 必须存在且非 null）</li>
     *   <li>可选字段的数据类型验证</li>
     *   <li>业务逻辑验证（如超时值）</li>
     * </ul>
     *
     * @param rpcRequestBody 表示 RPC 请求体的 JSON 节点
     * @throws IllegalArgumentException 验证失败时抛出，带描述性错误消息
     */
    private void validateRpcRequestBody(JsonNode rpcRequestBody) {
        // 验证必填字段：method
        if (!rpcRequestBody.has("method") || rpcRequestBody.get("method").isNull()) {
            throw new IllegalArgumentException("RPC 请求体必须包含非空的 'method' 字段");
        }

        // 验证 method 是文本值
        JsonNode methodNode = rpcRequestBody.get("method");
        if (!methodNode.isTextual() || methodNode.asText().isEmpty()) {
            throw new IllegalArgumentException("'method' 字段必须是非空字符串");
        }

        // 验证可选字段 timeout（如果存在）
        if (rpcRequestBody.has(DataConstants.TIMEOUT) && !rpcRequestBody.get(DataConstants.TIMEOUT).isNull()) {
            long timeout = rpcRequestBody.get(DataConstants.TIMEOUT).asLong();
            if (timeout < 0) {
                throw new IllegalArgumentException("'timeout' 字段必须非负");
            }
        }

        // 验证可选字段 retries（如果存在）
        if (rpcRequestBody.has(DataConstants.RETRIES) && !rpcRequestBody.get(DataConstants.RETRIES).isNull()) {
            int retries = rpcRequestBody.get(DataConstants.RETRIES).asInt();
            if (retries < 0) {
                throw new IllegalArgumentException("'retries' 字段必须非负");
            }
        }

        // 验证可选字段 expirationTime（如果存在）
        if (rpcRequestBody.has(DataConstants.EXPIRATION_TIME) && !rpcRequestBody.get(DataConstants.EXPIRATION_TIME).isNull()) {
            long expirationTime = rpcRequestBody.get(DataConstants.EXPIRATION_TIME).asLong();
            if (expirationTime < 0) {
                throw new IllegalArgumentException("'expirationTime' 字段必须非负");
            }
        }

        // 验证可选字段 oneway（如果存在）
        if (rpcRequestBody.has("oneway") && !rpcRequestBody.get("oneway").isNull() && !rpcRequestBody.get("oneway").isBoolean()) {
            throw new IllegalArgumentException("'oneway' 字段必须是布尔值");
        }

        // 验证可选字段 persistent（如果存在）
        if (rpcRequestBody.has(DataConstants.PERSISTENT) && !rpcRequestBody.get(DataConstants.PERSISTENT).isNull() && !rpcRequestBody.get(DataConstants.PERSISTENT).isBoolean()) {
            throw new IllegalArgumentException("'persistent' 字段必须是布尔值");
        }

        // 验证可选字段 requestUUID（如果存在）
        if (rpcRequestBody.hasNonNull("requestUUID")) {
            try {
                UUID.fromString(rpcRequestBody.get("requestUUID").asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'requestUUID' 字段必须是有效的 UUID 格式");
            }
        }
    }

    /**
     * 在访问验证后处理直接 RPC 请求。
     *
     * <p>当 AccessValidator 确认用户有权限向指定设备发送 RPC 调用后，
     * 调用此方法。该方法构建 RPC 请求并通过规则引擎 RPC 服务发送到目标设备。</p>
     *
     * @param deviceId 目标设备 ID
     * @param rpcRequestBody 要验证和处理的 RPC 请求体
     * @return 将使用 RPC 响应或错误完成的 DeferredResult
     * @throws ThingsboardException 如果请求体验证失败
     */
    private DeferredResult<ResponseEntity> sendDeviceRpcDirectly(DeviceId deviceId, JsonNode rpcRequestBody) throws ThingsboardException {
        // 在处理前验证请求体
        // 如果验证失败会抛出 IllegalArgumentException，由外层处理器捕获
        validateRpcRequestBody(rpcRequestBody);

        SecurityUser currentUser = getCurrentUser();
        DeferredResult<ResponseEntity> response = new DeferredResult<>();

        accessValidator.validate(currentUser, Operation.RPC_CALL, deviceId, new HttpValidationCallback(response, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable DeferredResult<ResponseEntity> result) {
                try {
                    tbRuleEngineDeviceRpcService.sendRpcRequestToDevice(
                            buildDirectRpcRequest(currentUser.getTenantId(), deviceId, rpcRequestBody),
                            rpcResponse -> handleDirectRpcResponse(result, rpcResponse));
                } catch (IllegalArgumentException e) {
                    // 处理请求构建过程中可能发生的验证错误
                    AccessValidator.handleError(e, result, HttpStatus.BAD_REQUEST);
                }
            }

            @Override
            public void onFailure(Throwable e) {
                ResponseEntity entity;
                if (e instanceof ToErrorResponseEntity) {
                    entity = ((ToErrorResponseEntity) e).toErrorResponseEntity();
                } else {
                    entity = new ResponseEntity(HttpStatus.UNAUTHORIZED);
                }
                response.setResult(entity);
            }
        }));
        return response;
    }

    /**
     * 从 JSON 请求体构建 RuleEngineDeviceRpcRequest。
     *
     * <p>该方法构造将发送到规则引擎的请求对象，以传递给目标设备。
     * 它从 JSON 体中提取所有相关参数，并在未指定时应用默认值。</p>
     *
     * @param tenantId 请求用户的租户 ID
     * @param deviceId 目标设备 ID
     * @param rpcRequestBody JSON RPC 请求体
     * @return 完全填充的 RuleEngineDeviceRpcRequest
     */
    private RuleEngineDeviceRpcRequest buildDirectRpcRequest(TenantId tenantId, DeviceId deviceId, JsonNode rpcRequestBody) {
        // 计算超时时间：使用提供的值或默认值
        // 如果提供的超时时间小于 minRpcTimeout，则使用 minRpcTimeout
        long timeout = rpcRequestBody.has(DataConstants.TIMEOUT) && !rpcRequestBody.get(DataConstants.TIMEOUT).isNull()
                ? rpcRequestBody.get(DataConstants.TIMEOUT).asLong()
                : defaultRpcTimeout;

        // 计算过期时间：使用提供的值或从当前时间 + 超时计算
        // 如果未指定，实际过期时间为从现在起的 max(minRpcTimeout, timeout)
        long expirationTime = rpcRequestBody.has(DataConstants.EXPIRATION_TIME) && !rpcRequestBody.get(DataConstants.EXPIRATION_TIME).isNull()
                ? rpcRequestBody.get(DataConstants.EXPIRATION_TIME).asLong()
                : System.currentTimeMillis() + Math.max(minRpcTimeout, timeout);

        // 使用提供的 requestUUID 或生成随机 UUID 以实现幂等性
        UUID requestUUID = rpcRequestBody.hasNonNull("requestUUID")
                ? UUID.fromString(rpcRequestBody.get("requestUUID").asText())
                : UUID.randomUUID();

        // 解析 oneway 标志：默认为 false（双向 RPC）
        // 如果字段缺失，假定为双向；如存在，使用布尔值
        boolean oneway = rpcRequestBody.has("oneway") && !rpcRequestBody.get("oneway").isNull()
                && rpcRequestBody.get("oneway").asBoolean();

        // 解析 persistent 标志：是否在设备离线时持久化请求以供后续投递
        boolean persisted = rpcRequestBody.has(DataConstants.PERSISTENT)
                && !rpcRequestBody.get(DataConstants.PERSISTENT).isNull()
                && rpcRequestBody.get(DataConstants.PERSISTENT).asBoolean();

        // 解析方法名（已验证为非空）
        String method = rpcRequestBody.get("method").asText();

        // 解析 params（可选，可以是任意 JSON 值包括 null）
        String params = parseRpcJsonData(rpcRequestBody.get("params"));

        // 解析附加信息（可选的元数据）
        String additionalInfo = parseRpcJsonData(rpcRequestBody.get(DataConstants.ADDITIONAL_INFO));

        // 解析 retries（可选，null 表示使用系统默认值）
        Integer retries = null;
        if (rpcRequestBody.has(DataConstants.RETRIES) && !rpcRequestBody.get(DataConstants.RETRIES).isNull()) {
            retries = rpcRequestBody.get(DataConstants.RETRIES).asInt();
        }

        return RuleEngineDeviceRpcRequest.builder()
                .tenantId(tenantId)
                .deviceId(deviceId)
                .requestId(ThreadLocalRandom.current().nextInt())
                .requestUUID(requestUUID)
                .originServiceId(null)
                .oneway(oneway)
                .persisted(persisted)
                .method(method)
                .body(params)
                .expirationTime(expirationTime)
                .restApiCall(false)
                .additionalInfo(additionalInfo)
                .retries(retries)
                .build();
    }

    /**
     * 处理来自设备的 RPC 响应。
     *
     * <p>该方法处理从设备接收的响应，并将其映射到适当的 HTTP 响应。
     * 它处理成功响应和各种错误情况。</p>
     *
     * <h3>响应映射：</h3>
     * <ul>
     *   <li>TIMEOUT/NO_ACTIVE_CONNECTION -> 504 Gateway Timeout</li>
     *   <li>其他错误 -> 500 Internal Server Error</li>
     *   <li>带 JSON 响应的成功 -> 200 OK 和解析后的 JSON</li>
     *   <li>带非 JSON 响应的成功 -> 200 OK 和原始字符串</li>
     *   <li>无响应体的成功 -> 200 OK（空）</li>
     * </ul>
     *
     * @param response 要完成的 DeferredResult
     * @param rpcResponse 来自设备的 RPC 响应
     */
    private void handleDirectRpcResponse(DeferredResult<ResponseEntity> response, RuleEngineDeviceRpcResponse rpcResponse) {
        // 检查 RPC 级错误
        if (rpcResponse.getError().isPresent()) {
            switch (rpcResponse.getError().get()) {
                case TIMEOUT, NO_ACTIVE_CONNECTION -> response.setResult(new ResponseEntity<>(HttpStatus.GATEWAY_TIMEOUT));
                default -> response.setResult(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
            }
            return;
        }

        // 处理成功响应
        if (rpcResponse.getResponse().isPresent() && !StringUtils.isEmpty(rpcResponse.getResponse().get())) {
            String data = rpcResponse.getResponse().get();
            try {
                // 尝试将响应解析为 JSON 以便正确返回
                response.setResult(new ResponseEntity<>(JacksonUtil.toJsonNode(data), HttpStatus.OK));
            } catch (IllegalArgumentException e) {
                // 如果响应不是有效 JSON，则返回原始字符串
                log.debug("无法解析直接 RPC 响应: {}", data, e);
                response.setResult(new ResponseEntity<>(data, HttpStatus.OK));
            }
        } else {
            // 单向 RPC 或设备返回空响应
            response.setResult(new ResponseEntity<>(HttpStatus.OK));
        }
    }

    /**
     * 解析 RPC JSON 数据字段为字符串表示。
     *
     * <p>该方法处理 RPC 请求的 params 字段，可以是：</p>
     * <ul>
     *   <li>null 或 JSON null -> 返回 null</li>
     *   <li>原始值（字符串、数字、布尔）-> 作为文本返回</li>
     *   <li>复杂对象/数组 -> 返回 JSON 字符串表示</li>
     * </ul>
     *
     * @param value 要解析的 JSON 节点
     * @return 值的字符串表示，如果为 null/缺失则返回 null
     */
    private String parseRpcJsonData(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : JacksonUtil.toString(value);
    }
}
