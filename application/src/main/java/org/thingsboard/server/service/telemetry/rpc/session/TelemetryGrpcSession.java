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
package org.thingsboard.server.service.telemetry.rpc.session;

import io.grpc.stub.StreamObserver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.gen.edge.v1.ResponseMsg;

import java.util.function.Consumer;

@Data
@Slf4j
public class TelemetryGrpcSession {
    private final String sessionId;
    private final StreamObserver<ResponseMsg> outputStream;
    private final Consumer<String> closeCallback;

    private TenantId tenantId;
    private long connectedTime;
    private volatile long lastActivityTime; // 使用 volatile 保证多线程可见性
    private volatile boolean connected;

    public TelemetryGrpcSession(String sessionId, StreamObserver<ResponseMsg> outputStream,
                                Consumer<String> closeCallback) {
        this.sessionId = sessionId;
        this.outputStream = outputStream;
        this.closeCallback = closeCallback;
        this.connectedTime = System.currentTimeMillis();
        this.lastActivityTime = System.currentTimeMillis();
        this.connected = true;
        this.tenantId = TenantId.SYS_TENANT_ID;
    }

    public void updateLastActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * 发送响应。此方法是线程安全的。
     * gRPC的StreamObserver不是线程安全的，当从不同的线程（例如，gRPC回调执行器）发送响应时，必须进行同步。
     * @param response 要发送的响应消息
     */
    public void sendResponse(ResponseMsg response) {
        if (connected) {
            try {
                synchronized (outputStream) {
                    outputStream.onNext(response);
                }
            } catch (Exception e) {
                log.warn("[{}] 发送响应时出错: {}", sessionId, e.getMessage());
            }
        }
    }

    public void close() {
        if (connected) {
            this.connected = false;
            try {
                outputStream.onCompleted();
            } catch (Exception e) {
                // 忽略流可能已经关闭的异常
            }
            if (closeCallback != null) {
                closeCallback.accept(sessionId);
            }
        }
    }

    /**
     * 检查会话是否因不活跃而过期。
     * @param timeoutMs 超时毫秒数
     * @return 如果会话已过期则返回 true
     */
    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - lastActivityTime > timeoutMs;
    }
}
