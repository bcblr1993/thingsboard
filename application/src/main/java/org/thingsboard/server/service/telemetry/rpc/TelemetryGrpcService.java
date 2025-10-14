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
package org.thingsboard.server.service.telemetry.rpc;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.gen.edge.v1.*;
import org.thingsboard.server.queue.util.AfterStartUp;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.edge.EdgeContextComponent;
import org.thingsboard.server.service.edge.rpc.processor.telemetry.TelemetryEdgeProcessor;
import org.thingsboard.server.service.telemetry.rpc.session.TelemetryGrpcSession;
import org.thingsboard.server.common.data.ResourceUtils;

import javax.annotation.Nonnull;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "telemetry_grpc", value = "enabled", havingValue = "true")
@TbCoreComponent
public class TelemetryGrpcService extends EdgeRpcServiceGrpc.EdgeRpcServiceImplBase {

    // ... [所有 @Value 注解的属性保持不变] ...
    @Value("${telemetry_grpc.port}")
    private int port;
    @Value("${telemetry_grpc.max_connections}")
    private int maxConnections;
    @Value("${telemetry_grpc.ssl.enabled}")
    private boolean sslEnabled;
    @Value("${telemetry_grpc.ssl.cert}")
    private String certFileResource;
    @Value("${telemetry_grpc.ssl.private_key}")
    private String privateKeyResource;
    @Value("${telemetry_grpc.keep_alive.time_sec}")
    private int keepAliveTimeSec;
    @Value("${telemetry_grpc.keep_alive.timeout_sec}")
    private int keepAliveTimeoutSec;
    @Value("${telemetry_grpc.keep_alive.client_max_keep_alive_time_sec}")
    private int clientMaxKeepAliveTimeSec;
    @Value("${telemetry_grpc.max_inbound_message_size}")
    private int maxInboundMessageSize;

    // 新增：会话超时和清理周期配置
    @Value("${telemetry_grpc.session.timeout_ms:600000}") // 默认10分钟
    private long sessionTimeoutMs;

    @Value("${telemetry_grpc.session.check_interval_ms:60000}") // 默认1分钟
    private long sessionCheckIntervalMs;


    private final ConcurrentMap<String, TelemetryGrpcSession> sessions = new ConcurrentHashMap<>();
    private Semaphore connectionSemaphore;

    @Autowired @Lazy private TelemetryEdgeProcessor telemetryProcessor;
    @Autowired @Lazy private EdgeContextComponent ctx;

    private Server server;

    @PostConstruct
    public void init() {
        this.connectionSemaphore = new Semaphore(maxConnections);
        log.info("Initialized Telemetry gRPC service with max {} connections.", maxConnections);
    }

    @AfterStartUp(order = AfterStartUp.REGULAR_SERVICE)
    public void onStartUp() {
        log.info("Initializing Telemetry gRPC service on port: {}", port);
        try {
            NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                    .permitKeepAliveTime(clientMaxKeepAliveTimeSec, TimeUnit.SECONDS)
                    .keepAliveTime(keepAliveTimeSec, TimeUnit.SECONDS)
                    .keepAliveTimeout(keepAliveTimeoutSec, TimeUnit.SECONDS)
                    .permitKeepAliveWithoutCalls(true)
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .addService(this);

            if (sslEnabled) {
                InputStream certFileIs = ResourceUtils.getInputStream(this, certFileResource);
                InputStream privateKeyFileIs = ResourceUtils.getInputStream(this, privateKeyResource);
                builder.useTransportSecurity(certFileIs, privateKeyFileIs);
            }

            server = builder.build();
            server.start();
            log.info("Telemetry gRPC server started successfully on port: {}", port);
        } catch (Exception e) {
            log.error("Failed to start Telemetry gRPC server!", e);
            throw new RuntimeException("Failed to start Telemetry gRPC server!", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (server != null) {
            log.info("Stopping Telemetry gRPC server...");
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
            }
            log.info("Telemetry gRPC server stopped.");
        }
    }

    /**
     * 定时任务，用于清理不活跃的僵尸连接
     */
    @Scheduled(fixedDelayString = "${telemetry_grpc.session.check_interval_ms:60000}")
    public void cleanupExpiredSessions() {
        if (sessions.isEmpty()) {
            return;
        }
        log.trace("Running periodic check for expired gRPC sessions...");
        List<String> expiredSessionIds = sessions.values().stream()
                .filter(session -> session.isExpired(sessionTimeoutMs))
                .map(TelemetryGrpcSession::getSessionId)
                .collect(Collectors.toList());

        if (!expiredSessionIds.isEmpty()) {
            log.info("Found {} expired sessions due to inactivity: {}", expiredSessionIds.size(), expiredSessionIds);
            expiredSessionIds.forEach(this::cleanupSession);
        }
    }

    @Override
    public StreamObserver<RequestMsg> handleMsgs(StreamObserver<ResponseMsg> outputStream) {
        if (!connectionSemaphore.tryAcquire()) {
            log.warn("Maximum connections ({}) reached. Rejecting new connection.", maxConnections);
            // 使用 gRPC 标准状态码来拒绝连接
            outputStream.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Maximum number of connections reached")
                    .asRuntimeException());
            return new NoOpRequestObserver();
        }

        String sessionId = UUID.randomUUID().toString();
        TelemetryGrpcSession session = new TelemetryGrpcSession(sessionId, outputStream, this::onSessionClose);

        return new StreamObserver<>() {
            private boolean authenticated = false;

            @Override
            public void onNext(RequestMsg requestMsg) {
                try {
                    session.updateLastActivity();

                    if (!authenticated) {
                        if (requestMsg.getMsgType() == RequestMsgType.CONNECT_RPC_MESSAGE && requestMsg.hasConnectRequestMsg()) {
                            authenticated = processConnectRequest(session, requestMsg.getConnectRequestMsg());
                            if (authenticated) {
                                // 认证成功后，才将 session 放入 map
                                sessions.put(sessionId, session);
                                log.info("[{}] New telemetry client authenticated and connected. Active sessions: {}", sessionId, sessions.size());
                            } else {
                                // 认证失败，直接关闭连接并释放信号量
                                log.warn("[{}] Client authentication failed. Closing stream.", sessionId);
                                // 使用 UNAUTHENTICATED 状态码通知客户端
                                outputStream.onError(Status.UNAUTHENTICATED
                                        .withDescription("Invalid credentials")
                                        .asRuntimeException());
                                connectionSemaphore.release();
                            }
                        } else {
                            log.warn("[{}] First message was not a connect request. Closing stream.", sessionId);
                            outputStream.onError(Status.INVALID_ARGUMENT
                                    .withDescription("Connection must be initiated with a ConnectRequestMsg")
                                    .asRuntimeException());
                            connectionSemaphore.release();
                        }
                    } else {
                        // 已认证，处理其他消息
                        if (requestMsg.getMsgType() == RequestMsgType.UPLINK_RPC_MESSAGE && requestMsg.hasUplinkMsg()) {
                            processUplinkMsg(session, requestMsg.getUplinkMsg());
                        }
                    }
                } catch (Exception e) {
                    log.error("[{}] Error processing request message.", sessionId, e);
                    outputStream.onError(Status.INTERNAL
                            .withDescription("Error processing message: " + e.getMessage())
                            .asRuntimeException());
                    cleanupSession(sessionId);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("[{}] Telemetry stream error: {}", sessionId, t.getMessage());
                cleanupSession(sessionId);
            }

            @Override
            public void onCompleted() {
                log.info("[{}] Telemetry stream completed by client.", sessionId);
                cleanupSession(sessionId);
            }
        };
    }

    private boolean processConnectRequest(TelemetryGrpcSession session, ConnectRequestMsg request) {
        boolean authResult;
        String edgeRoutingKey = request.getEdgeRoutingKey();
        String edgeSecret = request.getEdgeSecret();

        Optional<Edge> edgeOptional = ctx.getEdgeService().findEdgeByRoutingKey(TenantId.SYS_TENANT_ID, edgeRoutingKey);

        if (edgeOptional.isPresent() && edgeOptional.get().getSecret().equals(edgeSecret)) {
            authResult = true;
            session.setTenantId(edgeOptional.get().getTenantId());
            log.info("[{}] Client successfully authenticated for tenant [{}].", session.getSessionId(), session.getTenantId());
        } else {
            authResult = false;
        }

        ConnectResponseMsg response = ConnectResponseMsg.newBuilder()
                .setResponseCode(authResult ? ConnectResponseCode.ACCEPTED : ConnectResponseCode.BAD_CREDENTIALS)
                .setErrorMsg(authResult ? "" : "Invalid edge routing key or secret")
                .build();

        session.sendResponse(ResponseMsg.newBuilder().setConnectResponseMsg(response).build());
        return authResult;
    }

    private void processUplinkMsg(TelemetryGrpcSession session, UplinkMsg uplinkMsg) {
        if (uplinkMsg.getEntityDataCount() == 0) {
            sendUplinkResponse(session, uplinkMsg.getUplinkMsgId(), true, "");
            return;
        }

        log.trace("[{}] Processing {} entity data messages.", session.getSessionId(), uplinkMsg.getEntityDataCount());

        List<ListenableFuture<Void>> futures = uplinkMsg.getEntityDataList().stream()
                .flatMap(entityData -> {
                    try {
                        return telemetryProcessor.processTelemetryMsg(session.getTenantId(), entityData).stream();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        ListenableFuture<List<Void>> allFutures = Futures.allAsList(futures);
        Futures.addCallback(allFutures, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nonnull List<Void> result) {
                sendUplinkResponse(session, uplinkMsg.getUplinkMsgId(), true, "");
            }

            @Override
            public void onFailure(@Nonnull Throwable t) {
                log.error("[{}] Failed to process telemetry data.", session.getSessionId(), t);
                sendUplinkResponse(session, uplinkMsg.getUplinkMsgId(), false, t.getMessage());
            }
        }, ctx.getGrpcCallbackExecutorService());
    }

    private void sendUplinkResponse(TelemetryGrpcSession session, int uplinkMsgId, boolean success, String errorMsg) {
        UplinkResponseMsg response = UplinkResponseMsg.newBuilder()
                .setUplinkMsgId(uplinkMsgId)
                .setSuccess(success)
                .setErrorMsg(errorMsg != null ? errorMsg : "")
                .build();

        session.sendResponse(ResponseMsg.newBuilder().setUplinkResponseMsg(response).build());
    }

    private void onSessionClose(String sessionId) {
        cleanupSession(sessionId);
    }

    private void cleanupSession(String sessionId) {
        TelemetryGrpcSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            connectionSemaphore.release();
            log.info("[{}] Cleaned up session. Active sessions: {}. Available permits: {}",
                    sessionId, sessions.size(), connectionSemaphore.availablePermits());
        } else {
            // 可能因为认证失败等原因，session从未被加入map，但信号量需要被释放
            // 但在当前逻辑下，这种情况已经在onNext中处理，这里仅作日志记录
            log.trace("Session [{}] not found for cleanup, it might have failed authentication.", sessionId);
        }
    }

    /**
     * 一个不做任何事情的StreamObserver，用于在拒绝连接时返回。
     */
    private static class NoOpRequestObserver implements StreamObserver<RequestMsg> {
        @Override
        public void onNext(RequestMsg value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
