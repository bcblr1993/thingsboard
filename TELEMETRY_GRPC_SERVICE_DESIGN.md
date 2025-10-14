# 遥测专用gRPC服务设计文档

## 概述

本文档描述了ThingsBoard遥测专用gRPC服务的设计和实现。该服务专门用于处理遥测数据，与现有的Edge gRPC服务（7070端口）并行运行，监听7071端口。

## 设计目标

1. **专门处理遥测数据**: 只处理遥测数据，不处理其他Edge功能
2. **复用现有逻辑**: 完全复用现有的遥测处理逻辑
3. **支持多连接**: 支持多个客户端同时连接
4. **客户端认证**: 支持用户名密码或令牌认证
5. **完全兼容**: 使用现有的protobuf格式
6. **独立运行**: 不影响现有的Edge服务

## 架构设计

### 核心组件

```
TelemetryGrpcService (主服务类)
├── TelemetryGrpcSession (会话管理)
├── 连接管理 (Semaphore)
└── 认证管理 (Edge凭证/自定义令牌)
```

### 服务结构

- **TelemetryGrpcService**: 主服务类，负责启动gRPC服务器和管理连接
- **TelemetryGrpcSession**: 会话管理类，管理单个客户端连接
- **连接管理**: 使用Semaphore控制最大连接数
- **认证管理**: 支持Edge凭证和自定义令牌两种认证方式

## 配置参数

### 基础配置

```yaml
telemetry_grpc:
  enabled: false                    # 是否启用服务
  port: 7071                       # 监听端口
  max_connections: 100             # 最大连接数
```

### SSL配置

```yaml
telemetry_grpc:
  ssl:
    enabled: false                 # 是否启用SSL
    cert: telemetry_server.pem     # SSL证书文件
    private_key: telemetry_server_key.pem  # SSL私钥文件
```

### 连接保活配置

```yaml
telemetry_grpc:
  keep_alive:
    time_sec: 10                   # 保活时间间隔
    timeout_sec: 5                 # 保活超时时间
    client_max_keep_alive_time_sec: 1  # 客户端最大保活时间
```

### 消息配置

```yaml
telemetry_grpc:
  max_inbound_message_size: 4194304  # 最大入站消息大小(4MB)
  thread_pool_size: 4              # 线程池大小
```

### 认证配置

```yaml
telemetry_grpc:
  auth:
    type: edge_credentials         # 认证方式: edge_credentials 或 custom_tokens
    custom_tokens:
      allowed_tokens: "token1,token2,token3"  # 允许的令牌列表
      validation_timeout: 30       # 令牌验证超时时间
```

## 消息处理流程

### 1. 连接建立

1. 客户端连接到7071端口
2. 检查连接数限制
3. 创建TelemetryGrpcSession
4. 等待认证请求

### 2. 认证流程

1. 客户端发送CONNECT_RPC_MESSAGE
2. 根据配置选择认证方式：
   - **Edge凭证认证**: 验证routingKey和secret
   - **自定义令牌认证**: 验证令牌是否在允许列表中
3. 发送认证响应
4. 认证失败则关闭连接

### 3. 遥测数据处理

1. 客户端发送UPLINK_RPC_MESSAGE
2. 提取EntityData部分
3. 调用现有的遥测处理逻辑
4. 等待所有处理完成
5. 发送处理结果响应

### 4. 其他消息处理

- 其他消息类型直接忽略，不记录日志
- 只处理UPLINK_RPC_MESSAGE中的EntityData部分

## 认证机制

### Edge凭证认证

使用现有的Edge认证逻辑：
- `edgeRoutingKey`: 作为用户名
- `edgeSecret`: 作为密码
- 通过Edge服务验证凭证有效性
- 获取对应的租户ID

### 自定义令牌认证

使用配置的令牌列表：
- `edgeSecret`: 作为令牌
- 验证令牌是否在允许列表中
- 使用系统租户ID

## 数据处理流程

### 遥测数据处理

1. 接收UplinkMsg中的EntityData
2. 调用TelemetryEdgeProcessor.processTelemetryMsg()
3. 调用BaseTelemetryProcessor.processPostTelemetry()
4. 创建TbMsg消息
5. 推送到规则引擎
6. 发送处理结果响应

### 响应处理

- 成功: success=true, errorMsg=""
- 失败: success=false, errorMsg=错误信息
- 包含uplinkMsgId用于消息匹配

## 错误处理

### 连接错误

- 连接数超限: 拒绝新连接
- 认证失败: 关闭连接
- 消息处理错误: 发送错误响应

### 日志记录

- 连接建立和断开
- 认证成功和失败
- 遥测数据处理结果
- 错误信息记录

## 性能特性

### 连接管理

- 使用Semaphore控制最大连接数
- 自动清理断开的连接
- 支持连接保活机制

### 异步处理

- 异步处理遥测数据
- 使用Future等待处理完成
- 非阻塞响应发送

### 线程池

- 可配置的线程池大小
- 专门的处理线程
- 回调线程池

## 安全特性

### 认证安全

- 强制客户端认证
- 支持多种认证方式
- 认证失败立即断开

### 连接安全

- SSL/TLS支持
- 连接数限制
- 超时处理

## 监控和运维

### 日志监控

- 连接状态日志
- 认证结果日志
- 处理结果日志
- 错误日志

### 性能监控

- 连接数统计
- 处理延迟统计
- 错误率统计

## 部署说明

### 配置文件

在thingsboard.yml中添加telemetry_grpc配置节

### 依赖关系

- 依赖现有的EdgeContextComponent
- 依赖现有的TelemetryEdgeProcessor
- 依赖现有的Edge服务

### 启动顺序

- 在现有Edge服务启动后启动
- 使用@AfterStartUp注解控制启动顺序

## 测试说明

### 单元测试

- 认证逻辑测试
- 消息处理测试
- 错误处理测试

### 集成测试

- 端到端连接测试
- 遥测数据处理测试
- 多连接并发测试

### 性能测试

- 连接数压力测试
- 消息处理性能测试
- 内存使用测试

## 扩展性

### 水平扩展

- 支持多实例部署
- 负载均衡支持
- 集群部署

### 功能扩展

- 支持更多认证方式
- 支持更多消息类型
- 支持更多配置选项

## 兼容性

### 向后兼容

- 不影响现有Edge服务
- 不影响现有客户端
- 不影响现有配置

### 协议兼容

- 完全使用现有protobuf格式
- 完全兼容现有消息结构
- 完全兼容现有响应格式

## 总结

遥测专用gRPC服务提供了一个专门处理遥测数据的高性能、高安全性的解决方案。通过复用现有的处理逻辑和消息格式，确保了系统的稳定性和兼容性。同时，通过独立的服务和配置，提供了灵活的部署和运维选项。
