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
package org.thingsboard.rule.engine.mqtt.subscribe;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientCallback;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.credentials.BasicCredentials;
import org.thingsboard.rule.engine.credentials.ClientCredentials;
import org.thingsboard.rule.engine.credentials.CredentialsType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import javax.net.ssl.SSLException;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RuleNode(
        type = ComponentType.EXTERNAL,
        name = "mqtt subscribe",
        configClazz = TbMqttSubscribeNodeConfiguration.class,
        nodeDescription = "Subscribe to topics on the MQTT broker",
        nodeDetails = "Will subscribe to MQTT topics and process incoming messages.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbExternalNodeMqttConfig",
        icon = "call_merge",
        inEnabled = false
)
public class TbMqttSubscribeNode implements TbNode {


    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String ERROR = "error";

    private static final String externalMsgType = "EXTERNAL_MQTT_MESSAGE";
    protected TbMqttSubscribeNodeConfiguration mqttSubscribeNodeConfiguration;

    protected MqttClient mqttClient;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        try {
            this.mqttSubscribeNodeConfiguration = TbNodeUtils.convert(configuration, TbMqttSubscribeNodeConfiguration.class);
            this.mqttClient = initClient(ctx);
        } catch (Exception e) {
            throw new TbNodeException(e);
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        // Handle incoming messages here
    }

    @Override
    public void destroy() {
        if (this.mqttClient != null) {
            this.mqttClient.disconnect();
        }
    }

    protected MqttClient initClient(TbContext ctx) throws Exception {
        MqttClientConfig config = new MqttClientConfig(getSslContext());
        if (!StringUtils.isEmpty(this.mqttSubscribeNodeConfiguration.getClientId())) {
            config.setClientId(this.mqttSubscribeNodeConfiguration.isAppendClientIdSuffix() ?
                    this.mqttSubscribeNodeConfiguration.getClientId() + "_" + ctx.getServiceId() : this.mqttSubscribeNodeConfiguration.getClientId());
        }
        config.setCleanSession(this.mqttSubscribeNodeConfiguration.isCleanSession());

        prepareMqttClientConfig(config);
        MqttClient client = MqttClient.create(config, null,ctx.getExternalCallExecutor());
        client.setEventLoop(ctx.getSharedEventLoop());

        //设置重连后重新订阅
        client.setCallback(new MqttClientCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                //断开连接
                String hostPort = mqttSubscribeNodeConfiguration.getHost() + ":" + mqttSubscribeNodeConfiguration.getPort();
                log.info("connectionLost to MQTT broker at {}", hostPort);
            }
            @Override
            public void onSuccessfulReconnect() {
                //重新连接
                String hostPort = mqttSubscribeNodeConfiguration.getHost() + ":" + mqttSubscribeNodeConfiguration.getPort();
                log.info("success to reconnect to MQTT broker at {}", hostPort);
                subscribeToTopic(ctx,client);
            }
        });

        Future<MqttConnectResult> connectFuture = client.connect(this.mqttSubscribeNodeConfiguration.getHost(), this.mqttSubscribeNodeConfiguration.getPort());
        MqttConnectResult result;
        try {
            result = connectFuture.get(this.mqttSubscribeNodeConfiguration.getConnectTimeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            connectFuture.cancel(true);
            client.disconnect();
            String hostPort = this.mqttSubscribeNodeConfiguration.getHost() + ":" + this.mqttSubscribeNodeConfiguration.getPort();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s.", hostPort));
        }
        if (!result.isSuccess()) {
            connectFuture.cancel(true);
            client.disconnect();
            String hostPort = this.mqttSubscribeNodeConfiguration.getHost() + ":" + this.mqttSubscribeNodeConfiguration.getPort();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s. Result code is: %s", hostPort, result.getReturnCode()));
        } else {
            //订阅
            subscribeToTopic(ctx,client);
        }
        return client;
    }

    /**
     * 订阅mqtt主题
     * @param ctx ctx
     * @param mqttClient mqttClient
     */
    private void subscribeToTopic(TbContext ctx, MqttClient mqttClient) {
        /**
         * MqttQoS.AT_LEAST_ONCE:
         * 这个服务质量等级保证消息至少被传输一次。当消息发布者发布消息时，
         * 它会尽力将消息传输给MQTT代理，然后代理会将消息传输给订阅者，并等待
         * 订阅者的确认。如果没有收到确认，代理会尝试重新传输消息，直到收到确认。
         * 这确保了消息至少被传输一次，并且消息的顺序得到保留，但可能会出现重复传
         * 输的情况。
         *
         * MqttQoS.EXACTLY_ONCE:
         * 这个服务质量等级保证消息被精确地传输一次。当消息发布者发布消息时，
         * 它会尽力将消息传输给MQTT代理，然后代理会将消息传输给订阅者，并等待
         * 订阅者的确认。一旦收到确认，代理会记住消息的标识符，并在必要时重新传
         * 输消息，直到订阅者确认收到消息。这确保了消息只被传输一次，且不会出现
         * 重复传输的情况。
         */
        mqttClient.on(this.mqttSubscribeNodeConfiguration.getTopicPattern(), (topic, body) -> {
            String data = body.toString(UTF8);

            //从描述中获取绑定实体类型及ID,格式entityType:entityId
            org.thingsboard.server.common.data.rule.RuleNode self = ctx.getSelf();
            String describe = self.getAdditionalInfoField("description", JsonNode::asText, "");
            //构建消息的元数据对象
            TbMsgMetaData metaData = new TbMsgMetaData();
            metaData.putValue("topic", topic);
            metaData.putValue("time", String.valueOf(System.currentTimeMillis()));
            TbMsg.TbMsgBuilder builder = TbMsg.newMsg()
                    .type(externalMsgType)
                    .metaData(metaData)
                    .dataType(TbMsgDataType.JSON)
                    .data(data);
            if (describe != null && !describe.isEmpty()) {
                try {
                    String[] describes = describe.split(":");
                    EntityId entityId = EntityIdFactory.getByTypeAndId(describes[0], describes[1]);
                    //生成新的消息
                    builder.originator(entityId);
                    ctx.tellSuccess(builder.build());
                } catch (Exception e) {
                    builder.originator(self.getId());
                    ctx.tellFailure(builder.build(), e);
                }
            } else {
                builder.originator(self.getId());
                ctx.tellFailure(builder.build(), new RuntimeException("The description cannot be empty"));
            }

            return CompletableFuture.completedFuture(null);
        }, MqttQoS.AT_MOST_ONCE);
    }

    protected void prepareMqttClientConfig(MqttClientConfig config) throws SSLException {
        ClientCredentials credentials = this.mqttSubscribeNodeConfiguration.getCredentials();
        if (credentials.getType() == CredentialsType.BASIC) {
            BasicCredentials basicCredentials = (BasicCredentials) credentials;
            config.setUsername(basicCredentials.getUsername());
            config.setPassword(basicCredentials.getPassword());
        }
    }

    private SslContext getSslContext() throws SSLException {
        return this.mqttSubscribeNodeConfiguration.isSsl() ? this.mqttSubscribeNodeConfiguration.getCredentials().initSslContext() : null;
    }



}
