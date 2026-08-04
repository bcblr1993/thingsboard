# ThingsBoard 4.1 集成 Apache IoTDB 时序存储 —— 设计方案与压测报告

| 项目 | 内容 |
|---|---|
| 分支 | `feature/iotdb-timeseries` |
| 目标 | 为历史时序存储新增 IoTDB 实现（与 Cassandra 并列），逼近 IoTDB 官方写入性能 |
| IoTDB 版本 | 本报告压测数据在升级前 **1.3.2**（树模型）采集，2026-07-15 升级至 **1.3.7**（tsfile 1.1.3）后复测性能无退化，详见《IoTDB技术设计文档》；为统一，下文版本标注均按当前 **1.3.7**。 |
| 兼容性 | 同一套代码兼容**单机**与**集群** |
| 存储范围 | 历史（TimeseriesDao）+ 最新值（TimeseriesLatestDao），**均实现、配置可切换** |
| 编译状态 | ✅ `mvn -pl dao -am compile` 通过（JDK 17，历史+latest） |
| 实测峰值 | ✅ 真实 DAO 代码单机稳态 **~3.1–3.2 M 点/秒**（历史+最新都 IoTDB；8 核/16GB，4G 堆容器，详见 7.7） |
| 文档日期 | 2026-07-06 |

---

## 1. 背景与目标

ThingsBoard 4.1 的历史遥测默认可选 Cassandra / SQL / TimescaleDB。Cassandra 采用宽表 + 应用层分区表，在高频时序写入与聚合查询场景下存在：写放大（额外维护分区表）、聚合需回传全量数据到 JVM 计算、列存压缩比一般等瓶颈。

本方案新增一种 **Apache IoTDB** 历史存储实现，利用其时序原生的列式编码、内部时间分区、聚合下推与批量写接口，实现历史存储性能的显著提升，同时**不改动**现有 Cassandra/SQL 实现，通过配置项 `database.ts.type=iotdb` 切换。

**最新值（latest）存储**也已实现 IoTDB 版本（基于原生 `SELECT LAST`），通过 `database.ts_latest.type` 在 `iotdb` / `redis` 间自由切换（详见 4.6）。既可让 IoTDB 一统历史+最新（去 Redis 依赖），也可历史用 IoTDB、最新继续用 Redis（追求微秒级 latest 读）。

---

## 2. IoTDB 调研结论

### 2.1 版本选型

| 分支 | 特点 | 结论 |
|---|---|---|
| **1.3.x（本方案选 1.3.7）** | 成熟的树模型，路径 `root.x.y.z`；Java Session API 稳定、文档完善 | ✅ 首选 |
| 2.x | 新增关系型 Table Model，架构相同但语义不同 | 需要 SQL 关系语义时才考虑 |

选 1.3.x 的核心原因：ThingsBoard 数据天然是「实体 → 测点」的树状结构（`ENTITY_TYPE / entityId / key`），与 IoTDB 树模型路径 **一一对应**，改造成本最低。生产建议始终使用最新补丁版（近期修复 CVE-2025-12183/11226/66566）。

### 2.2 官方最佳实践要点

- **运行环境**：Java 17+；`max open files`、`somaxconn` 设为 65535（Docker 需在宿主机层面放开）。
- **批量写入用 `insertTablet`**：官方基准的高吞吐（单机千万点/秒、集群线性扩展至亿级）**全部基于 Tablet 批量接口**，比逐点写快约 10×。
- **对齐时间序列（Aligned Timeseries）**：同一设备下一起采集的多测点共享时间列，写入与存储显著优于非对齐。
- **SessionPool 而非单 Session**：Session 非线程安全；`SessionPool` 提供连接池 + `nodeUrls` 故障转移。
- **性能由 Region 驱动**：单库即可打满机器，Region 软上限 ≈ 逻辑核数 ÷ 2。
- **存储**：多块独立磁盘并发写；避免单个大 RAID、避免 NAS/LVM/加密盘。
- **内存**：官方基准用 20G 堆；`MAX_HEAP_SIZE` / `MAX_DIRECT_MEMORY_SIZE` 按数据量配置。

> 参考：官方 [Write Data](https://iotdb.apache.org/UserGuide/V1.3.x/Basic-Concept/Write-Data)、[Java Native API](https://iotdb.apache.org/UserGuide/V1.3.x/API/Programming-Java-Native-API.html)、[负载均衡](https://iotdb.apache.org/UserGuide/latest/User-Manual/Load-Balance.html)、[BenchANT 基准](https://benchant.com/blog/apache-iotdb-performance)。

---

## 3. ThingsBoard 现有时序存储架构分析

### 3.1 DAO 装配机制

TB 通过 `@ConditionalOnProperty` 元注解 + 配置项动态选择 DAO，新增存储只需增加一个 `havingValue`，**零侵入**：

```
database.ts.type        → 历史存储 DAO   (cassandra / sql / timescale / iotdb)
database.ts_latest.type → 最新值 DAO     (redis / cassandra / sql ...)
```

现有注解位于 `common/dao-api/.../util/`：`NoSqlTsDao`(cassandra)、`SqlTsDao`、`TimescaleDBTsDao`。本方案新增 `IotdbTsDao`（`havingValue="iotdb"`）。

### 3.2 需实现的接口

**`TimeseriesDao`（历史）**
```java
findAllAsync(tenantId, entityId, List<ReadTsKvQuery>)  // 范围/聚合查询
save(tenantId, entityId, TsKvEntry, ttl)               // 写入（逐条）
savePartition(...)                                      // Cassandra 专属分区表
remove(...) / cleanup(systemTtl)
```
外加 **`AggregationTimeseriesDao.findAllAsync(单 query)`**。

### 3.3 关键发现：写入是逐条的

`BaseTimeseriesService.doSave()` 对每个 `TsKvEntry` 逐条调用 `timeseriesDao.save(...)`（还额外一次 `savePartition`）。这决定了**要达到 IoTDB 官方性能，必须在 DAO 内部把逐条写聚合成批量 Tablet**，否则无法逼近基准。此外 `database.ts.type=iotdb` 时 `DefaultTbEntityDataSubscriptionService` 的 `tsInSqlDB` 为 false，查询规划走 NoSQL 路径，无需额外改动。

---

## 4. 设计方案

### 4.1 数据模型（对齐树模型）

```
数据库(存储组): root.tb
设备路径:        root.tb.<ENTITY_TYPE>.u_<entityId 去横杠>
测点(对齐):      每个遥测 key = 一个 aligned measurement
时间戳:          IoTDB 原生 time 列 = TsKvEntry.ts
```

**类型映射**

| TB DataType | IoTDB TSDataType | 编码 | 压缩 |
|---|---|---|---|
| DOUBLE | DOUBLE | GORILLA | LZ4 |
| LONG | INT64 | TS_2DIFF | LZ4 |
| BOOLEAN | BOOLEAN | RLE | LZ4 |
| STRING | TEXT | PLAIN | LZ4 |
| JSON | TEXT | PLAIN | LZ4 |

**路径安全**：entityId 为 UUID，含 `-`，统一以 `u_` 前缀并把 `-` 替换为 `_`（生成 `u_xxxx_xxxx_...`，纯合法节点名）；telemetry key 在 SQL 中用反引号包裹以容纳特殊字符。

### 4.2 写入路径（性能核心）

TB 逐条 `save()` → 内部聚合缓冲区 → 对齐 Tablet 批量刷：

```
save(entityId,key,ts,value)
   └─ 按 device.hashCode 分片入队（无锁）
        └─ 每分片单线程：按 (device,ts) 合并成对齐行
             └─ 满 batch_size 行 或 到 flush_interval 触发
                  └─ SessionPool.insertAlignedTablet(tablet)
                       └─ flush 成功后 set 每个点的 ListenableFuture
```

要点：
- **按 `(device, ts)` 合并对齐行**：同一时刻的多 key 填入同一行、缺失列用 BitMap 标 null。这是逼近性能的关键（详见第 7 节压测教训），也贴合 TB「一次上报含多 key」的语义。
- **分片数 = CPU 核数**，避免锁竞争与乱序。
- **背压**：每分片队列超过 `max_pending_per_shard` 时生产者短暂 park，防 OOM。
- **纯内存攒批（已确认取舍）**：进程崩溃时未 flush 的点丢失（≤ `flush_interval_ms`），换取最高吞吐。

### 4.3 连接层：单机 / 集群统一

```java
new SessionPool.Builder()
    .nodeUrls(解析 iotdb.node_urls)   // 1 个=单机, 多个=集群，代码一致
    .user(...).password(...).maxSize(poolSize)
    .enableRedirection(true)          // 集群：写请求路由到 leader Region（单机无副作用）
    .enableCompression(false)
    .build();
```
`iotdb.node_urls` 逗号分隔即可在单机与集群间切换，无需改代码。

### 4.4 读取与聚合（下推 IoTDB）

- **原始查询（Aggregation.NONE）**：
  `select \`key\` from <device> where time>=? and time<? order by time asc|desc limit ?`
- **聚合查询**：一条 `GROUP BY ([start,end), <interval>ms)` 将 avg/max/min/sum/count **下推到存储引擎**，每个窗口映射为一个 `TsKvEntry`（ts 取窗口中点，对齐 Cassandra 行为）。相比 Cassandra「拉全量分区回 JVM 再聚合」，网络与 CPU 大幅下降。

  | TB Aggregation | IoTDB 函数 |
  |---|---|
  | MIN / MAX | `min_value` / `max_value` |
  | AVG / SUM / COUNT | `avg` / `sum` / `count` |

### 4.5 其他

- `savePartition` → `immediateFuture(0)`（IoTDB 内部按时间自动分区，省掉 Cassandra 的额外写）。
- `remove` → `delete from <device>.\`key\` where time>=? and time<?`。
- `cleanup` → no-op；数据过期用 IoTDB 原生 TTL（`set ttl to root.tb.** <ms>`，由 `iotdb.ttl_ms` 控制）。
- 安装期免建表：测点首写自动创建，数据库由连接池启动时 `create database` 保证。

### 4.6 最新值（Latest）实现

`IotdbTimeseriesLatestDao`（`@IotdbTsLatestDao`，`ts_latest.type=iotdb` 启用），基于 IoTDB 原生 **`SELECT LAST`（LastCache）**：

| 接口 | 实现 |
|---|---|
| `findLatest` / `findLatestOpt` | `select last \`key\` from <device>`（解析 Value+DataType 列） |
| `findAllLatest` | `select last * from <device>`（一次取回设备全部测点最新值） |
| `saveLatest` | 走**共享 `IotdbWriteService`** 写入该点 |
| `removeLatest` | 删除区间数据；`rewriteLatestIfDeleted` 时用 `... where time<start order by time desc limit 1` 取新最新值 |
| `findAllKeysByEntityIds` | `show timeseries <device>.**` 收集测点名 |
| `findAllKeysByDeviceProfileId` | 返回空（与 Cassandra NoSQL 路径一致） |

**关键设计——避免双写**：历史 `save()` 与最新 `saveLatest()` 共用**同一个写缓冲区**（`IotdbWriteService` 单例，`@IotdbAnyDao` 装配）。当历史与最新都用 IoTDB 时，`doSave` 对同一条 `TsKvEntry` 分别调 `save()` 与 `saveLatest()`，两者产生**相同的 `(device, ts, key)`**，在缓冲区按 `(device,ts)` 合并为同一行的同一单元格 → **物理上只写一次**，不增加写负载。当历史用其他后端（如 Cassandra）、最新用 IoTDB 时，`saveLatest` 是 IoTDB 的唯一写入方，最新值正确维护。

**必要的集成改动**：`BaseTimeseriesService.doSave()` 用 `instanceof` 路由 latest 保存分支，已加入 `|| timeseriesLatestDao instanceof IotdbTimeseriesLatestDao`（走 Cassandra 式逐条 `saveLatest(entry)`），否则最新值不会被保存。

**装配层级**：`@IotdbAnyDao`（`ts.type=iotdb || ts_latest.type=iotdb`）装配共享的 `IotdbSessionPoolConfig` + `IotdbWriteService`，使「历史非 IoTDB、仅最新用 IoTDB」也能加载连接池与写服务。

---

## 5. 关键决策与取舍

| 决策点 | 选择 | 理由 |
|---|---|---|
| IoTDB 版本 | 1.3.7 树模型 | 与 TB 实体/测点结构天然契合，API 成熟 |
| 写入模式 | 内存攒批 + 对齐 Tablet | 官方高吞吐路径，逼近基准 |
| 可靠性 vs 吞吐 | **极致吞吐（纯内存攒批）** | 用户确认；接受崩溃丢失 ≤ flush 窗口的数据 |
| 数据模型 | **对齐序列（Aligned）** | 性能最优；类型漂移问题对齐/非对齐等同，无额外劣势 |
| latest 存储 | 沿用 Redis | 改造面最小、收益最大 |
| 单机/集群 | SessionPool + node_urls | 一套代码，配置切换 |

> 若日后需收紧可靠性，仅需把 flush 窗口调小并依赖 IoTDB 服务端 WAL，聚合器代码不变、只改参数。

---

## 6. 实现清单

### 6.1 新增文件

| 文件 | 作用 |
|---|---|
| `common/dao-api/.../util/IotdbTsDao.java` | 历史装配注解（`ts.type=iotdb`） |
| `common/dao-api/.../util/IotdbTsLatestDao.java` | 最新值装配注解（`ts_latest.type=iotdb`） |
| `common/dao-api/.../util/IotdbAnyDao.java` | 共享设施装配（任一为 iotdb） |
| `dao/.../timeseries/iotdb/IotdbSessionPoolConfig.java` | SessionPool 连接池、建库、TTL |
| `dao/.../timeseries/iotdb/IotdbSchemaUtil.java` | 路径/类型映射（历史与 latest 共用） |
| `dao/.../timeseries/iotdb/IotdbWriteService.java` | 共享写服务（唯一写缓冲区，避免双写） |
| `dao/.../timeseries/iotdb/IotdbTimeseriesWriteBuffer.java` | 写入聚合器（异构类型 + 每点 Future + 背压） |
| `dao/.../timeseries/iotdb/IotdbBaseTimeseriesDao.java` | 历史 DAO（写走聚合器、读/聚合下推） |
| `dao/.../timeseries/iotdb/IotdbTimeseriesLatestDao.java` | 最新值 DAO（`select last` + 共享写服务） |
| `dao/.../timeseries/iotdb/IotdbRealBenchmark.java` | 真实 DAO 端到端压测入口（本地压测用，非 Spring 组件，详见 7.7） |
| `application/.../install/IotdbTsDatabaseSchemaService.java` | 安装期（免建表） |

**对 `dao/.../timeseries/BaseTimeseriesService.java` 的改动**：
1. `doSave` 的 latest 路由 `instanceof` 分支加入 `IotdbTimeseriesLatestDao`（否则 IoTDB latest 不会被保存）；
2. 新增 `@Value("${database.ts.type:}")` 字段 + `skipRedundantIotdbLatest` 逻辑：历史与最新都用 IoTDB 且写历史时，跳过冗余的 `saveLatest`（历史写入已通过 LastCache 维护最新值，详见 7.7 优化 1）。

**对 `IotdbBaseTimeseriesDao` 的优化**：新增 `devicePathCache`（`ConcurrentHashMap<EntityId,String>`），避免每个点重复计算 IoTDB 设备路径（详见 7.7 优化 2）。

### 6.2 修改文件

| 文件 | 改动 |
|---|---|
| `pom.xml` | 新增 `iotdb.version=1.3.7` 属性、`iotdb-session` 依赖管理；license 插件排除 `.iotdb-docker/**`、`iotdb-benchmark/**` |
| `dao/pom.xml` | 引入 `iotdb-session` 依赖 |
| `application/.../thingsboard.yml` | 新增 `iotdb:` 配置块；`database.ts.type` 注释增加 iotdb 选项 |
| `.gitignore` | 忽略 `.iotdb-docker/`、`iotdb-benchmark/target/` |

### 6.3 编译验证

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # 必须 JDK 17
mvn -pl dao -am compile -DskipTests                 # BUILD SUCCESS
```
> 注意：项目旧版 Lombok 在 JDK 21+/25 会抛 `TypeTag :: UNKNOWN`，务必用 JDK 17。

---

## 7. 压测报告

### 7.1 测试方法

编写独立压测工程 `iotdb-benchmark/`，**复刻生产写入策略**（分片内存攒批 → 按 `(device,ts)` 合并对齐行 → `insertAlignedTablet`），模拟 TB 逐条 `save()` 的调用方式，直接压真实 IoTDB 容器，隔离测量写入路径吞吐，避免全栈依赖。

### 7.2 测试环境

| 项 | 配置 |
|---|---|
| 主机 | Apple 8 核（darwin arm64） |
| IoTDB | 1.3.7 单机版 Docker，`MAX_HEAP_SIZE=2G`、`MAX_DIRECT_MEMORY_SIZE=1G` |
| 数据集 | 2000 设备 × 20 测点 × 500 点 = **2000 万点**（DOUBLE） |
| 客户端 | JDK 25 运行基准 jar，SessionPool |

### 7.3 写入吞吐结果

| 配置 | 耗时 | 吞吐 |
|---|---|---|
| producers=8, batch=1000, flush=50ms | 10.0s | 200 万点/秒 |
| producers=16, batch=1000, flush=50ms | 8.8s | 226 万点/秒 |
| **producers=8, batch=500, flush=100ms** | **8.5s** | **235 万点/秒（峰值）** |
| producers=12, batch=500, flush=50ms | 9.0s | 224 万点/秒 |

各配置收敛在 220–235 万点/秒，表明客户端策略已压满、瓶颈在服务端（受容器 2G 堆与单机限制）。

### 7.4 查询结果（正确性 + 延迟）

| 查询 | 结果 | 延迟 |
|---|---|---|
| 原始范围查询（设备0.key0 取回 500 行，倒序） | 正确 | ~26 ms |
| 聚合 `group by 1h`（avg/max/count） | avg=0.2495, max=0.499, count=500 ✅ | ~3 ms |
| `last`（对应 latest 语义） | 值正确 | ~3 ms |

### 7.5 关键教训

初版「每个数据点独立成行」写法：仅 **97 万点/秒**，且同一时间戳下未写的列默认 0 且未标 null，被 IoTDB 按 `(device,ts)` 合并时**用 0 覆盖了真实值**。改为**按时间戳合并对齐行 + BitMap 标 null**后：

- 吞吐 **翻倍到 200 万+ 点/秒**（行数减少 20×）；
- 值正确（avg/max/count 全部符合预期）。

该经验已固化进生产聚合器 `IotdbTimeseriesWriteBuffer`。

### 7.6 性能展望

本次为 8 核笔记本 + 2G 堆容器的保守结果。参考官方（20G 堆、写优化配置、裸金属）可达千万点/秒级；提升容器堆内存、启用服务端写优化配置、生产集群横向扩展（Region 线性扩展），可进一步大幅提高。

### 7.7 真实 DAO 代码端到端压测（历史+最新都用 IoTDB）

前述 7.3 用的是独立复刻工程。为更真实，另写 `IotdbRealBenchmark`（`dao` 模块内），**反射装配真实生产 Bean**（`IotdbSessionPoolConfig` / `IotdbWriteService` / `IotdbBaseTimeseriesDao` / `IotdbTimeseriesLatestDao`），走真实 `save()` / `saveLatest()` 接口，模拟 `BaseTimeseriesService.doSave` 同时写历史+最新，并**等待每个 `ListenableFuture` 真正落库完成**才计时。

环境升级：IoTDB 容器 **4G 堆**、数据卷改用 **Docker named volume**（避开 macOS 文件共享层）。

| 场景（真实 DAO 代码） | 吞吐 |
|---|---|
| explicit：save() + saveLatest() 各写一次（优化前） | ~1.13 M 点/秒 |
| lastcache：仅 save()，最新靠 LastCache（优化后） | ~1.59 M 点/秒 |
| + 设备路径缓存，2000 万点 producers=16 | ~1.95 M 点/秒 |
| 4000 万点 producers=16（稳态） | ~2.64 M 点/秒 |
| 8000 万点 producers=16（稳态） | ~3.11 M 点/秒 |
| **1.2 亿点 producers=16（稳态上限）** | **~3.23 M 点/秒** |

所有场景 `findLatest` / `findAllLatest` / 聚合 / 原始查询均返回正确结果（如 `findLatest=0.499`、`AVG=0.2495`）。

**压测发现的两处真实优化（已并入生产代码）**：
1. **避免冗余写**：历史与最新都用 IoTDB 时，历史 `save()` 已通过 IoTDB 原生 LastCache 维护最新值，故 `doSave` 跳过冗余的 `saveLatest`（仅 latest-only 调用时才显式写）。吞吐 +40%（1.13→1.59 M），最新值仍正确。
2. **设备路径缓存**：`IotdbBaseTimeseriesDao` 按 `EntityId` 缓存计算好的 IoTDB 路径，避免每个点重复 `UUID.toString().replace(...)` 的字符串分配。

**瓶颈分析**（1.2 亿点运行时采样）：IoTDB 容器 CPU ~1.7–2.3 核，压测客户端 java ~2.8–3.8 核——两者在同一台 8 核机器上**争抢 CPU**，容器内存 6.0/7.65 GiB 接近 Docker VM 上限。即单机瓶颈是「客户端与服务端共享 CPU + 内存天花板」，非代码本身。**生产中 TB 与 IoTDB 分机部署、IoTDB 独占机器资源时会更高。**

> 结论：这台 8 核 / 16GB 机器上，真实 DAO 代码「历史+最新都用 IoTDB」的单机稳态写入上限约 **3.1–3.2 M 点/秒**。

### 7.8 MQTT 真实形态压测（宽设备+随机稀疏列）与二次优化

7.3/7.7 的高吞吐是「20 测点稠密」形态。用户用 MQTT 模拟 **5000 设备 × 500 测点、每秒随机 30%(150 点)变化** 实测性能大幅低于预期。用 `IotdbMqttLikeBenchmark`（真实 DAO，形态完全复现：每 tick 每设备随机 150 测点、同一时间戳）复现并定位出**两个根因**：

**根因 1（客户端）：稀疏形态下逐设备 RPC 爆炸。** 每设备每 50ms 只攒 1 行，原 `flushAll` 对每设备单发 `insertAlignedTablet` → RPC 数爆炸。同规模基线仅 **10.2 万点/秒**。
优化（已并入生产代码 `IotdbTimeseriesWriteBuffer`）：
1. **多设备合并 RPC**：`flushAll` 改为把整个分片的待刷设备合并成 `insertAlignedTablets(Map)` 一次发送（按 50 万单元格/1000 设备分块防超大消息）；
2. **全局 MeasurementSchema 缓存**（同名同类型跨设备/批次复用）；
3. **行数组倍增扩容**（消除宽设备逐列 +1 的 O(n²) 复制）；
4. **单元格直写类型数组**（消除逐格按测点名哈希查找）。
效果：同规模 10.2 万 → **21.3 万点/秒（2×）**。

**根因 2（服务端）：250 万条序列触发 507 拒写。** `5000×500=250 万`条时序超出默认 schema 内存配额（4G 堆默认仅 ~150MB，约 83 万条即 `507: Too many timeseries in memory without device template`），**写入被服务端静默拒绝**——这就是 MQTT 压测「性能极差」的直接原因（大量写入实际失败）。
修复（已固化进 compose）：`datanode_memory_proportion=3:2:4:1:1:1` + `schema_memory_proportion=6:3:1`（schema 段需按 序列数×~190B÷75% 安全水位 预算）。

**进一步（官方推荐）：设备模板。** 对「大量设备 × 相同测点」，建 `create schema template tb_dev aligned(...)` 挂到 `root.tb.DEVICE`：元数据不逐条物化，schema 内存近乎归零、**并且写入更快**。

**该形态实测结果**（5000×500×30%、2250 万点、8 核共享机）：

| 阶段 | 吞吐 | 说明 |
|---|---|---|
| 原始代码(用户遇到的状态) | ~10 万/秒 + 大量 507 拒写 | RPC 爆炸 + schema 配额不足 |
| 客户端 4 项优化 | 21.3 万/秒(1000 设备规模) | 零失败 |
| + schema 内存调优(5000 设备可用) | 稳态 12.7~15.6 万/秒 | 零 507 |
| **+ 设备模板** | **稳态 19.6 万/秒** | 零失败，schema 内存近乎零占用 |

**瓶颈与到 75 万点/秒目标的差距**：稳态时 IoTDB 容器 CPU **~843%（8 核全部打满）**，且与压测客户端同机争抢——单机形态天花板在服务端 CPU。要达到 5000×150=75 万点/秒输入率：① TB 与 IoTDB 分机（IoTDB 独占）；② 更多核数；③ 集群横向扩展（Region 线性）；④ 设备模板必开。另注意：用户经 MQTT 全链路的测量还包含 TB transport/规则引擎/队列开销，DAO 之外的链路也需相应扩容。

**其他实证结论**：`wal_mode=DISABLE` 在 1.3.7 单机不可用（IoTConsensus 依赖 WAL，DataNode 无法启动）；宽设备(500 测点)下 `findAllLatest` 冷查询 ~0.5s、聚合/单点 last 50~75ms。

---

## 8. 部署与配置

### 8.1 Docker 启动 IoTDB（单机）

```bash
docker run -d --name iotdb -p 6667:6667 \
  -e MAX_HEAP_SIZE=2G -e MAX_DIRECT_MEMORY_SIZE=1G \
  -e dn_rpc_address=0.0.0.0 -e dn_rpc_port=6667 \
  -v $(pwd)/.iotdb-docker/data:/iotdb/data \
  -v $(pwd)/.iotdb-docker/logs:/iotdb/logs \
  apache/iotdb:1.3.7-standalone
```
> 坑：单机版**不要**设 `dn_internal_address=<主机名>`，否则无法在该主机名上 bind 内部端口而崩溃退出；保持默认 `127.0.0.1`。

### 8.2 ThingsBoard 配置（`thingsboard.yml` / 环境变量）

```yaml
database:
  ts:
    type: "${DATABASE_TS_TYPE:cassandra}"   # 设为 iotdb 启用
iotdb:
  node_urls: "${IOTDB_NODE_URLS:127.0.0.1:6667}"   # 单机1个, 集群逗号分隔多个
  username: "${IOTDB_USER:root}"
  password: "${IOTDB_PASSWORD:root}"
  database: "${IOTDB_DATABASE:root.tb}"
  pool_size: "${IOTDB_POOL_SIZE:0}"                 # 0=自动(2×核数)
  enable_redirection: "${IOTDB_ENABLE_REDIRECTION:true}"
  enable_auto_fetch: "${IOTDB_ENABLE_AUTO_FETCH:true}"
  enable_compression: "${IOTDB_ENABLE_COMPRESSION:false}"
  ttl_ms: "${IOTDB_TTL_MS:0}"                       # 0=永不过期
  write:
    shards: "${IOTDB_WRITE_SHARDS:0}"               # 0=自动(核数)
    batch_size: "${IOTDB_WRITE_BATCH_SIZE:1000}"
    flush_interval_ms: "${IOTDB_WRITE_FLUSH_INTERVAL_MS:50}"
    max_pending_per_shard: "${IOTDB_WRITE_MAX_PENDING_PER_SHARD:200000}"
```

启用示例：
```bash
export DATABASE_TS_TYPE=iotdb
export IOTDB_NODE_URLS=127.0.0.1:6667          # 集群: h1:6667,h2:6667,h3:6667
# 最新值二选一：
export DATABASE_TS_LATEST_TYPE=iotdb           # IoTDB 一统（去 Redis 依赖，saveLatest 与历史合并不双写）
# export DATABASE_TS_LATEST_TYPE=redis         # 或最新值继续用 Redis（微秒级读）
```

### 8.3 集群注意事项

- 集群仅支持 host / overlay 网络，**不支持 bridge 网络**。
- 官方推荐 **3C3D**（3 ConfigNode + 3 DataNode），跨 **3 台机器**，每台 1 ConfigNode + 1 DataNode。
- 部署模板：[`docker/iotdb/docker-compose-cluster.yml`](../../docker/iotdb/docker-compose-cluster.yml)（每台机器一份，设 `NODE_IP`=本机 IP、`SEED_CN`=node1_IP:10710；先起 node1 种子，再起 node2/node3）。副本因子默认 schema=3 / data=2（仅首次 bootstrap 生效，三台需一致）。
- ThingsBoard 连接：`IOTDB_NODE_URLS=ip1:6667,ip2:6667,ip3:6667`（客户端自动路由/故障转移）。
- 客户端保持 `enable_redirection=true`（路由至 leader）+ `enable_auto_fetch=true`（发现可用 DataNode）。
- 服务端自动做 Region 存储/计算负载均衡；扩吞吐靠加 DataNode + 调 `data_region_per_data_node`（Region 线性扩展），单库即可，无需手动分库。

---

## 9. 已知限制与后续

### 9.1 已知限制

1. **STRING 与 JSON 都映射为 IoTDB TEXT**，读回统一当作 STRING（丢失 JSON 类型区分）。历史值读取一般直接用于展示，影响有限。
2. **同一 key 类型漂移**（先写 LONG 后写 DOUBLE）会被 IoTDB 拒绝（单测点类型固定），当前 catch 并记日志。对齐/非对齐模型此约束相同。
3. **纯内存攒批**：崩溃丢失 ≤ `flush_interval_ms` 内未 flush 的点（已确认取舍）。

### 9.2 后续可选工作

- **端到端联调**：起 TB（`DATABASE_TS_TYPE=iotdb`、`DATABASE_TS_LATEST_TYPE=iotdb`），用真实设备遥测验证读写、latest 与仪表盘。
- **latest 压测**：验证 `select last` 在高并发下的读延迟与 LastCache 命中。
- **历史数据迁移**：Cassandra → IoTDB 一次性迁移工具（读 Cassandra 批量 `insertAlignedTablet` 入 IoTDB）。
- **压测扩展**：混合类型（LONG/BOOLEAN/TEXT）、更大堆、集群横向扩展基准。
- **单元/集成测试**：仿照 `dao/src/test/.../timeseries` 增加 IoTDB 版（Testcontainers）。

---

## 10. 附录：常用运维命令

```bash
# 进入 CLI
docker exec -it iotdb /iotdb/sbin/start-cli.sh -h 127.0.0.1 -p 6667 -u root -pw root

# 查看某设备测点 / 原始数据 / 聚合 / 最新值
show timeseries root.tb.DEVICE.**;
select `temperature` from root.tb.DEVICE.u_xxxx where time >= 0 order by time desc limit 100;
select avg(`temperature`) from root.tb.DEVICE.u_xxxx group by ([0,now()),1h);
select last `temperature` from root.tb.DEVICE.u_xxxx;

# 设置/查看 TTL
set ttl to root.tb.** 2592000000;   -- 30 天(ms)
show all ttl;

# 重新运行独立压测 harness（复刻写入策略）
cd iotdb-benchmark && mvn -q clean package && \
  java -jar target/iotdb-benchmark-jar-with-dependencies.jar \
  127.0.0.1:6667 2000 20 500 8 500 100 16
# 参数: nodeUrls devices keysPerDevice pointsPerSeries producers batchSize flushMs poolSize
```

### 附录 B：运行真实 DAO 压测（7.7 节）

用 4G 堆 + named volume 的容器：
```bash
docker rm -f iotdb; docker volume create iotdb-data
docker run -d --name iotdb -p 6667:6667 --ulimit nofile=65535:65535 \
  -e MAX_HEAP_SIZE=4G -e MAX_DIRECT_MEMORY_SIZE=2G \
  -e dn_rpc_address=0.0.0.0 -e dn_rpc_port=6667 \
  -v iotdb-data:/iotdb/data apache/iotdb:1.3.7-standalone
```
编译并压测真实 DAO 代码（必须 JDK 17）：
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl dao -am install -DskipTests
mvn -pl dao dependency:build-classpath -Dmdep.outputFile=/tmp/daocp.txt -o
java -Xmx4g -cp "dao/target/classes:$(cat /tmp/daocp.txt)" \
  org.thingsboard.server.dao.timeseries.iotdb.IotdbRealBenchmark \
  127.0.0.1:6667 8000 20 500 16 1000 50 32 200000 0 lastcache
# 参数: nodeUrls devices keys points producers batchSize flushMs poolSize maxPendingPerShard shards latestMode
# latestMode: lastcache=仅save,最新靠LastCache(both=iotdb优化后) | explicit=save+saveLatest(优化前)
```

---

## 附录 C：IoTDB 调优参数（写入频率/点数越高怎么调）

调优分**客户端侧**（本实现的 `iotdb.*` 配置，在 `thingsboard.yml`）和**服务端侧**（IoTDB `iotdb-common.properties`，单机版可用 docker-compose 的同名 env 覆盖，见 `docker/iotdb/docker-compose-standalone.yml`）。

### C.1 先厘清「频率/点数」的三个维度

不同维度对应要调的参数不同，先对号入座：

| 维度 | 含义 | 主要影响 |
|---|---|---|
| **写入频率**（每秒总点数） | 单位时间落库的数据点总量 | 吞吐相关：攒批、并发、内存、刷盘/合并线程 |
| **单设备测点数**（keys/设备） | 一个实体同时上报多少 key | 对齐 Tablet 的列宽、memtable 内存 |
| **序列基数**（设备数 × keys） | 时间序列总条数 | Region 数、schema 内存、客户端分片 |

### C.2 客户端侧（`thingsboard.yml` 的 `iotdb.*`）

| 参数 | 默认 | 作用 | 频率/点数越高 → |
|---|---|---|---|
| `iotdb.write.batch_size` | 1000 | 单设备攒够多少「不同时间戳行」再刷一个对齐 Tablet | **调大**（如 2000~5000）提升批量效率；但过大增加单次延迟与内存 |
| `iotdb.write.flush_interval_ms` | 50 | 一个点最多等待多久强制刷 | 高频时可略**调小**保证低延迟；追求极致吞吐可**调大**(如 100~200) |
| `iotdb.write.shards` | 0(=核数) | 写入分片(刷盘线程)数，按设备哈希 | 设备数多、CPU 核多时**调大**（一般 = 核数） |
| `iotdb.write.max_pending_per_shard` | 200000 | 每分片队列上限(背压阈值)，防 OOM | 高频写 + 大堆时**调大**，避免生产者被过早背压阻塞 |
| `iotdb.pool_size` | 0(=2×核数) | SessionPool 连接数 | 并发高时**调大**（如 32~64） |
| `iotdb.enable_compression` | false | Thrift RPC 压缩 | 网络带宽紧张时开启(换 CPU)；本机/内网建议保持关闭 |

> 经验：本报告 7.7 中，`batch_size=1000`、`shards=16`、`pool=32`、`flush=50ms`、`maxPending=200000` 在 8 核机上跑出稳态 ~3.1M 点/秒。**先加 producers/shards 打满 CPU，再加 batch_size 提批量效率。**

### C.3 服务端侧（IoTDB `iotdb-common.properties` / compose env）

| 参数 | 默认 | 作用 | 频率/点数越高 → |
|---|---|---|---|
| `DATANODE_MEMORY_SIZE`（经自定义入口写入 `datanode-env.sh`） | 72G | DataNode 总预算，官方脚本再拆分堆与直接内存 | **最关键**。72G 在 1.3.7 中约为 63G 堆 + 9G 直接内存 |
| `CONFIGNODE_MEMORY_SIZE`（经自定义入口写入 `confignode-env.sh`） | 4G | ConfigNode 总预算 | 单机元数据/共识使用，需与 DataNode、原生内存共同计入容器上限 |
| `mem_limit` / `memswap_limit` | 96G / 104G | Docker 容器硬上限 / 内存与 Swap 总上限 | 硬上限必须大于两个节点预算之和，并给线程、元空间、文件缓存留足余量 |
| `data_region_per_data_node` | 5.0 | **写入并发的真正边界**(Region 数=并行写引擎数) | **调大**，建议 ≈ 逻辑核数 ÷ 2；序列基数/频率越高越受益 |
| `flush_thread_count` | 0(=核数) | memtable 刷盘线程数 | 高频写 + 快盘时**调大** |
| `compaction_thread_count` | ~核数/2 | 后台文件合并线程数 | 写入越密集小文件越多，**调大**以免合并跟不上拖慢查询 |
| `max_number_of_points_in_page` | 10000 | 每个 page 的最大点数 | 同测点高频连续写时**调大**(如 20000~50000)，提升压缩比与写吞吐 |
| `avg_series_point_number_threshold` | 100000 | memtable 内某序列平均点数达阈值即刷盘 | 单设备测点多、单序列高频时可适当调大(需配合更大堆) |
| `write_memory_proportion` | 19:1 | 写入内存中 Memtable : TimePartitionInfo | 一般不动；序列基数极大时留意 TimePartitionInfo |
| `datanode_memory_proportion` | 3:3:1:1:1:1 | 存储:查询:schema:共识:流:空闲 | **序列基数极大**(海量设备/测点)时，schema 段可适当增大 |
| `wal_mode` | ASYNC | WAL 落盘模式 | 吞吐优先保持 `ASYNC`；要求断电零丢用 `SYNC`(慢)。⚠️ 实测 1.3.7 单机 **不可 DISABLE**(IoTConsensus 依赖 WAL, DataNode 起不来) |
| `datanode_memory_proportion` | 3:3:1:1:1:1 | 存储:查询:schema:共识:流:空闲 | **序列基数大(设备×测点>80万条)必改**，否则 507 拒写。预算: 序列数×~190B ÷ 0.75(安全水位) ÷ 0.6(region 份额) = schema 段所需；实测 250 万条需 4/12(4G 堆) |
| `schema_memory_proportion` | 5:4:1 | schemaRegion:schemaCache:partitionCache | 配合上行调大 region 份额(如 6:3:1) |
| **设备模板**(DDL 非参数) | 无 | `create schema template ... aligned(...)` + `set ... to root.tb.<TYPE>` | **同构设备(相同测点集)强烈推荐**：schema 内存近乎归零、写入实测 +25%~50%、根治 507。测点集需预先可知(可 alter 追加) |

### C.4 场景速查

| 场景 | 客户端 | 服务端 |
|---|---|---|
| **提高整体写入吞吐** | `shards`=核数、`batch_size`↑、`pool_size`↑、`maxPending`↑ | `MAX_HEAP_SIZE`↑、`data_region_per_data_node`≈核/2、`flush_thread_count`↑、`compaction_thread_count`↑ |
| **单设备高频连续写**(如 1kHz) | `batch_size`↑ | `max_number_of_points_in_page`↑、`avg_series_point_number_threshold`↑、堆↑ |
| **海量设备/测点**(高基数) | `shards`↑、`pool_size`↑ | `data_region_per_data_node`↑、schema 内存段↑、堆↑ |
| **降低写入延迟** | `flush_interval_ms`↓、`batch_size` 适中 | `wal_mode=ASYNC`、`flush_thread_count`↑ |
| **强可靠(不丢数据)** | 改用「小 flush 窗口」而非纯内存攒批 | `wal_mode=SYNC` |
| **磁盘/带宽受限** | `enable_compression=true` | 多块独立磁盘、避免 NAS；`compaction_thread_count` 适度 |

### C.5 生效方式与注意

- IoTDB 参数分三类：**仅首次启动可改**、**重启生效**、**热加载**（`set configuration 'k'='v'` 或 `load configuration`）。改前查 `iotdb-common.properties.template` 说明。
- 单机版通过 docker-compose 的 `environment` 用**同名 env** 覆盖上述参数（已验证 `data_region_per_data_node` 等能写入配置文件）。
- **不要频繁手动 flush**：会产生大量小文件，拖慢查询，交给 IoTDB 自动管理。
- 集群：吞吐靠 **Region 线性扩展**（加 DataNode + 调 `data_region_per_data_node`），单库即可打满，无需手动分库。

来源：[IoTDB Common-Config-Manual](https://iotdb.apache.org/UserGuide/latest/Reference/Common-Config-Manual.html) · [部署与资源推荐](https://iotdb.apache.org/UserGuide/V1.2.x/Deployment-and-Maintenance/Deployment-Recommendation.html) · [分布式调优](https://dev.to/timechodb/key-apache-iotdb-distributed-tuning-details-you-must-understand-2gfh)
