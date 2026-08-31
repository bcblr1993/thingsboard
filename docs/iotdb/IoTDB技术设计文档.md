# ThingsBoard 4.1 × Apache IoTDB 时序存储 —— 技术设计文档

| 项目 | 内容 |
|---|---|
| 文档性质 | 竣工版（As-Built）技术设计文档，描述当前代码的实际实现 |
| 分支 | `feature/iotdb-timeseries` |
| IoTDB 版本 | Apache IoTDB **1.3.7**（树模型，`iotdb-session` Java 客户端；tsfile 独立为 `org.apache.tsfile:1.1.3`） |
| 存储范围 | 历史时序（`TimeseriesDao`）+ 最新值（`TimeseriesLatestDao`），配置独立切换 |
| 构建要求 | **JDK 17**（本地默认 JDK 24 会导致 Lombok 编译失败） |
| 文档日期 | 2026-07-15（1.3.2→1.3.7 升级） |
| 关联文档 | 《IoTDB-1.3.7生产部署最佳调优配置.md》（生产配置速查）、《IoTDB参数调优指南.md》（原理与运维调参手册）、《IoTDB四机容量实测矩阵.md》《IoTDB全机械盘六类负载容量报告.md》（容量选型依据）、《IoTDB时序存储集成设计与压测报告.md》（方案调研与基准压测）、《IoTDB集成影响分析.md》 |

---

## 1. 概述

本模块为 ThingsBoard 4.1 新增 Apache IoTDB 时序存储后端，与既有 Cassandra / SQL / TimescaleDB 实现并列，通过配置切换、零侵入装配。设计目标：

1. **写入吞吐逼近 IoTDB 原生上限**：TB 的 DAO 接口是逐点调用（一次一个 `TsKvEntry`），而 IoTDB 的性能全部来自 Tablet 批量接口。核心工作是在两者之间实现一个高吞吐的**内存写缓冲**（分片 → 按设备/时间戳合并 → 对齐 Tablet 批量落库）。
2. **查询下推**：原始查询、聚合（GROUP BY 时间窗）、最新值全部下推给 IoTDB 执行，不在 JVM 内聚合。
3. **单机/集群同构**：`node_urls` 一个地址即单机、多个地址即集群，代码路径完全一致。
4. **历史与最新值合一**：两个 DAO 共享同一写缓冲，同一 `(device, ts, key)` 的历史写与 latest 写在缓冲内合并为一次物理写。

实测基准（独立 harness，8 核/16G 容器、4G 堆 IoTDB）：单机稳态 **~3.1M 点/秒**；端到端（MQTT → 规则引擎 → IoTDB，32 核/64G 全家桶单机）：**60 万点/秒**可运行但不可持续（见 §10）。

---

## 2. 总体架构

### 2.1 组件与数据流

```
                    ┌──────────────────────────── application ─────────────────────────────┐
 MQTT/HTTP/CoAP ──► 规则引擎 Save Timeseries 节点 ──► BaseTimeseriesService
                                                         │  ① saveBatch(整条消息)  ← BatchedTimeseriesDao
                                                         │  ② findLatest(多 key)   ← BatchedTimeseriesLatestDao
                                                         │  (两个可选能力接口: 仅 IoTDB 实现,
                                                         │   instanceof 门控 → 其他后端走原逐点/逐 key 路径)
                    ┌─────────────────────────── dao ─────┼──────────────────────────────┐
                    │     IotdbBaseTimeseriesDao      IotdbTimeseriesLatestDao           │
                    │     (历史: 批量写/原始+聚合查询/删除)  (最新值: SELECT LAST / 多 key 批量)  │
                    │            │         └──────┬───────┘         │                    │
                    │            │   ┌────────────┴────────────┐    │                    │
                    │   写 ──────┴──►│    IotdbWriteService    │◄───┘ 写                 │
                    │               │      (唯一写入口)        │                          │
                    │               └────────────┬────────────┘                          │
                    │                 IotdbTimeseriesWriteBuffer                         │
                    │                 (N 个 Shard 线程: 合并→Tablet→批量 RPC)             │
                    │                                                                     │
                    │   读 ──► 有界队列线程池(fast-fail) + IotdbQueryMetrics(耗时/慢查询)   │
                    │                              │                                      │
                    │        IotdbSessionPoolConfig: 写池 ║ 读池 (物理隔离)                │
                    └──────────────────────────────┼──────────────────────────────────────┘
                                 写 ▼ insertAlignedTablet(s)      读 ▼ SQL / SELECT LAST
                                              Apache IoTDB (单机或 3C3D 集群)
```

**要点**：写与读在**连接池层面物理隔离**（查询洪峰占不到写连接）；历史与 latest 共用同一个
写缓冲（同 `(device, ts, key)` 的重复点在缓冲内合并，物理只写一次）。

### 2.2 文件清单与职责

#### IoTDB 专属实现（`@IotdbTsDao` / `@IotdbTsLatestDao` / `@IotdbAnyDao` 条件装配）

| 文件 | 职责 |
|---|---|
| `dao/.../timeseries/iotdb/IotdbBaseTimeseriesDao.java` | 历史存储 DAO。`saveBatch` 整条消息入队写缓冲（实现 `BatchedTimeseriesDao`）、`save` 单点兼容路径；`findAllAsync` 原始查询与**双路径聚合**（固定毫秒走原生 GROUP BY，自然周/月/季逐窗计算，见 §5）；`remove` 范围删除。读任务经有界队列 fast-fail 提交 |
| `dao/.../timeseries/iotdb/IotdbTimeseriesLatestDao.java` | 最新值 DAO。`saveLatest` 走共享写缓冲；读基于 `SELECT LAST`（IoTDB LastCache，内存级），**多 key 合并为单次查询**（实现 `BatchedTimeseriesLatestDao`，按 `latest_batch_size` 分块）；`removeLatest` 半开区间判定与 latest 重写 |
| `dao/.../timeseries/iotdb/IotdbWriteService.java` | 唯一写入口。持有**单例**写缓冲，使历史/latest 的重复点合并；注册写侧 Micrometer 指标与周期统计日志（含 `typeDriftPoints`） |
| `dao/.../timeseries/iotdb/IotdbTimeseriesWriteBuffer.java` | 写路径核心：分片、攒批、对齐 Tablet、批量 RPC、背压、毒丸免疫与类型冲突恢复（详见 §4，逐行走读见《[IoTDB写路径代码走读](IoTDB写路径代码走读.md)》） |
| `dao/.../timeseries/iotdb/IotdbSessionPoolConfig.java` | 连接层：构建**读/写两个物理隔离的 `SessionPool`**；启动建库 fail-fast（含重试）；全局 TTL 设置/清除（`ttl_fail_fast` 可选阻断启动）；**EDQS 与 `latest=iotdb` 不兼容组合的启动硬拦截**（见 §7） |
| `dao/.../timeseries/iotdb/IotdbSchemaUtil.java` | 路径/类型映射工具（TB ↔ IoTDB）：设备路径、测点名反引号转义与还原、类型映射（含 JSON→`STRING`）、非法/保留字 key 判定 |
| `dao/.../timeseries/iotdb/IotdbQueryMetrics.java` | 读路径可观测性：查询耗时/次数/错误计数，慢查询（≥`read_slow_query_ms`）计入 `iotdbQuery.slow` 并 WARN |
| `common/dao-api/.../util/IotdbTsDao.java`、`IotdbTsLatestDao.java`、`IotdbAnyDao.java` | 条件装配开关（见 §2.3） |
| `application/.../install/IotdbTsDatabaseSchemaService.java` | install 流程的空实现（IoTDB 无需建表，序列首写自动创建） |

#### 共享层改动（跨后端，均以 `instanceof` 门控确保其他后端行为不变）

| 文件 | 职责 |
|---|---|
| `dao/.../timeseries/BatchedTimeseriesDao.java` | **可选能力接口**：后端若能把"一条遥测消息"作为整体确认，则实现 `saveBatch`。契约：返回的 Future 仅在**每个可存储条目**都被后端持久接受后完成，返回**已接受**的 `dataPoints` 之和；后端特有的非法条目**可被隔离并单独上报**，从而一个毒丸 key 不会阻止同消息其余合法条目落库。当前仅 `IotdbBaseTimeseriesDao` 实现 |
| `dao/.../timeseries/BatchedTimeseriesLatestDao.java` | **可选能力接口**：latest 后端若能一次查询多个测点，则实现 `findLatest(keys)`。用于消除实体查询"设备数 × key 数"的查询放大。当前仅 `IotdbTimeseriesLatestDao` 实现 |
| `dao/.../timeseries/BaseTimeseriesService.java` | 装配点：`saveBatch`/`findLatest(keys)` 两处以 `instanceof` 判定是否走批量快路径；历史与 latest 同为 IoTDB 时跳过冗余的 per-entry `saveLatest`（写缓冲已维护 LastCache）。**非 IoTDB 后端走原逐点/逐 key 路径，行为逐字节不变** |
| `dao/.../sql/query/TsLatestAwareEntityQueryDao.java` | 实体查询（`ts_latest` 非 sql/timescale 时激活，含 redis/cassandra/iotdb）：遥测过滤在 Java 侧求值、保留 `textSearch` 与排序、按遥测 latest 值排序采用全序比较器 |

#### 测试

| 文件 | 职责 |
|---|---|
| `dao/src/test/.../iotdb/IotdbTimeseriesWriteBufferTest.java` | 写缓冲：稀疏 TEXT NPE 回归（§11.1）、毒丸隔离、类型漂移、停机竞态与并发记账不变量 |
| `dao/src/test/.../iotdb/IotdbBaseTimeseriesDaoTest.java` | 历史 DAO：limit 语义、聚合 `lastEntryTs` 契约、读队列 fast-fail |
| `dao/src/test/.../iotdb/IotdbTimeseriesLatestDaoTest.java` | latest DAO：`removeLatest` 边界、查询故障不伪装成空、批量 latest 契约 |
| `dao/src/test/.../iotdb/IotdbSessionPoolConfigTest.java` | 启动健壮性：建库 fail-fast、TTL 设置/清除与 `ttl_fail_fast` |
| `dao/src/test/.../iotdb/IotdbSchemaUtilTest.java` | 路径/类型映射：特殊字符 key 往返、JSON↔STRING、保留字识别 |
| `dao/src/test/.../iotdb/IotdbIntegrationIT.java` | **集成测试（Testcontainers + 真实 IoTDB 1.3.7）**：批量 latest（特殊字符/JSON/缺失值）、持久化类型漂移丢点不失败消息。以 `iotdb-integration` profile + failsafe 隔离（见 §13） |
| `dao/src/test/.../timeseries/BaseTimeseriesServiceBatchTest.java`、`BaseTimeseriesServiceEdqsTest.java` | 共享层门控：批量路径生效性与非 IoTDB 后端不受影响；EDQS 更新语义 |
| `dao/src/test/.../iotdb/TestMetrics.java` | 测试辅助：构造无副作用的 `IotdbQueryMetrics`，供 DAO 单测注入 |

#### 运维与部署资产

| 文件 | 职责 |
|---|---|
| `iotdb-benchmark/`（`IotdbWriteBenchmark`） | **独立压测模块**（`main()` 直跑，不参与 Spring 装配）：复刻写入策略的纯 IoTDB 压测、容量评估（paced 限速节拍 / max 裸吞吐）。**压测工具一律不放在 `dao/src/main`**，避免含 `delete database` 的破坏性代码进入生产制品 |
| `docker/iotdb/docker-compose-standalone.yml` / `-cluster.yml` | 单机与 3C3D 集群部署编排 |
| `docker/iotdb/thingsboard.env.example` | **生产 env 模板**：存储开关、EDQS 护栏、TTL、超时/重试、读写池与队列等（密码为 `CHANGE_ME` 占位） |
| `docker/iotdb/bin/iotdb-purge-orphan-series.sh` | 孤儿序列清理：比对 IoTDB 序列与 PG 存活设备，默认 dry-run，`--apply` 才删除（见调优文档 §6.3） |
| `docker/iotdb/bin/entrypoint-standalone.sh`、`docker/iotdb/ha/` | 单机容器入口脚本与双机热备编排 |

### 2.3 装配机制（零侵入切换）

沿用 TB 的 `@ConditionalOnProperty` 元注解模式，三个开关注解定义在 `common/dao-api`：

| 注解 | 生效条件 | 标注对象 |
|---|---|---|
| `@IotdbTsDao` | `database.ts.type=iotdb` | `IotdbBaseTimeseriesDao`、install 服务 |
| `@IotdbTsLatestDao` | `database.ts_latest.type=iotdb` | `IotdbTimeseriesLatestDao` |
| `@IotdbAnyDao` | 上面两者任一为 `iotdb`（SpEL 表达式） | `IotdbSessionPoolConfig`、`IotdbWriteService`（共享组件） |

支持三种组合：仅历史用 IoTDB（latest 继续 Redis/SQL）、仅 latest 用 IoTDB、历史+latest 都用 IoTDB（推荐，写合并收益最大）。

`BaseTimeseriesService` 中有一处配套修改：当历史与 latest 都是 IoTDB 时，`save()` 本身就会更新 IoTDB 的 LastCache，因此跳过冗余的显式 `saveLatest`（`skipRedundantIotdbLatest`），写放大直接减半。

#### 第二层：能力接口门控（`instanceof`，跨后端安全的关键）

除 Spring 条件装配外，还有一层**运行时能力探测**——共享的 `BaseTimeseriesService` 通过
`instanceof` 判断当前 DAO 是否具备批量能力，具备则走快路径，否则走原有逐点/逐 key 路径：

| 能力接口 | 判定点 | 实现者 | 其他后端（SQL/Cassandra/Redis/Timescale） |
|---|---|---|---|
| `BatchedTimeseriesDao` | `save(...)` 保存遥测消息 | 仅 `IotdbBaseTimeseriesDao` | `instanceof` 为 false → 走原 `savePartition` + 逐点 `save` 循环 |
| `BatchedTimeseriesLatestDao` | `findLatest(tenantId, entityId, keys)` | 仅 `IotdbTimeseriesLatestDao` | `instanceof` 为 false → 走原逐 key `findLatest` + `allAsList` |

> **为什么用能力接口而不是直接判 `tsType.equals("iotdb")`**：能力接口把"能否批量"表达为**类型系统层面的契约**，
> 新后端只要实现接口即可自动获得快路径，无需再改 `BaseTimeseriesService` 的分支逻辑；
> 同时 `instanceof` 为 false 时代码路径与改造前**逐字节一致**，从结构上保证其他后端零影响。
>
> 两个接口的**契约要点**：`saveBatch` 返回的 Future 必须在**每个可存储条目**都被后端持久接受后才完成，
> 返回**已接受**的 `dataPoints` 之和；后端特有的非法条目（如 IoTDB 的保留字 key、类型漂移点）
> 可被**隔离并单独上报**，从而一个毒丸 key 不会阻止同消息其余条目落库（失败语义详见 §4.4）。
> `findLatest(keys)` 必须为**每个请求 key 返回一个条目**（缺失者补 null 值占位）以与逐 key 路径等价。

---

## 3. 数据模型

### 3.1 路径设计（对齐树模型）

```
<database>.<ENTITY_TYPE>.u_<uuid('-'→'_')>.`<telemetry key>`
例: root.tb.DEVICE.u_d62254e0_79e2_11f1_a754_6dab4efd0a2c.`temperature`
```

- `database` 默认 `root.tb`（可配），一个 TB 实例一个 database（storage group）。
- 实体一层用 `ENTITY_TYPE`（DEVICE/ASSET/…）+ UUID 消毒串，天然避免任何名称冲突；UUID 前缀 `u_` 保证节点名不以数字开头。
- 测点 key 统一**反引号引用**（`` ` `` 转义为 ``` `` ```），任意特殊字符的 key 都安全（见 `IotdbSchemaUtil.quote`）。
- 序列为**对齐时间序列**（aligned）：同设备同一时刻的多测点共享时间列，这是 IoTDB 官方高吞吐基准的前提。
- **无需建模/建表**：序列在首次写入时由 IoTDB 自动创建（`IotdbTsDatabaseSchemaService` 因此是空实现）。注意这带来"新设备首写风暴"问题，见 §10.2。

### 3.2 类型映射

| TB `DataType` | IoTDB `TSDataType` | 编码 | 压缩 |
|---|---|---|---|
| BOOLEAN | BOOLEAN | RLE | LZ4 |
| LONG | INT64 | TS_2DIFF | LZ4 |
| DOUBLE | DOUBLE | GORILLA | LZ4 |
| STRING | TEXT（值包成 `Binary`，UTF-8） | PLAIN | LZ4 |
| JSON | **STRING**（IoTDB 1.3.7 独立类型，值包成 `Binary`，UTF-8） | PLAIN | LZ4 |

读方向：`STRING` 列还原为 `JsonDataEntry`（JSON 保真），`TEXT` 列还原为 `StringDataEntry`；并额外兼容 IoTDB 的 INT32/FLOAT（映射回 TB 的 Long/Double），以容忍外部工具写入的数据。JSON 用独立 `STRING` 类型承载，是为了在序列类型层面与普通字符串区分、读回精确还原（对齐 SQL 后端的独立 `json_v` 列）。

> ⚠️ **升级影响**：早期版本 JSON 也映射为 `TEXT`。若历史数据里某 key 曾以 JSON 写入（存为 `TEXT`），新版本改写为 `STRING` 会因 IoTDB 序列类型不可变而冲突（被类型漂移隔离拦截）。这类序列需重建/迁移。普通字符串、数值、布尔 key 不受影响。

**类型稳定性约束**：同一 key 的类型必须稳定，详见 [§12.1](#121-类型稳定性使用约束重要)。

---

## 4. 写路径设计（性能核心）

> 📖 **逐行代码走读**见《[IoTDB写路径代码走读](IoTDB写路径代码走读.md)》——跟着一条遥测消息
> 从规则引擎走到 IoTDB，逐段解释 `addBatch` / `DeviceBatch.add` / `toTablet` / `flushAll` /
> `BatchCompletion` 每行在做什么、为什么必须这么写（含配具体数据的合并过程演示）。
> 本节讲**为什么这样设计**，走读文档讲**代码怎么跑的**。

### 4.1 设计动机

TB 规则引擎对每条遥测消息的每个 key 调一次 `save()`。4000 设备 × 每秒 150 key ≈ **60 万次/秒**的逐点调用，逐点 RPC 会瞬间打爆 IoTDB。`IotdbTimeseriesWriteBuffer` 的职责就是把逐点流重组为 IoTDB 喜欢的形态：**每设备一个对齐 Tablet、多设备合并成一次 RPC**。

### 4.2 整体结构

```
saveBatch(device, 一条消息的所有点) ─┐   add(单点) 亦包装为 addBatch(单点)
addBatch(device, points, dataPoints)─┴─hash(device)─► Shard[i].queue (无锁 CLQ)
   · 一条消息一个 BatchCompletion(Future)               │  shard 线程 drain(≤8192/轮)
   · 消息大于分片水位时按水位分段入队, 共享同一 Future     │
                                                        ▼
                                       pending: Map<device, DeviceBatch>
                                       DeviceBatch: ts → Object[列]（按测点列索引直写）
                                                        │
              触发: ①设备行数 ≥ batch_size(1000)  ②距上次 flush ≥ flush_interval_ms(默认 1000)
                                                        ▼
                                       toTablet(): 对齐 Tablet + null bitmap
                                                        │
              多设备合并: 单次 RPC ≤ 500,000 cells 且 ≤ 1000 设备
                                                        ▼
                              insertAlignedTablet / insertAlignedTablets (SessionPool)
```

### 4.3 关键机制逐条说明

**分片（Shard）**：`iotdb.write.shards` 个独立线程（默认 = CPU 核数），按 `device.hashCode()` 取模路由。同一设备恒定落在同一分片 → 单设备的合并无需任何锁；分片间完全并行。队列用 `ConcurrentLinkedQueue` + `AtomicInteger` 计数，生产者（规则引擎线程）与消费者（shard 线程）无锁交互。

**按 (device, ts) 合并成行**：`DeviceBatch` 内 `LinkedHashMap<Long, Object[]>` 以时间戳为行、测点为列累积。同一设备同一时刻的 N 个 key 合并为一行 N 列 —— 这正是"历史 + latest 双写合并"的实现点：两个 DAO 对同一 `(device, ts, key)` 各 add 一次，第二次只是覆写同一格子，物理上只写一次。

**列数组直写与倍增扩容**：行数组按列索引直写（`row[col] = value`），避免 Tablet.addValue 的逐格测点名哈希查找；宽设备（500 列）下行数组按 2 倍扩容，避免逐列 +1 的 O(n²) 复制。`MeasurementSchema` 有全局缓存（`SCHEMA_CACHE`），同名同类型测点全体设备共享同一对象，消除了每批次重建 schema 的分配开销（这两点是 5000 设备 × 500 测点压测后的针对性优化，详见压测报告 §7.8）。

**稀疏行与 null bitmap（含关键修复）**：批次内不同 ts 的行测点集合可以不同（30% 变化率场景的常态）。缺失格子通过 `tablet.bitMaps[col].mark(row)` 标记为 null；**TEXT 列还必须同时在 `Binary[]` 数组里填 `Binary.EMPTY_VALUE` 占位** —— tsfile 的 `Tablet.getTotalValueOccupation()` 遍历 TEXT 列时不检查 bitmap，数组中的 null 会导致序列化 NPE（此缺陷经交叉验证在 tsfile 1.1.3/IoTDB 1.3.7 中仍存在；生产事故 + 修复记录见 §11.1）。

**双触发 flush**：设备行数达到 `batch_size`（默认 1000）立即单独 flush（`flushOne`）；否则由 `flush_interval_ms`（默认 1000ms）周期性 flush 全部 pending（`flushAll`）。前者保证高频设备不憋大批次，后者约束端到端延迟上界 ≈ flush 间隔 + RPC 耗时。**flush 间隔是变化上报形态下的第一吞吐杠杆**：窗口越大、每设备 tablet 行数越多，服务端每点 CPU 成本越低——本机实测 50ms→2000ms 把拐点从 45 万点/秒推到 ≥68 万点/秒（+50%，详见 §10.4）；代价是崩溃丢数窗口同步变大。

**多设备合并 RPC**：稀疏场景下每设备每窗口往往只有 1~2 行，逐设备单发 RPC 会导致 RPC 数爆炸（每秒数千次）。`flushAll` 把多个设备的 Tablet 合并成一次 `insertAlignedTablets`，按两个上限切块：单次 RPC ≤ `500,000` cells 且 ≤ `1000` 设备（防止 thrift 消息过大）。这是 MQTT 宽设备压测后的第二个关键优化。

**背压**：每分片未落库点数（排队中 + 已合并进批次但尚未 flush）超过 `max_pending_per_shard`（默认 20 万）时，`addBatch()` 以 100µs 间隔自旋 park，阻塞上游（规则引擎线程）直到腾出空间。计数在 **flush 完成（成功或失败）时才释放**，因此即使 IoTDB 完全无响应，分片内存也被硬性约束在 `max_pending_per_shard` 以内（shards × 20 万 = 全局上界），不会缓冲无界增长直至 OOM。背压触发次数计入 `backpressureEvents` 指标并伴随 WARN 日志。`max_backpressure_wait_ms>0` 时再加一道硬上限，超时快速失败该点、释放调用线程。**注意**：单条消息点数可能大于分片水位，故按水位**分段入队**（各段共享同一 Future），否则 `pointCount > max_pending_per_shard` 会导致即使分片为空也永远满足不了背压条件而死等；构造期校验 `max_pending_per_shard > 0`。

**Future 语义（一条消息一个 Future）**：批量路径下每条遥测消息共享一个 `BatchCompletion`，在其全部点结算后完成——成功以"已接受 `dataPoints`"（扣除被丢弃的数据错误点）完成、基础设施失败则 `setException`（见 §4.4）。规则引擎的消息 ack（以及 pack timeout 统计）由此驱动 —— 写得慢会表现为规则引擎超时而不是丢数。单点 `add()` 仍可用，内部包装为 `addBatch(单点)`。

**可靠性取舍（重要）**：写缓冲是**纯内存**的，进程崩溃时未 flush 的点（上界 ≈ shards × max_pending_per_shard）会丢失。这是为极致吞吐做出的明确取舍；换取的是不需要任何本地 WAL。停机走 `@PreDestroy` → `stop()` 会把队列排干后再退出，正常发布不丢数。

### 4.4 失败处理（毒丸免疫 + 类型冲突恢复）

失败被分成两类，处理策略完全不同：**基础设施故障**（连接/超时/权限/磁盘）会**失败 Future**（上游 RETRY 队列可重放恢复）；**确定性数据错误**（非法 key、类型漂移/冲突）则**丢该点并成功结算消息**，绝不让 RETRY 策略把同一条 Kafka 消息变成永久毒丸（无限重放、lag 不消）。

**① 非法 key（保留字/空）**：在 DAO 层（`save`/`saveBatch`/`saveLatest` 三路一致）写入前拦截，丢弃该点、限流 WARN，消息按"已接受点数"成功结算（全非法则返回 0 / null）。

**② 批内类型漂移**：同批同 key 先 LONG 后 STRING 等 —— `DeviceBatch.add` 按首见类型建列，类型不符的点 `completion.drop(1, dataPoints)`（从成功数据点扣除、**不记失败**），限流 WARN，计入 `typeDriftPoints` 指标。

**③ 跨批类型冲突（服务端拒绝）**：某 key 与 IoTDB 已有序列类型不一致时，整设备 Tablet 被服务端拒（`507 ... data type of X is not consistent ...`）。此时**不**连坐整设备，而是 `recoverTypeConflict` 按 measurement 拆分逐列重试：只有被服务端**再次明确判定为类型冲突**的 measurement 才作丢点成功结算，同设备其余 key 照常落库。`isTypeConflict` 仅匹配 IoTDB 明确的类型不兼容错误串（已对 1.3.7 真实 SessionPool 异常实测校准，见 §12.1）；权限/磁盘/连接/超时等**绝不**被误判为可丢，仍失败 Future。

**④ 连接类系统故障**：多设备 chunk RPC 失败先降级为逐设备重试；连续 `MAX_RETRY_PROBES=3` 次**连接类**失败即判定系统性故障、快速失败剩余设备（避免逐设备超时拖垮 shard 线程）。类型冲突这类单设备"毒丸"不计入该阈值。

**⑤ 停机与背压**：停机后 `add` 拒收、入队后复核用 `queue.remove` 与消费端 `poll` 互斥（杜绝"已写库却报失败/size 双减为负"），保证每个 Future 必定完成（详见写缓冲单测）。背压硬上限 `max_backpressure_wait_ms>0` 时超时快速失败该点、释放调用线程。

> **一条消息一个 Future**：批量路径下每条遥测消息共享一个 `BatchCompletion`（`remaining` 倒计数 + `acceptedDataPoints`），所有点结算后才完成；期间任一基础设施错误 → 整条失败，仅数据错误 → 成功但扣减点数。规则引擎的消息 ack 由此驱动。

---

## 5. 读路径设计（历史查询）

读路径与写入**物理隔离**：读用独立连接池 `read_pool_size`（查询洪峰再大也占不到写连接，杜绝"读挤占写→flush 变慢→背压→Kafka lag"）；读线程池 `iotdb-read`（`iotdb.read_threads`，默认 2×CPU）用**有界队列**，满队后 **fast-fail**（`AbortPolicy` → 返回立即失败的 Future），**绝不在调用线程内联执行**（调用方是规则引擎/设备状态/订阅线程，内联会被查询阻塞至 `query_timeout_ms`，触发 pack timeout / offset 延迟）。慢查询（≥`read_slow_query_ms`）计入 `iotdbQuery.slow` 并 WARN。SQL 直接下推：

**原始查询**（`Aggregation.NONE`）：

```sql
select `key` from root.tb.DEVICE.u_xxx
where time >= {startTs} and time < {endTs}
order by time asc|desc [limit N]
```

**聚合查询**分两条路径，按 `AggregationParams.intervalType` 分派：

**① 固定毫秒窗（`MILLISECONDS`，绝大多数场景）**——利用 IoTDB 原生 GROUP BY，**一条 SQL**、聚合完全在存储侧完成：

```sql
select avg(`key`), max_time(`key`) from root.tb.DEVICE.u_xxx
group by ([{startTs},{endTs}),{interval}ms)
```

**② 自然日历窗（`WEEK`/`WEEK_ISO`/`MONTH`/`QUARTER`）**——月长不等、且有 DST 切换，不能近似成固定毫秒（月≠30 天、季≠90 天，否则窗口漂移）。改为按 TB 的 `intervalType + tzId` 用 `TimeUtils.calculateIntervalEnd` 逐窗计算真实边界后**逐窗查询**（与 SQL 后端 `AbstractChunkedAggregationTimeseriesDao` 同源同语义）：

```sql
select avg(`key`), max_time(`key`) from root.tb.DEVICE.u_xxx
where time >= {periodStart} and time < {periodEnd}   -- 每个自然窗口一次
```

日历聚合的窗口数通常很少（如一年按月=12 次），逐窗查询代价可接受；**常用的固定毫秒聚合仍是单 RPC，主路径性能不受影响**。两条路径的数据点 ts 均取窗口中点、`lastEntryTs` 均取 `max_time`（原始记录最大时间戳），与 SQL 后端契约一致。

| TB `Aggregation` | IoTDB 函数 |
|---|---|
| MIN / MAX | `min_value` / `max_value` |
| AVG / SUM / COUNT | `avg` / `sum` / `count` |

两个语义对齐细节：① 聚合结果时间戳取**窗口中点**（`ts + interval/2`），与 TB 其他存储实现一致；② 空窗口（Field 为 null）跳过不返回。`ReadTsKvQueryResult.lastTs` 取结果集中最大时间戳，供 TB 分页游标使用。

设备路径有 `ConcurrentHashMap` 缓存（`devicePathCache`），避免每个点重复拼串。

---

## 6. 最新值（Latest）设计

依托 IoTDB 原生 **LastCache**（内存级最新点缓存，随写自动维护）：

- `findLatest(key)` → `select last \`key\` from <device>`；`findAllLatest()` → `select last * from <device>`。返回行结构为 `Time | Timeseries | Value | DataType`，按 DataType 字符串还原为 TB `KvEntry`（`parseLastRow`）。
- **多 key 批量（`BatchedTimeseriesLatestDao`）**：`findLatest(keys)` 不再逐 key 发起 N 次 `SELECT LAST`，而是合并为 `select last \`k1\`,\`k2\`,... from <device>`，按 `latest_batch_size`（默认 500）分块。实体查询"设备数 × key 数"的查询放大由此消除（PCS/BMS 的 200~350 key 只产生一次 RPC）。契约与逐 key 路径**完全等价**：按请求顺序返回、每个请求 key 都有一个 entry、缺失 key 补 `StringDataEntry(key, null)` 占位。该接口仅 IoTDB 实现，`BaseTimeseriesService` 以 `instanceof` 门控，其他 latest 后端仍走原逐 key 循环。
  > 实测（IoTDB 1.3.7）：`select last k1,k2` 中若个别 key 序列不存在，服务端**正常返回已存在 key 的行、不整条报错**，故不会出现"个别缺失导致全部退化为空"的问题。
- `saveLatest()` 与历史 `save()` 走**同一个**写缓冲；历史+latest 双 IoTDB 时上层直接跳过 saveLatest（§2.3），单点写零额外成本。
- `removeLatest(query)`：读当前 last → 若落在删除区间则执行范围 delete → 若 `rewriteLatestIfDeleted` 则回查区间前最后一个点作为新 latest 返回（`readPreviousBefore`，`order by time desc limit 1`）。
- `findAllKeysByEntityIds` → `show timeseries <device>.**` 取叶子名（剥反引号）；`findAllKeysByDeviceProfileId` 返回空集（与 Cassandra latest DAO 行为对齐，该能力由 SQL 侧提供）。

查询不存在的设备/测点时 IoTDB 抛异常，DAO 捕获后按"空结果"处理（debug 日志），符合 TB 对新设备的预期。

---

## 7. 连接层与生命周期

`IotdbSessionPoolConfig` 持有**读、写两个物理隔离的 `SessionPool`**：

- `node_urls` 逗号分隔：1 个 = 单机，N 个 = 集群（客户端自动故障转移 + 可选 leader 重定向 `enable_redirection`）。
- **双池**：写池 `pool_size`（`getSessionPool()`，写入/建库/基准复用）与读池 `read_pool_size`（`getReadSessionPool()`，历史/latest 查询专用），互不挤占。均默认 2×CPU；Session 非线程安全，必须走池。
- **显式超时**：`connection_timeout_ms`（thrift socket 往返，含写 flush 单次 RPC）、`session_wait_timeout_ms`（取 Session 等待）、`query_timeout_ms`（慢查询被服务端 kill）、`max_retry_count`/`retry_interval_ms`。不配则用客户端默认（部分为 0=无限），IoTDB 卡住时会永久等待、pending 永不释放、背压永久阻塞上游——故必须给有限上限。
- **启动 fail-fast**：`create database` 走 `init_retries` 次重试（容忍容器晚就绪）；仅"数据库已存在(501)"静默确认，网络/认证/权限等真实故障重试耗尽即抛异常让 Spring 启动失败（不带病启动）。
- **全局 TTL**：`ttl_ms>0` 执行 `set ttl to <db>.**`（失败默认 ERROR、`ttl_fail_fast=true` 则阻断启动）；`ttl_ms=0` 启动主动 `unset ttl` 清除残留（"永不过期"语义真正成立）。数据过期由 IoTDB 原生完成（`cleanup()` 空实现，TB 的 SQL TTL 任务不适用）；**不支持数据级 per-tenant/entity TTL**（写路径会 WARN，见 §12.1）。
- **EDQS 兼容性硬拦截**：`database.ts_latest.type=iotdb` 与 EDQS 同步/API（`TB_EDQS_SYNC_ENABLED`/`TB_EDQS_API_SUPPORTED`）**不可共存**——IoTDB latest 用原生 LastCache、不维护 SQL `ts_kv_latest` 版本表，而 EDQS 全量初始化只从该表装载，会静默得到空/旧 latest。故启动即 fail-fast，错误信息给出解决办法（禁用 EDQS 或改用 sql/redis/cassandra latest）。仅在 `latest=iotdb` 时触发，其他 latest 后端不受影响。
- 关闭顺序：`IotdbWriteService.stop()` 先排干写缓冲 → 两个 `SessionPool.close()`。

依赖管理注意：`iotdb-session` 传递引入旧版 `antlr4-runtime`（1.3.7 的 iotdb-parent 仍锁 4.9.3）会与 Hibernate 6 所需的 4.13 冲突导致启动失败，已在 `dao/pom.xml` 从 `iotdb-session` 排除（提交 `e4f09946`；1.3.7 依赖树确认排除后 antlr 收敛到 4.13.0，无冲突）。

**版本升级说明（1.3.2 → 1.3.7）**：1.3.3 起 tsfile 从 IoTDB 拆分为独立 Apache 项目，包名 `org.apache.iotdb.tsfile.*` → `org.apache.tsfile.*`（其中 `TSDataType` 子路径由 `file.metadata.enums` 移至 `enums`，其余类子路径不变）。已迁移全部引用，API（`Tablet`/`Binary.EMPTY_VALUE`/`MeasurementSchema` 等构造与方法）完全兼容。68 实测：服务端 1.3.2→1.3.7 数据卷平滑加载（100 万序列元数据完整），2000 设备稳态 ~30 万点/秒零失败零背压，性能无退化。

**⚠️ 服务端部署必改项（1.3.7 安全加固 breaking change）**：1.3.7 把 `dn_rpc_address` 默认值从 `0.0.0.0` 改为 `127.0.0.1`，IoTDB 只监听环回。容器化/跨机部署下若不显式设 `dn_rpc_address=0.0.0.0`（集群为本机对外 IP），客户端会持续报 `Cluster has no nodes to connect`、写入全部失败——升级时实测踩过此坑。部署模板已带该项；调优/排查详见《IoTDB参数调优指南》§3.5。另注：1.3.7 默认 `iotdb-system.properties` 精简，多数调优参数不再列出但**参数名均仍有效**，需从 `iotdb-system.properties.template` 拷贝或用 Docker `environment` 传入。

---

## 8. 配置参考（`thingsboard.yml` → 环境变量）

启用开关：

```bash
export DATABASE_TS_TYPE=iotdb          # 历史存储
export DATABASE_TS_LATEST_TYPE=iotdb   # 最新值（或保持 redis）
# ⚠️ latest=iotdb 时必须关闭 EDQS 同步/API（否则启动 fail-fast，见 §7）
export TB_EDQS_SYNC_ENABLED=false
export TB_EDQS_API_SUPPORTED=false
```

`iotdb.*` 配置段：

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `node_urls` | `IOTDB_NODE_URLS` | `127.0.0.1:6667` | 逗号分隔；单机/集群同构 |
| `username` / `password` | `IOTDB_USER` / `IOTDB_PASSWORD` | root/root | |
| `database` | `IOTDB_DATABASE` | `root.tb` | 所有序列的根 |
| `pool_size` | `IOTDB_POOL_SIZE` | 0=auto(2×CPU) | **写**连接池上限 |
| `read_pool_size` | `IOTDB_READ_POOL_SIZE` | 0=auto(2×CPU) | **读**连接池上限（与写物理隔离，查询洪峰不占写连接）。连接数≈写池+读池，连接受限时调小 |
| `enable_redirection` | `IOTDB_ENABLE_REDIRECTION` | true | 集群写重定向；单机无害 |
| `enable_compression` | `IOTDB_ENABLE_COMPRESSION` | false | thrift RPC 压缩（CPU 换带宽） |
| `ttl_ms` | `IOTDB_TTL_MS` | 0=永不过期 | 全局(库级)原生 TTL；0 时启动主动 unset 残留 TTL。不支持数据级 TTL（见 §12.1） |
| `ttl_fail_fast` | `IOTDB_TTL_FAIL_FAST` | false | 全局 TTL 设置失败是否阻断启动。true=把 TTL 当磁盘硬约束；仅 ttl_ms>0 有意义 |
| `read_threads` | `IOTDB_READ_THREADS`(隐式) | 0=auto(2×CPU) | 读线程池核心线程数（历史与 latest 各一个） |
| `read_queue_capacity` | `IOTDB_READ_QUEUE_CAPACITY` | 0=auto(threads×64) | 读线程池有界队列容量。满队后 **fast-fail**（立即失败、绝不内联执行阻塞调用线程） |
| `read_slow_query_ms` | `IOTDB_READ_SLOW_QUERY_MS` | 1000 | 查询耗时≥此值计入 `iotdbQuery.slow` 并 WARN |
| `latest_batch_size` | `IOTDB_LATEST_BATCH_SIZE` | 500 | 单次 `SELECT LAST` 最多携带的 key 数（多 key latest 批量查询，见 §6）。超出则在同一个有界读任务内分批，避免 SQL/响应无限膨胀 |
| `init_retries` | `IOTDB_INIT_RETRIES` | 5 | 启动建库/连通探测重试次数；耗尽仍失败则 fail-fast（不带病启动） |
| `init_retry_interval_ms` | `IOTDB_INIT_RETRY_INTERVAL_MS` | 3000 | 上项重试间隔 |
| `connection_timeout_ms` | `IOTDB_CONNECTION_TIMEOUT_MS` | 15000 | thrift socket 往返上限（含写 flush 单次 RPC） |
| `session_wait_timeout_ms` | `IOTDB_SESSION_WAIT_TIMEOUT_MS` | 10000 | 从连接池获取 Session 的等待上限 |
| `max_retry_count` | `IOTDB_MAX_RETRY_COUNT` | 3 | 客户端对可重试(连接类)错误的重试次数 |
| `retry_interval_ms` | `IOTDB_RETRY_INTERVAL_MS` | 1000 | 上项重试间隔 |
| `query_timeout_ms` | `IOTDB_QUERY_TIMEOUT_MS` | 60000 | 查询超时（慢查询超时被服务端 kill，避免读线程无限占用） |
| `write.shards` | `IOTDB_WRITE_SHARDS` | 0=auto(CPU) | 写分片/flush 线程数 |
| `write.batch_size` | `IOTDB_WRITE_BATCH_SIZE` | 1000 | 单设备行数触发 flush |
| `write.flush_interval_ms` | `IOTDB_WRITE_FLUSH_INTERVAL_MS` | 1000 | 攒批窗口 = 入库延迟上界。秒级实时性可接受时 1000~2000（吞吐 +50%），亚秒实时性才用 50~500 |
| `write.max_pending_per_shard` | `IOTDB_WRITE_MAX_PENDING_PER_SHARD` | 200000 | 背压硬水位（未落库点数/分片）。须 ≥ 2~3 × [总点速率 ÷ shards × flush 间隔秒数]，与上一项联动调整 |
| `write.max_backpressure_wait_ms` | `IOTDB_WRITE_MAX_BACKPRESSURE_WAIT_MS` | 0=无限等 | 背压等待硬上限。0=如实反压到 Kafka(可恢复不丢数)；>0=超时快速失败该点、释放调用线程 |
| `write.stats_interval_ms` | `IOTDB_WRITE_STATS_INTERVAL_MS` | 10000 | 写缓冲统计日志周期；0=关闭 |
| `enable_auto_fetch` | `IOTDB_ENABLE_AUTO_FETCH` | true | 集群：后台拉取可用 DataNode 用于重试/故障转移 |

### 8.1 写缓冲可观测性

写缓冲通过 TB 标准 `StatsFactory` 注册 Micrometer 指标（前缀 `iotdbWriteBuffer`）：`pendingPoints`（当前未落库点数）、`addedPoints` / `writtenPoints` / `failedPoints`（累计）、`flushRpcs` / `rpcFailures`、`backpressureEvents`、`typeDriftPoints`（因类型漂移/冲突被丢弃的点，持续非零说明设备上报类型不稳定，需修数据源而非 IoTDB）。读路径另有 `iotdbQuery.*`（total/errors/slow/latency）。同时每 `stats_interval_ms`（默认 10s）输出一行统计日志（含 `typeDrift`），空闲窗口不打；`rpcFailures/backpressure/typeDrift` 任一非零伴随降级 WARN。

以 68 环境 2000 设备 × 500 测点 × 30%/s 稳态压测的真实日志为例（采集于 flush=50ms 时期；现默认 1000ms 下同负载的 `rpcs` 会低一个数量级、单 RPC 点数相应变大，判读方法不变）：

```
[IoTDB] write buffer: pending [3486] added [2997513] written [3000792] failed [0]
        rpcs [5908] rpcFailures [0] backpressure [0] flushAvg [6ms] flushMax [206ms]
```

各字段含义（`pending` 是打点瞬时值，其余均为**本窗口增量**）：

| 字段 | 本例 | 含义与判读 |
|---|---|---|
| `pending` | 3486 | 当前尚未落库的点（排队 + 已合并未 flush，即背压计数的分子）。折算成流量时长最直观：3486 ÷ 30 万点/秒 ≈ 0.012s，等于零积压 |
| `added` | 2997513 | 窗口内新接收的点数。10s 300 万 = 30 万点/秒，与 2000×500×30% 理论值吻合 |
| `written` | 3000792 | 窗口内成功落库的点数。**略大于 added 是正常现象**：包含上个窗口末尾 pending、本窗口才 flush 完成的点 |
| `failed` | 0 | 窗口内写失败的点数（flush 异常时整批计入，配合 ERROR 堆栈定位） |
| `rpcs` | 5908 | 窗口内 insertAlignedTablet(s) 调用次数。交叉验证：≈591 次/s，接近 32 分片 × 20 次/s（50ms flush）= 640 的理论上限；平均每 RPC ≈ 508 点，说明多设备合并在生效 |
| `rpcFailures` | 0 | 窗口内 RPC 异常次数 |
| `backpressure` | 0 | 窗口内 `add()` 因分片满而阻塞生产者的次数。非零即规则引擎线程已被阻塞 |
| `flushAvg` | 6ms | 窗口内单次 RPC 平均耗时 |
| `flushMax` | 206ms | 窗口内最慢一次 RPC 耗时（读后归零重新累计）。偶发尖刺多为 IoTDB memtable 刷盘/小 GC，远小于规则引擎 pack 超时（默认 2s）即无碍 |

**健康形态**：pending 折算 < 1s 流量、failed/rpcFailures/backpressure 恒为 0、flushAvg 个位数~几十 ms。
**劣化前兆**（按出现顺序）：① `flushAvg` 持续上百 ms 且 `flushMax` 频繁过秒 → IoTDB 变慢，先查其 GC（`GcTimeAlerter`）与磁盘 iowait；② `pending` 逐窗口单调上涨 → 写入速度已跟不上摄入；③ `backpressure > 0`（伴随 WARN）→ 已在阻塞规则引擎，pack 超时雪崩在即——4000 设备堆耗尽雪崩（§10.1）前正是这个演进顺序。

---

## 9. 部署

- 单机：`docker/iotdb/docker-compose-standalone.yml`（含内存/Region 调优参数说明与自定义内存入口）。
- 集群：`docker/iotdb/docker-compose-cluster.yml`（3 ConfigNode + 3 DataNode）。TB 侧只需把 `IOTDB_NODE_URLS` 填 3 个 DataNode 地址。
- IoTDB 服务端第一关键参数是 **DataNode 堆内存**（决定活跃序列容量上限，见 §10）。⚠️ 注意：`MAX_HEAP_SIZE`/`MAX_DIRECT_MEMORY_SIZE` 在 1.3.x 官方镜像中是**无效变量**（脚本不读取，实测确认），正确做法是仓库模板的 `DATANODE_MEMORY_SIZE`/`CONFIGNODE_MEMORY_SIZE`（或官方 `MEMORY_SIZE`，堆=预算×档位系数）——机制、公式与验收方法详见《IoTDB-1.3.7生产部署最佳调优配置》§1。`max open files 65535`；数据盘强烈建议 SSD/NVMe，避免与 swap 同盘。

---

## 10. 性能实测与容量结论

### 10.1 实测数据汇总

| 场景 | 环境 | 结果 |
|---|---|---|
| 独立 harness 纯写 | 8C/16G，IoTDB 4G 堆 | 稳态 ~3.1M 点/秒（详见压测报告 §7.7） |
| 端到端 2000 设备×500 测点×30%/s（30 万点/秒） | 32C/64G 单机全家桶¹，IoTDB 28G 堆，SATA HDD | **长期稳定**，零错误零超时 |
| 端到端 4000 设备（60 万点/秒），flush=50ms 旧默认 | 同上 | 稳态运行 **~50 分钟后雪崩**：IoTDB 28G 堆耗尽 → GC 占比 100% → 规则引擎 100% pack 超时 |

¹ 全家堂 = iotcloud(TB) + Kafka + PostgreSQL + IoTDB 同机部署。

上表为 7/10 端到端基线（当时 flush=50ms）。此后的系统性容量数据见两份专项报告，结论已按 flush=1000~2000ms 新默认值刷新：

- **《IoTDB四机容量实测矩阵》**（16C/30G HDD → 160C/377G SSD 四档硬件 × flush 档位的最大可持续设备数矩阵，DAO 直连口径）：71(160C/SSD) ≥17500 台、72(112C/SSD) 拐点 12600~15000、68(32C/HDD) 拐点 7200~8400、183(16C/HDD) ~2400；**长跑保守水位**按"每 100 万活跃序列 ≥14G 堆"折算为 9000/6800/4000/1400 台。
- **《IoTDB全机械盘六类负载容量报告》**：固定变化集合比随机变化容量高 20~40%（活跃序列数是第一约束）；错峰全量快照对吞吐影响 <1% 但会把固定模式的活跃序列拉回全量。

**结论（全家桶单机 32C/64G/HDD、30% 随机变化模型）：flush=50ms 旧默认下安全水位约 2500 台；flush=1000ms+ 生产默认下存储链路可支撑 4000 台以上（长跑水位受 IoTDB 堆约束，28G 堆 ≈ 4000 台 = 200 万活跃序列），生产按四机矩阵"长跑保守水位"选型。**

约束排序：① IoTDB 堆内存 —— 活跃序列数（=设备×测点，与变化率无关）决定 memtable 开销，100 万序列 28G 堆可承载、200 万不可持续；② 主机总内存（TB 默认堆吃 1/4 物理内存，挤占 IoTDB）；③ CPU（60 万点/秒时 IoTDB 21 核 + TB 6.3 核 ≈ 28/32）；④ HDD（写入量本身很小——LZ4 压缩比 ~4.5，落盘仅 ~5MB/s——但与 swap/合并同盘时会放大故障）。

提升路径（按性价比）：TB 显式设 `-Xmx8g`、省出内存给 IoTDB 堆加到 36–40G（预计 4000 台可稳）→ IoTDB 独立机器（预计 5000–6000 台）→ 数据盘换 SSD → 3C3D 集群。

### 10.2 新设备首写风暴（预期行为）

序列自动创建意味着 N 台新设备首次上线会瞬间触发 N×测点数 的 schema 创建（4000×500=200 万条，实测持续 ~2.5 分钟）。期间写延迟秒级、规则引擎 pack 超时告警刷屏，但**实测数据不丢**（写请求已入缓冲，最终全部落库；超时只是 ack 晚于阈值）。规避手段：分批接入新设备，或接入前预创建序列。

### 10.3 配套调参建议（TB 侧）

- Main 队列 `pack_processing_timeout` 默认 2000ms 偏紧，大规模接入期建议 10–30s（注意需大于 `flush_interval_ms` + flush 耗时，否则稳态就会误报超时）。
- 默认策略 `SKIP_ALL_FAILURES` 下超时/失败消息**不重发**。本实现的写路径异步最终完成，常规超时不丢数；但若追求强一致可改 `RETRY_FAILED_AND_TIMED_OUT` —— IoTDB 对同 `(device, ts, key)` 的重复写是幂等覆盖，重试无副作用。

### 10.4 攒批窗口实验（flush_interval_ms 的吞吐杠杆）

本机（M1 Max，Docker VM 8 核/31G，IoTDB 16G 堆）用容量压测的限速节拍模式实测（当时位于
`dao` 下的 `IotdbCapacityBenchmark`，现已统一收敛至独立的 `iotdb-benchmark/` 模块，见 §2.2），
负载模型同 §10.1（500 测点、30%/s 变化、300s 错峰全量快照）：

| flush_interval | 3600 台（54.4 万点/秒） | 说明 |
|---|---|---|
| 50ms | ❌ 340s 后崩溃 | IoTDB CPU 8 核打满 |
| 500ms | 稳态可持续（冷启动窗口除外） | |
| 1000ms | ✅ 零 overrun | CPU 峰值降至 ~600% |
| 2000ms | ✅ 零 overrun | 继续推：**4000 台 ✅、4500 台（68 万点/秒）✅ 零 overrun** |
| 3000ms | ✅ 零 overrun | 与 2000ms 无显著差异 |

结论：窗口 50ms→2000ms 使同硬件拐点 **+50% 以上**（45 万 → ≥68 万点/秒）。原理：变化上报形态下每序列每次写入深度极浅（§1 R1），拉大窗口让每设备 tablet 行数增加、RPC 摊销变好。1000ms 以上收益趋平，按业务延迟容忍度在 1000~2000 之间取值即可。两参数联动公式见 `thingsboard.yml` iotdb.write 段内注释。

---

## 11. 故障案例与修复记录

### 11.1 稀疏 TEXT 列 flush NPE（已修复，提交 `9444656e`）

- **现象**：压测启动积压期大量 `[IoTDB] flush failed ... Cannot invoke "Binary.getLength()" because "binaries[rowIndex]" is null`（JIT OmitStackTraceInFastThrow 后退化为 `null`），并引发规则引擎重试风暴自放大。
- **根因**：tsfile 的 `Tablet.getTotalValueOccupation()`（`insertTablet` 序列化前计算缓冲区大小）遍历 TEXT 列 `Binary[]` 时**不检查 null bitmap**（1.3.2 与升级后的 1.1.3/1.3.7 均有此缺陷）。本实现为性能直写列数组、缺失格子只标 bitmap，TEXT 列留下的 null 触发 NPE。触发条件 = 同设备批次 ≥2 个时间戳且字符串测点只在部分行出现 —— 当时默认 flush=50ms，稳态每设备每窗口仅 1 行故不触发，积压/重试期（多行批次）必触发，因此表现为"开始一段时间大量报错、之后自愈"。注意：现默认 flush=1000ms 下稳态批次天然多行，若无此修复该 NPE 将成为常态而非偶发——修复的重要性反而更高。
- **修复**：`toTablet()` 对缺失 TEXT 格子在标 bitmap 之外填 `Binary.EMPTY_VALUE` 占位；同时 flush 失败日志改为输出完整堆栈。
- **回归测试**：`IotdbTimeseriesWriteBufferTest#sparseTextColumnMustSerialize` 构造两行稀疏批次，修复前精确复现生产 NPE、修复后通过。

### 11.2 antlr4-runtime 版本冲突（已修复，提交 `e4f09946`）

`iotdb-session` 传递引入旧版 `antlr4-runtime` 与 TB 依赖冲突导致应用启动失败，`dao/pom.xml` 中排除。

### 11.3 宽设备稀疏写入两大性能瓶颈（已修复，提交 `ecaa855b`）

5000 设备 × 500 测点 MQTT 实测发现：① 行数组逐列扩容 O(n²) 复制 → 改倍增扩容 + 全局 `MeasurementSchema` 缓存；② 稀疏批次逐设备单发 RPC 导致 RPC 爆炸 → 多设备合并 `insertAlignedTablets`（≤50 万 cells / ≤1000 设备每次）。

---

## 12. 已知限制与后续工作

| 项 | 说明 | 优先级 |
|---|---|---|
| 内存缓冲无持久化 | 进程崩溃丢失未 flush 的点（上界 shards×200k）；正常停机不丢 | 接受（设计取舍） |
| 同一 key 类型漂移 | 使用约束，见 [§12.1](#121-类型稳定性使用约束重要)；实现已做到"丢冲突点、不连坐、不毒丸" | 使用约束 |
| ~~缓冲无可观测性指标~~ | ✅ 已补齐：Micrometer 指标 + 周期统计日志（§8.1），背压语义同步修正为硬性内存上界 | 已完成 |
| ~~`enable_auto_fetch` 未接线~~ | ✅ 已接线到 `SessionPool.Builder.enableAutoFetch()` | 已完成 |
| ~~聚合忽略 `IntervalType`/时区~~ | ✅ 已实现自然周/月/季日历聚合（§5），与 SQL 后端 `TimeUtils.calculateIntervalEnd` 同源；固定毫秒仍单 RPC | 已完成 |
| ~~实体查询 N×K `SELECT LAST` 放大~~ | ✅ 已消除：`BatchedTimeseriesLatestDao` 单设备一次多 key `select last`（§6），按 `latest_batch_size` 分块 | 已完成 |
| ~~无自动化集成测试~~ | ✅ 已补 `IotdbIntegrationIT`（Testcontainers 真实 IoTDB 1.3.7，§13） | 已完成 |
| ~~压测工具位于生产源码树~~ | ✅ 已移除 `dao/src/main` 下的 benchmark 类（含 `delete database`），统一由独立 `iotdb-benchmark/` 模块承担 | 已完成 |
| `findAllKeysByDeviceProfileId` 空实现 | 与 Cassandra latest 行为一致，按 profile 列 key 由 SQL 侧提供 | 低 |
| 大规模新设备接入的 schema 风暴 | 见 §10.2，可选做序列预创建工具 | 中 |
| IoTDB 2.x Table Model | 1.3 树模型满足当前需求；2.x 需要关系语义时再评估 | 观望 |

### 12.1 类型稳定性使用约束（重要）

**核心约束：同一测点 key 在其整个生命周期、以及在同 profile 的所有设备之间，数据类型必须保持稳定**（要么一直是数值，要么一直是布尔，要么一直是字符串/JSON，不可混用）。这是所有时序数据库的通用建模准则，在 IoTDB 后端下有两处具体表现，均为**使用约束、非实现缺陷**：

**（1）类型漂移/冲突：丢冲突点、不连坐、不毒丸（实现已隔离到单 measurement）**

若某 key 先以数值写入（IoTDB 建成 `INT64` 序列），之后改以字符串写入，属确定性数据错误（IoTDB 序列类型不可变，重试永远失败）。实现分三层隔离，**只丢真正冲突的点、同设备/同消息其余 key 照常落库、消息成功结算不形成 Kafka 毒丸**：

- **批内漂移**：`DeviceBatch.add` 按首见类型建列，类型不符的点 `completion.drop`（扣减成功点数、不记失败），不连带同批其他 key。
- **跨批冲突（服务端拒绝）**：整设备 Tablet 被拒（`507 ... data type of X is not consistent ...`）时，`recoverTypeConflict` 按 measurement 拆分逐列重试——只有被服务端**再次判定为类型冲突**的列作丢点成功结算，其余列照常写入。权限/磁盘/连接/超时等**绝不**当作可丢，仍失败 Future（可 RETRY 恢复）。
- **可观测**：丢弃点计入 `typeDriftPoints` 指标 + 限流 WARN；持续非零即提示修数据源。
- **正确用法**：保证每个 key 类型稳定。若确需变更类型，换新 key 名，或先删该设备下该序列再以新类型写入。
- **⚠️ 版本耦合**：`isTypeConflict` 靠匹配 IoTDB 类型不兼容**错误串**（`data type of ... is not consistent` 等）判定，已对 **1.3.7 真实 SessionPool 异常实测校准**。升级 IoTDB 版本后需重验错误串——若不再匹配，类型冲突会退化为"消息失败"（优雅降级，RETRY 下变毒丸，但不会崩），需更新匹配模式。**缓解**：`IotdbIntegrationIT.persistedTypeDriftIsDroppedWithoutFailingTheMessage` 在真实容器中覆盖该路径，**升级 IoTDB 后跑一次集成测试即可捕获**（见 §13）。

**（2）混合类型下的"按遥测值排序"只保证全序、不做数值/字典的语义融合**

实体查询支持"按某遥测 latest 值排序"。当同一 key 在不同设备上混有数值与非数值（如个别设备上报 `"error"` 之类字符串哨兵值）时，比较器采用**确定的全序**：所有可转数值的值归为一类按数值大小排序，且**整体排在**非数值之前；非数值之间按字符串字典序。

- 为什么必须是全序：比较器若不满足传递性，JDK 的 `TimSort` 会抛 `Comparison method violates its general contract` 使**整个实体查询崩溃**（波及全部实体，而非仅异常设备）。因此这一条做了**防御性实现**（全序比较器，永不抛异常），即使用户混用类型也不会崩。
- 使用侧建议：用于排序/过滤的遥测 key 应保持类型一致；混合类型下的排序结果虽稳定，但"数值段整体在前、字符串段在后"的顺序未必符合业务直觉。

---

## 13. 测试与验证手段

| 手段 | 位置 | 用途 |
|---|---|---|
| 单元测试 | `dao/src/test/.../iotdb/*Test.java` | 写缓冲/DAO/latest/连接层/schema 的逻辑与故障路径回归（含并发记账、停机竞态、毒丸隔离、边界语义）。运行：`mvn -pl dao test -Dtest='Iotdb*Test'`（需 JDK 17） |
| **集成测试（真实 IoTDB 容器）** | `dao/src/test/.../iotdb/IotdbIntegrationIT.java` | Testcontainers 拉起真实 `apache/iotdb:1.3.7-standalone`，验证批量 latest（特殊字符 key / JSON 保真 / 缺失值占位）与**持久化类型漂移被丢弃且不失败消息**（类型冲突恢复）。运行：`mvn -pl dao -Piotdb-integration verify`；只跑 IT 可用 `mvn -pl dao -Piotdb-integration failsafe:integration-test failsafe:verify`。**IoTDB 升级后必须跑**——它会捕获服务端错误串变化导致的 `isTypeConflict` 失效（见 §12.1） |
| 独立写压测 | `iotdb-benchmark/`（`IotdbWriteBenchmark`，独立模块） | 复刻写入策略的纯 IoTDB 压测，找存储侧上限；容量评估（paced 节拍 / max 吞吐）亦由该模块承担。**压测工具一律不放在 `dao/src/main`**，避免含 `delete database` 的破坏性代码进入生产制品 |
| 端到端验证 | 真实 MQTT 上送 + 观察 `TbRuleEngineConsumerStats` 与 `[IoTDB] flush` 日志 | 容量评估（§10 数据来源） |

运维排查速查：`docker exec iotdb /iotdb/sbin/start-cli.sh -e "select count(\`key\`) from <device> where time >= ... "` 核对落库；`GcTimeAlerter` 日志出现即堆容量临界；规则引擎 `timeoutMsgs` 持续非零而 Kafka lag 不涨 = 写延迟超过 pack 超时（先查 IoTDB GC）。
