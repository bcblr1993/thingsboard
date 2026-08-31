# 新增 IoTDB 时序存储对现有系统的影响分析

> 面向 `feature/iotdb-timeseries` 分支。分析新增 IoTDB 实现后，对 ThingsBoard 4.1 **原有配置、接口、功能**的影响。结论均基于本仓库实际代码。

## 0. 一句话结论

新增 IoTDB 采用 ThingsBoard 既有的**条件装配**机制（`@ConditionalOnProperty`），**默认不启用**（`database.ts.type` 默认仍是 `cassandra`）。**不改动**任何对外 REST/WS 接口签名。唯一对所有部署生效的代码改动是 `BaseTimeseriesService`（已加保护条件，非 IoTDB 路径行为不变）。影响集中在：**启用 IoTDB 后**时序数据的写入/读取/TTL/最新值等**功能语义**，以及新增依赖与运维方式。

## 0.1 影响范围总览

| 模块 | 是否受影响 | 说明 |
|---|---|---|
| 对外 REST / WebSocket 遥测接口 | ❌ 签名不变 | 走 `TimeseriesService` 同一接口；仅底层实现不同，见第 3 节行为差异 |
| Swagger / OpenAPI 文档 | ❌ 结构不变 | 默认已关闭；未改 Controller/DTO；仅 `saveEntityTelemetryWithTTL` 的 TTL 语义变化，见 2.4 |
| 实体存储（设备/资产/仪表盘/用户…） | ❌ 不受影响 | 始终 PostgreSQL |
| **属性 Attributes** | ❌ 不受影响 | 属性由独立的 `JpaAttributeDao` 存储，与 `database.ts.type` **无关** |
| 规则引擎 / RPC / 告警 / 审计 / 边缘 | ❌ 结构不受影响 | 仅读写遥测时经由时序 DAO |
| 现有 Cassandra / SQL / Redis 部署 | ❌ 不启用 IoTDB 时零影响 | 条件装配，IoTDB 类不加载 |
| `BaseTimeseriesService`（时序服务） | ⚠️ 有代码改动 | 见第 2.2 节，已加保护，非 IoTDB 路径不变 |
| 时序**写入/读取/聚合/最新值/TTL/删除** | ✅ 启用后有语义差异 | 见第 3 节（重点） |
| 安装 / 升级 / 数据迁移 | ✅ 有影响 | 见第 4、5 节 |
| 依赖 / 构建 / 运维监控 | ✅ 有影响 | 见第 6 节 |

---

## 1. 配置层面的影响

### 1.1 新增配置项（`thingsboard.yml`）
- `database.ts.type` 增加可选值 `iotdb`（默认仍 `cassandra`，不改则无影响）。
- `database.ts_latest.type` 增加可选值 `iotdb`。
- 新增 `iotdb:` 配置块（`node_urls` / `username` / `password` / `database` / `pool_size` / `enable_redirection` / `enable_auto_fetch` / `enable_compression` / `ttl_ms` / `write.*`）。**仅当选用 iotdb 时生效**，其余部署忽略。

### 1.2 条件装配的连锁影响
`database.ts.type=iotdb` 时：
- **加载**：`IotdbBaseTimeseriesDao` 及 `IotdbSessionPoolConfig` / `IotdbWriteService`（`@IotdbAnyDao`）。
- **不加载**：`CassandraBaseTimeseriesDao`（`@NoSqlTsDao`）、`JpaSqlTimeseriesDao`（`@SqlTsDao`）等——即**Cassandra/SQL 时序相关的 Bean 与其连接不会初始化**。
- 因此 `cassandra:` 段配置在 iotdb 模式下**不再被时序读写使用**（若 `ts_latest.type` 也非 cassandra，则 Cassandra 集群连接整体不建立）。

### 1.3 有效组合矩阵（历史 × 最新值）
| `ts.type` | `ts_latest.type` | 结果 |
|---|---|---|
| iotdb | iotdb | 历史+最新都 IoTDB，写入合并不双写（推荐，去 Redis） |
| iotdb | redis | 历史 IoTDB、最新 Redis（微秒级最新值读） |
| cassandra / sql | iotdb | 历史沿用旧库、最新用 IoTDB（渐进迁移） |
| cassandra / sql | redis / … | 与本特性无关，零影响 |

### 1.4 其它相关配置项
- `database.ts_max_intervals`：聚合区间数量上限，**仍在服务层强制**（`BaseTimeseriesService.validate`，与 DAO 无关），IoTDB 模式行为一致。
- `sql.ttl.ts.*`（SQL 的 TTL 清理任务）：对 IoTDB **不生效**，见 3.5。

---

## 2. 代码 / 接口层面的影响

### 2.1 新增（无侵入）
全部通过条件注解装配，不修改既有类：3 个注解、7 个 DAO/工具/服务类、1 个安装期服务。对外接口 `TimeseriesService` / `TimeseriesDao` / `TimeseriesLatestDao` **签名不变**。

### 2.2 唯一对所有部署生效的改动：`BaseTimeseriesService`
```java
@Value("${database.ts.type:}") private String tsType;   // 新增字段, 默认空串
// doSave 内:
boolean skipRedundantIotdbLatest = saveTs
    && timeseriesLatestDao instanceof IotdbTimeseriesLatestDao
    && "iotdb".equalsIgnoreCase(tsType);
if (saveLatest && !skipRedundantIotdbLatest && (…|| timeseriesLatestDao instanceof IotdbTimeseriesLatestDao)) { … }
```
**风险评估（低）**：
- `skipRedundantIotdbLatest` 仅当 latest 是 `IotdbTimeseriesLatestDao` **且** `ts.type=iotdb` 时为 true；**任何非 IoTDB 部署恒为 false**，原有 Cassandra/SQL/Redis 分支逻辑与行为**完全不变**。
- 新增 `@Value("${database.ts.type:}")` 带默认空串，未配置也不报错。
- 含义：both=iotdb 时历史 `save()` 已通过 IoTDB LastCache 维护最新值，跳过冗余 `saveLatest`（详见集成设计报告 7.7）。

### 2.3 对外 API 行为（签名不变，语义差异见第 3 节）
REST `/api/plugins/telemetry/...`、WebSocket 订阅、规则引擎遥测节点均经由 `TimeseriesService`，**调用方式不变**；差异仅在底层数据语义。

### 2.4 Swagger / OpenAPI 接口
- **文档结构无影响**：本项目 Swagger 默认关闭（`springdoc.api-docs.enabled=${SWAGGER_ENABLED:false}`）；即使开启，本特性**未改动任何 Controller / DTO / 请求响应模型**，故 OpenAPI 的路径、参数、schema、`@ApiOperation` 描述**完全不变**。
- ⚠️ **仅一处「接口语义」变化**：`saveEntityTelemetryWithTTL`（`POST /api/plugins/telemetry/{entityType}/{entityId}/timeseries/{scope}/{ttl}`）——接口与 Swagger 描述不变、照常可调，但 **IoTDB 模式下 `ttl` 路径参数被忽略**（IoTDB 仅支持全局 `iotdb.ttl_ms`，见 3.5）。调用方不会报错，但 TTL 不按该值生效。

---

## 3. 功能层面的影响（启用 IoTDB 后，重点）

### 3.1 写入
- **异步攒批 + 纯内存缓冲**：`save()` 入缓冲区，达 `batch_size` 或 `flush_interval_ms` 后批量落库；返回的 `Future` 在 flush 后完成。
- ⚠️ **崩溃丢数据窗口**：进程崩溃时缓冲区中**未 flush 的点会丢失**（≤ `flush_interval_ms`）。这是为极致吞吐做的权衡，与 Cassandra/SQL 的「每条同步落库」不同。要求强可靠可改小 flush 窗口或后续接 WAL 策略。
- `savePartition` 为空操作（IoTDB 内部按时间自动分区），少一次写。

### 3.2 读取（范围查询）
- 行为与现有一致（返回区间内原始点、支持 `limit`、升/降序）。

### 3.3 聚合查询
- **下推到 IoTDB**（`GROUP BY ([s,e), interval)`），比 Cassandra「拉全量回 JVM 聚合」更快。
- 每个聚合窗口的时间戳取**窗口中点**（对齐 Cassandra 既有行为）。
- `ts_max_intervals` 上限仍在服务层强制，**不会**因下推而绕过。

### 3.4 最新值（Latest）
- 基于 IoTDB 原生 `SELECT LAST`（LastCache）。
- ⚠️ **读延迟**：约 **1–3ms**（网络级），低于 Redis 的微秒级。仪表盘实时订阅、规则引擎频繁读 originator 最新遥测时延迟略增（一般可接受）。
- ⚠️ **可见延迟**：LastCache 随 flush 更新，最新值可能有 ≤ `flush_interval_ms` 的滞后。
- `ts.type=iotdb` 时 `DefaultTbEntityDataSubscriptionService.tsInSqlDB=false`，实体数据查询走 **NoSQL 路径**（与 Cassandra 相同），经由时序 DAO 取最新值。

### 3.5 数据过期 / TTL（重要差异）
- ⚠️ **仅支持全局 TTL**（`iotdb.ttl_ms`，对应 IoTDB `set ttl to root.tb.**`）。
- ⚠️ **不支持每租户 / 每设备 TTL**：`save(tenantId, entityId, entry, ttl)` 的 `ttl` 参数（来自租户 Profile 等）在 IoTDB 实现中**被忽略**。若业务依赖「不同租户不同保留期」，IoTDB 模式**当前不满足**。
- ⚠️ SQL 的 `TimeseriesCleanUpService`（`sql.ttl.ts.enabled`）调用 `cleanup()` 对 IoTDB 为**空操作**，不会删除 IoTDB 数据；过期完全交给 IoTDB 原生 TTL。（此点与 Cassandra 一致——Cassandra 也靠原生 TTL。）

### 3.6 删除遥测
- 支持（`DELETE FROM <device>.<key> WHERE time …`）。

### 3.7 数据类型
- 映射：BOOLEAN→BOOLEAN、LONG→INT64、DOUBLE→DOUBLE、**STRING/JSON→TEXT**。
- ⚠️ **JSON 类型信息丢失**：STRING 与 JSON 都存为 TEXT，读回统一按 **STRING** 返回（不再区分 JSON）。历史遥测多用于展示，影响有限，但依赖 JSON 类型的场景需注意。
- ⚠️ **类型漂移被拒**：同一 key 先写 LONG 后写 DOUBLE 会被 IoTDB 拒绝（单测点类型固定），实现中捕获并记日志。此约束对 Cassandra 不存在（Cassandra 宽表多列）。

### 3.8 遥测 key 列举
- `findAllKeysByEntityIds`：用 `SHOW TIMESERIES` 实现。
- `findAllKeysByDeviceProfileId`：返回空列表（**与 Cassandra NoSQL 实现一致**，该能力在 NoSQL 路径本就由别处提供）。

### 3.9 EDQS（实体数据查询服务）
- IoTDB `saveLatest` 返回 `null` version → `edqsService.onUpdate(...)` 不触发（**与 Cassandra 一致**，Cassandra 的 `saveLatest` 也返回 null）。即 EDQS 的最新遥测推送行为与 Cassandra 部署相同，非 IoTDB 引入的回退。

### 3.10 实体视图（ENTITY_VIEW）
- 写入遥测到 ENTITY_VIEW 仍被禁止（既有行为，未改变）。

### 3.11 明确不受影响
- **属性 Attributes**：由 `JpaAttributeDao`（SQL/Postgres）存储，与 `database.ts.type` **无关**，读写、类型、TTL 均不受影响。

---

## 4. 数据与迁移影响

- ⚠️ **切换存储不迁移历史数据**：把 `ts.type` 从 cassandra/sql 改为 iotdb 后，**旧库历史数据不会自动出现在 IoTDB**，查询旧数据将查不到（数据仍在原库，只是不再被读取）。
- **迁移**：需专门的一次性迁移工具（读旧库 → 批量 `insertAlignedTablet` 入 IoTDB），本分支暂未提供。
- **双写过渡**：可先 `ts.type=cassandra` + `ts_latest.type=iotdb` 灰度，或自建双写。
- **回滚**：改回 `ts.type=cassandra` 即恢复读旧库；**IoTDB 期间写入的数据留在 IoTDB**，回滚后不可见（需反向迁移）。

## 5. 安装 / 升级影响

- **安装**：`IotdbTsDatabaseSchemaService` 免建表；数据库 `root.tb` 由连接池启动时自动创建；测点首次写入自动创建。
- **无 schema 迁移脚本**（不涉及 `.cql` / `.sql`）。
- `TsLatestDatabaseSchemaService` 为可选注入（`@Autowired(required=false)`），IoTDB 无需该 Bean。

## 6. 依赖 / 构建 / 运维影响

- **新增依赖** `org.apache.iotdb:iotdb-session:1.3.7`（thrift 已 shade，未见与现有冲突）；构建产物体积略增。
- ⚠️ **必须 JDK 17** 编译/运行（项目既有约束；JDK 21+/25 会因旧 Lombok 报 `TypeTag :: UNKNOWN`）。
- `license-maven-plugin` 已排除 `.iotdb-docker/**`、`iotdb-benchmark/**`（否则本地压测/数据文件会导致 license 检查失败）。
- **监控**：Cassandra 的 JMX/metrics 在 iotdb 模式不适用；改为关注 IoTDB 自身指标与 `docker/iotdb/*.yml` 的资源/调优。
- **备份恢复**：由 Cassandra/SQL 备份方式改为 IoTDB 的数据目录/快照方式，运维手册需更新。

## 7. 风险矩阵与缓解

| 风险 | 影响面 | 缓解 |
|---|---|---|
| 崩溃丢失未 flush 的点 | 数据完整性 | 调小 `flush_interval_ms`；关键链路评估是否可接受 |
| 不支持每租户 TTL | 多租户保留策略 | 统一全局 TTL；或按 database 分租户（改造）；或历史留旧库 |
| JSON 类型退化为 STRING | 依赖 JSON 遥测的读取 | 业务侧按字符串解析；或后续加类型标记 |
| 同 key 类型漂移被拒 | 数据写入 | 规范上报类型；监控失败日志 |
| 最新值读延迟 µs→ms | 高频实时读 | latest 继续用 Redis（`ts_latest.type=redis`） |
| 切换不迁移历史数据 | 上线可见性 | 迁移工具 / 双写灰度 / 保留旧库只读 |

## 8. 上线前检查清单

- [ ] 确认是否需要**每租户 TTL**；若需要，评估 IoTDB 全局 TTL 是否可接受。
- [ ] 确认对**崩溃丢点窗口**（≤flush_interval）的容忍度。
- [ ] 确认是否有依赖 **JSON 类型**的遥测读取。
- [ ] 规划**历史数据迁移/双写**策略（切换不自动迁移）。
- [ ] latest 选型：IoTDB（去 Redis、强一致）vs Redis（微秒级读）。
- [ ] 用 **JDK 17** 构建；更新 CI/部署脚本。
- [ ] 更新**监控/备份**手册为 IoTDB 方式。
- [ ] 生产建议 TB 与 IoTDB **分机部署**；集群用 3C3D + host 网络（见 `docker/iotdb/docker-compose-cluster.yml`）。

---

**关联文档**：详细设计与压测见 [`IoTDB时序存储集成设计与压测报告.md`](IoTDB时序存储集成设计与压测报告.md)；部署见 `docker/iotdb/`。
