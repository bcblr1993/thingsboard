# IoTDB 时序存储参数调优指南（单机 / 集群 / 程序侧）

| 项目 | 内容 |
|---|---|
| 适用版本 | Apache IoTDB 1.3.7 + 本项目 `feature/iotdb-timeseries` 写缓冲实现 |
| 文档定位 | 独立的运维调优手册。架构原理见《IoTDB技术设计文档.md》，实测过程见《IoTDB时序存储集成设计与压测报告.md》 |
| 文档日期 | 2026-07-12 |

**使用方法**：先按 §1 算出你的负载画像 → 按 §2（程序侧）→ §3（单机）或 §4（集群）设置参数 → 上线后按 §5 的"症状 → 动作"表运维。所有建议值均有实测依据，实测锚点标注在各节。

---

## 1. 先算清楚你的负载（一切调优的输入）

三个决定性指标：

```
点速率(点/秒)   = 设备数 × 每秒每设备上报点数
                  变化上报模型: 设备数 × (测点数 × 变化率 + 测点数 ÷ 全量快照周期秒)
                  例: 4000 台 × (500×30% + 500÷300) ≈ 4000 × 151.7 ≈ 60.7 万点/秒

序列基数(条)    = 设备数 × 每设备测点数     ← 决定 IoTDB 堆内存与 schema 配额
                  例: 4000 × 500 = 200 万条。注意: 变化上报不减少序列基数(所有测点都会活跃)

写入形态        = 每序列每次写入深度。变化上报(每设备每秒 1 行)是"浅写入"形态,
                  服务端每点 CPU 成本 ≈ 稠密批量形态的 6~7 倍 —— 这是第一调优对象
```

**容量锚点（实测）**：

| 环境 | 配置 | 结果 |
|---|---|---|
| 8 核 VM / IoTDB 16G 堆 / SSD | flush=50ms | 拐点 45 万点/秒（3000 台×500 点@30%） |
| 8 核 VM / IoTDB 16G 堆 / SSD | flush=2000ms | **≥68 万点/秒（4500 台）零 overrun** |
| 32 核 / IoTDB 28G 堆 / HDD 全家桶 | flush=50ms | 30 万点/秒长期稳定；60 万点/秒 50 分钟后**堆耗尽雪崩** |

约束优先级：**① IoTDB 堆内存（由序列基数决定，撑不住是雪崩不是变慢）→ ② IoTDB CPU（由点速率×写入形态决定）→ ③ 主机总内存 → ④ 磁盘**。

---

## 2. 程序侧（ThingsBoard）调优

### 2.1 写缓冲核心参数（`thingsboard.yml` → `iotdb.write.*`）

| 参数 | 环境变量 | 默认 | 调整方法 |
|---|---|---|---|
| `flush_interval_ms` | `IOTDB_WRITE_FLUSH_INTERVAL_MS` | 1000 | **第一吞吐杠杆**，见下文 |
| `max_pending_per_shard` | `IOTDB_WRITE_MAX_PENDING_PER_SHARD` | 200000 | **与上一项联动**，见下文 |
| `shards` | `IOTDB_WRITE_SHARDS` | 0=CPU 核数 | 一般不动。TB 与 IoTDB 同机且 CPU 紧张时可设为核数一半 |
| `batch_size` | `IOTDB_WRITE_BATCH_SIZE` | 1000 | 单设备攒满即刻 flush 的行数上限，变化上报形态到不了，不动 |
| `stats_interval_ms` | `IOTDB_WRITE_STATS_INTERVAL_MS` | 10000 | 统计日志周期，生产保持开启（运维判读依据，见 §5） |

**`flush_interval_ms`（攒批窗口）** —— 变化上报形态下最重要的一个参数：

- 含义：缓冲中的点最长等多久强制落库。它同时决定**入库延迟上界**（≈ 窗口 + flush 耗时）和**攒批深度**（窗口越大每次 RPC 摊销越好、IoTDB 每点 CPU 成本越低）。
- 实测：50ms → 2000ms 使同硬件拐点 +50%（45 万 → ≥68 万点/秒）；**1000ms 以上收益趋平**。
- 取值：业务接受秒级入库 → **1000~2000**；要求亚秒实时性 → 50~500（吞吐上限打 6~7 折）。
- 代价：写缓冲纯内存，**进程崩溃丢失窗口内数据**（正常停机排干不丢）。窗口即丢数窗口。

**`max_pending_per_shard`（背压硬水位）** —— 保护参数，必须随窗口联动：

```
每分片稳态积压峰值 ≈ 总点速率 ÷ shards × (flush_interval_ms ÷ 1000)
max_pending_per_shard ≥ 峰值 × 2~3
例: 60 万点/秒、32 分片、flush=1000ms → 峰值 1.9 万 → 默认 200000 余量充足
    点速率或窗口再大一个量级 → 相应放大(实验最大用过 400000, 内存代价 ≈ 值×shards×100B)
```

误设过小的症状：统计日志 `backpressure > 0` 但 `flushAvg` 正常（几十 ms）——这是水位误触发，白白阻塞规则引擎；调大即可。若 `backpressure > 0` 且 `flushAvg` 秒级，是 IoTDB 真过载，调水位无用，回到 §3/§4。

### 2.2 连接与查询

| 参数 | 建议 |
|---|---|
| `iotdb.pool_size` | 默认 0（=2×CPU）足够；SessionPool 满的症状是读写都排队 |
| `iotdb.enable_compression` | 默认 false。TB 与 IoTDB 跨机房/带宽受限时才开（CPU 换带宽） |
| `iotdb.ttl_ms` | 按数据保留策略设置，让 IoTDB 原生过期，禁止用应用层删除 |
| `database.ts_latest.type` | 大量 dashboard/latest 读场景建议 `redis`（微秒级）；写入侧因缓冲合并，latest 用 IoTDB 也不增加写放大 |
| `iotdb.read_threads` | 默认 2×CPU。重查询场景（大窗口聚合并发多）单独压测后再调 |

### 2.3 规则引擎配套（数据库 `queue` 表 / UI 队列管理）

| 项 | 默认 | 建议 |
|---|---|---|
| Main 队列 `pack_processing_timeout` | 2000ms | **10000~30000ms**。必须 > `flush_interval_ms` + flush 耗时，否则稳态误报超时；大规模设备接入期尤其必要 |
| 处理策略 | `SKIP_ALL_FAILURES` | 超时/失败不重发。要强一致改 `RETRY_FAILED_AND_TIMED_OUT`（IoTDB 同 ts 同 key 重复写幂等，无副作用） |

### 2.4 运行纪律（不是参数但同样重要）

1. **新设备分批接入**（每批 ≤1000 台，间隔几分钟）：N 台新设备首写会触发 N×测点数条序列的自动创建，实测 200 万条持续 ~2.5 分钟，期间写延迟秒级、超时告警刷屏（数据不丢但很吓人）。
2. **TB 进程显式设堆**：容器不设 `-Xmx` 默认吃 1/4 物理内存，与 IoTDB 同机时会挤占其堆。同机部署建议 TB `-Xmx8g~12g`。
3. **禁止给规则链入口节点开 Debug mode**：每条消息写一条 PG 事件，等于给 PG 加遥测同量级写入。

---

## 3. IoTDB 单机调优

参考部署文件：`docker/iotdb/docker-compose-standalone.yml`（含全部参数的注释版）。

### 3.1 JVM 内存（第一优先级，决定"能装多少序列"）

```yaml
- DATANODE_MEMORY_SIZE=72G       # 约得到 63G 堆 + 9G 直接内存
- CONFIGNODE_MEMORY_SIZE=4G      # 约得到 3.2G 堆 + 0.8G 直接内存
mem_limit: 96g                   # 容器硬上限，必须给原生内存和文件缓存留余量
memswap_limit: 104g              # 容器内存与 Swap 的总上限
```

> Apache IoTDB 1.3.7 官方镜像的 `datanode-env.sh` / `confignode-env.sh` 不读取
> `MAX_HEAP_SIZE`、`MAX_DIRECT_MEMORY_SIZE` 环境变量。单机 Compose 通过
> `bin/entrypoint-standalone.sh` 将上述两个内存预算写入官方启动脚本；只配置旧变量会被静默忽略。

**堆大小 ↔ 序列基数的实测换算**：28G 堆稳定承载 100 万条活跃序列，200 万条约 50 分钟后 GC 雪崩（`GcTimeAlerter` 告警 → GC 占比 100%）。**经验公式：每 100 万条活跃序列至少预算 14G 堆**（变化上报形态，含 memtable/合并开销）。这是硬约束——堆不够的表现不是变慢而是运行几十分钟后雪崩，短测发现不了。

### 3.2 内存配比与 schema 配额（高序列基数必改，否则 507 拒写）

```yaml
- datanode_memory_proportion=3:2:4:1:1:1   # 存储:查询:schema:共识:流:空闲
- schema_memory_proportion=6:3:1           # schemaRegion:schemaCache:partitionCache
```

- schema 段预算公式：`序列条数 × ~190B ÷ 0.75(安全水位)`。实测 250 万条序列在 4G 堆下需把 schema 提到 4/12 才不触发 `507 Too many timeseries in memory`。
- **写入吞吐优先且序列 <100 万时**，把配额还给存储引擎：`5:2:2:1:1:1`（本机压测配置，存储段 = 堆×5/12）。
- **更优解——设备模板**（同构设备强烈推荐）：`create schema template ... aligned` 后元数据不逐条物化，schema 内存近乎归零且**写入实测 +25~50%**。代价是设备建模时要挂模板、测点集合需相对统一。

### 3.3 写入并发与后台任务

```yaml
- data_region_per_data_node=4     # 写入并发真正边界。建议 ≈ 逻辑核数÷2，高负载可到核数
- flush_thread_count=0            # 0=自动(=核数)，一般不动
- compaction_thread_count=4       # 写入越密集小文件越多，建议 核数÷2；盘慢(HDD)适当加大
- max_number_of_points_in_page=10000   # 同测点高频连续写可调大到 20000~50000(提升压缩比)
- wal_mode=ASYNC                  # 吞吐优先。断电零丢改 SYNC(吞吐大降)；不可 DISABLE(IoTConsensus 依赖 WAL)
```

### 3.4 操作系统与磁盘

| 项 | 要求 |
|---|---|
| `nofile` | 65535（compose 已带 ulimits；裸机部署改 limits.conf） |
| 磁盘 | 首选 SSD/NVMe。机械盘可用（顺序写压力小：LZ4 压缩比 ~4.5，60 万点/秒落盘仅 ~10MB/s + WAL），但**三条红线**：WAL 与数据分盘、swap 绝不与数据同盘、上查询负载前先补测随机读 |
| swap | 建议关闭或最小化——内存紧张时 swap 会把 GC 停顿放大成全局 iowait 雪崩（68 实测教训） |

### 3.5 IoTDB 1.3.7 版本适配（从 1.3.2 升级必读，单机/集群通用）

1.3.7 的安全加固与配置精简引入两个「不改就踩坑」的点：

1. **`dn_rpc_address` 必须显式设 `0.0.0.0`（否则客户端连不上）** —— 1.3.7 把 RPC 监听地址默认值从 `0.0.0.0` 改为 `127.0.0.1`（安全加固）。容器化或跨机访问下若沿用默认，IoTDB 只监听环回，TB 侧会持续报 `Cluster has no nodes to connect`、写入全部失败（本项目 68 升级时实测踩坑）。
   - Docker：`docker-compose-standalone.yml` 已带 `dn_rpc_address=0.0.0.0`；集群 `docker-compose-cluster.yml` 用 `dn_rpc_address=${NODE_IP}`（本机对外 IP）。裸机部署改 `iotdb-system.properties`。
   - 排查：TB 报 no-nodes / 宿主 `telnet <iotdb_ip> 6667` 不通 → 先查 `dn_rpc_address` 是否 0.0.0.0（或对外 IP）、6667 端口映射与容器是否存活。

2. **配置文件精简——参数名没变，但默认不再列出** —— 1.3.7 默认 `iotdb-system.properties` 只保留少量常改项，§3.2/§3.3 的内存配比与并发参数（`datanode_memory_proportion`、`schema_memory_proportion`、`data_region_per_data_node`、`wal_mode` 等）**参数名在 1.3.7 全部仍然有效**，只是默认配置文件里看不到。设置方式：从 `iotdb-system.properties.template` 拷贝对应行到 `iotdb-system.properties`，或（Docker 部署）用 `environment` 传入（官方镜像 entrypoint 会写入配置）。**运维时 `grep` 配置文件找不到某参数 ≠ 该参数无效**。

3. 其他加固（影响小，了解即可）：1.3.7 移除了部分高危 RPC 接口与 JEXL 函数；1.3.5 起用户密码加密算法调整（仅在从旧版升级后用外部工具直连时需注意）。本项目 SessionPool 的写入/查询路径均不受影响。

> tsfile 客户端包名从 `org.apache.iotdb.tsfile.*` 迁移到 `org.apache.tsfile.*`（1.3.3 起）已在代码侧完成，详见《IoTDB技术设计文档》§7 升级说明。

---

## 4. IoTDB 集群调优（3C3D）

参考部署文件：`docker/iotdb/docker-compose-cluster.yml`（含三机部署步骤）。**集群只支持 host/overlay 网络**，官方标准形态 3 机 × (1 ConfigNode + 1 DataNode)。

### 4.1 与单机的关系

DataNode 的全部单机参数（§3.1–3.4）**原样适用、逐台设置**；集群额外多出三类决策：副本因子、Region 数、客户端路由。

### 4.2 ConfigNode（轻量，别浪费内存）

```yaml
- MEMORY_SIZE=2G                  # 仅元数据/共识，2G 足够(集群纯 ConfigNode 镜像用官方 env; MAX_HEAP_SIZE 无效, 见 §3.1)
- schema_replication_factor=3     # 元数据副本 = ConfigNode 数
- data_replication_factor=2       # 数据副本，见下
```

- **副本因子只在集群首次 bootstrap 时生效**，三台必须一致，事后改需重建集群——上线前定好。
- `data_replication_factor=2` 是可用性/成本平衡点（容 1 台故障，写放大 ×2）；金融级可用 3。
- **有效写入容量 = 集群裸容量 ÷ data_replication_factor**：3 台单机各能扛 60 万点/秒，副本=2 时集群有效容量 ≈ 3×60÷2 = 90 万点/秒，不是 180 万。

### 4.3 Region 与扩展性

```yaml
- data_region_per_data_node=8     # 每 DataNode 的并行写引擎数，建议 ≈ 该机逻辑核数÷2
```

- IoTDB 的扩展单位是 Region 而不是节点——加机器后新 DataNode 分担 Region，写入近似线性扩展。
- 序列基数在集群下同样按堆预算：每 DataNode 的堆按它承载的 Region 份额估算（≈ 总序列 × 本机 Region 占比 × 副本因子）。

### 4.4 客户端（TB 侧）对接集群

```bash
IOTDB_NODE_URLS=10.0.0.1:6667,10.0.0.2:6667,10.0.0.3:6667   # 填全部 DataNode
IOTDB_ENABLE_REDIRECTION=true    # 写请求直达目标 Region leader，省一跳转发(默认开)
IOTDB_ENABLE_AUTO_FETCH=true     # 后台拉取可用 DataNode，节点故障/扩容自动感知(默认开)
```

三个都保持默认即可；`node_urls` 单地址=单机、多地址=集群，程序代码路径完全一致，无需改任何其他配置。

### 4.5 单机 → 集群的迁移触发条件

满足任一条即开始规划集群：① 点速率逼近单机拐点的 70%（§1 锚点换算）且 L1/模板优化已用尽；② 序列基数所需堆超过单机可分配内存；③ 需要节点级高可用。

---

## 5. 运维判读：症状 → 动作对照表

写缓冲统计日志（每 10s 一行，字段详解见技术设计文档 §8.1）+ IoTDB 日志，覆盖绝大多数问题：

| 症状 | 根因 | 动作 |
|---|---|---|
| `flushAvg` 数百 ms 且 `flushMax` 频繁过秒 | IoTDB 变慢（劣化第一阶段） | 查 IoTDB `GcTimeAlerter` 日志与磁盘 iowait；预谋扩容 |
| `pending` 逐窗口单调上涨 | 写入速度已跟不上摄入（第二阶段） | 立即降载或扩容；确认 flush_interval 是否还在 50ms 档 |
| `backpressure > 0` + `flushAvg` 正常 | 背压水位误设过小 | 按 §2.1 公式调大 `max_pending_per_shard` |
| `backpressure > 0` + `flushAvg` 秒级 | IoTDB 真过载（第三阶段，雪崩在即） | 降载；事后按 §3/§4 扩容。这是 68 雪崩前的最后信号 |
| `failed > 0` | 写入真失败 | 看同时刻 `[IoTDB] flush failed` 完整堆栈 |
| IoTDB 日志 `Gc Time Percentage` 告警 | 堆不够（序列基数超预算） | 按 §3.1 公式加堆；或设备模板降序列内存 |
| IoTDB `507 Too many timeseries` | schema 配额不足 | 按 §3.2 调配比，或上设备模板 |
| 规则引擎大量 `Timeout to process` 但 Kafka lag 不涨 | 单条延迟超 pack 超时（常见于接入风暴期） | 按 §2.3 调大 pack 超时；新设备分批 |
| 启动后前几分钟大量超时然后自愈 | 新设备序列创建风暴（预期行为） | 遵守 §2.4 分批纪律；数据不丢，无需处理 |
| TB 持续报 `Cluster has no nodes to connect`、`failed` 持续 = `added` | iotdb 未监听外部地址（1.3.7 默认 127.0.0.1）／容器被删／端口不通 | 按 §3.5：确认 `dn_rpc_address=0.0.0.0` 且 6667 可达；容器化查端口映射与容器存活 |
| flush 失败报 `302: ... system is read-only`，写入全失败但查询正常 | **数据盘剩余空间 < 5%**（`disk_space_warning_threshold`），IoTDB 保护性进入只读（本机实测复现：还会伴随 SessionPool 重连风暴，表象酷似服务端卡死） | 清理/扩容磁盘；若未自动恢复，CLI 执行 `SET SYSTEM TO RUNNING` 或重启 DataNode。生产必须对数据盘做 <85% 水位告警 |

**变更验证纪律**：任何容量相关调参后，用 `IotdbCapacityBenchmark`（paced 模式）按目标负载浸泡 **≥1 小时**再下结论——堆容量问题要 50 分钟以上才暴露，6 分钟的绿灯不算数；新设备级别必须先建好序列再跑干净轮，否则 schema 风暴会污染判定。

```bash
# 用法(paced 模式): 目标设备数能否被持续消化
java -cp dao/target/classes:<deps> org.thingsboard.server.dao.timeseries.iotdb.IotdbCapacityBenchmark \
  <nodeUrls> <设备数> 500 150 300 <时长s> 6 1000 <flushMs> 32 <maxPending> 0 paced keep
```
