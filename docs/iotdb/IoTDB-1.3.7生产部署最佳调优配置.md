# IoTDB 1.3.7 生产部署最佳调优配置（单机 / 集群）

| 项目 | 内容 |
|---|---|
| 适用版本 | Apache IoTDB **1.3.7**（官方 Docker 镜像）+ 本项目 `feature/iotdb-timeseries` 写缓冲实现 |
| 文档定位 | **生产配置速查**：拿来即用的参数清单与部署模板索引。原理与公式见《IoTDB参数调优指南》，容量选型依据见《IoTDB四机容量实测矩阵》《IoTDB全机械盘六类负载容量报告》 |
| 实测依据 | 本机 1.3.7 容器逐项验证参数生效机制（2026-07-17）；68/71 生产环境 1.3.7 实跑；四机容量矩阵 |
| 文档日期 | 2026-07-17 |

---

## 1. 先读：1.3.7 镜像的四个"不知道必踩"的坑（全部实测验证）

### 1.1 ⚠️ `MAX_HEAP_SIZE` / `MAX_DIRECT_MEMORY_SIZE` 是**无效环境变量**

1.3.x 全系（实测核对 1.3.2 与 1.3.7 镜像脚本）的 `datanode-env.sh` **根本不读取**这两个变量——它们是 0.13 时代的旧名。传了不会报错，但完全不生效，实际堆走自动计算。**历史部署凡是传这两个变量的，堆大小从来都是自动值而非你以为的设定值。**

正确的内存控制方式（按优先级）：

| 方式 | 说明 |
|---|---|
| **本仓库自定义入口（推荐）** | `docker/iotdb/docker-compose-standalone.yml` 已内置：传 `DATANODE_MEMORY_SIZE=72G`、`CONFIGNODE_MEMORY_SIZE=4G`，入口脚本分别写进两个 env.sh。**必须分开设**——见 1.2 |
| 官方 `MEMORY_SIZE` env | 节点总内存预算，堆/堆外自动按档位分配（见下表）。但 standalone 镜像下它**同时作用于 DataNode 和 ConfigNode 两个进程**，内存被双倍占用，故仅集群纯 DataNode/ConfigNode 镜像可直接用 |
| 改 `datanode-env.sh` 的 `ON_HEAP_MEMORY`/`OFF_HEAP_MEMORY` | 裸机部署或需要精确控制堆/堆外比例时 |

`MEMORY_SIZE` → 堆的自动分配档位（实测验证，32G VM → 16G 建议值 → 12816M 堆，数值精确吻合公式）：

```
不设 MEMORY_SIZE 时: MEMORY_SIZE = 系统内存 ÷ 2   ← 容器无 mem_limit 时读到的是宿主全量内存!
堆 = MEMORY_SIZE × 3/4  (总量 <4G)
   = MEMORY_SIZE × 4/5  (4G ~ 16G)
   = MEMORY_SIZE × 7/8  (16G ~ 128G)
   = MEMORY_SIZE - 16G  (≥128G)
堆外(direct) = MEMORY_SIZE - 堆
```

两个衍生坑：① 同机多容器（TB+Kafka+IoTDB 全家桶）不显式设内存时，IoTDB 默认拿走"主机内存一半"当预算，与其他服务超卖；② `mem_limit` 限的是容器，**不影响**上述公式读取的系统内存值，两者必须协同设置。

### 1.2 ⚠️ standalone 镜像是"一容器双进程"

`apache/iotdb:1.3.7-standalone` 内同时跑 ConfigNode + DataNode 两个 JVM。内存预算 = DataNode + ConfigNode 之和，ConfigNode 只管元数据/共识，**2~4G 足够，别浪费**。本仓库自定义入口就是为了把两者分开设置（官方 `MEMORY_SIZE` 做不到）。

### 1.3 ⚠️ `dn_rpc_address` 默认 `127.0.0.1`（1.3.7 安全加固）

不显式设 `dn_rpc_address=0.0.0.0`（集群设本机对外 IP），IoTDB 只监听环回，TB 侧持续报 `Cluster has no nodes to connect`、写入全部失败（68 升级实测踩坑）。所有部署模板已带此项。

### 1.4 调优参数通过 env 传入的机制与风险（实测确认）

官方镜像入口的 `replace-conf-from-env.sh` 会把**任意** env 键值写入 `iotdb-system.properties`：配置文件里已有的 key 替换、没有的 key **直接追加**。因此：

- ✅ 1.3.7 配置文件精简（多数参数默认不再列出）**不影响** env 传参——追加进去后服务端照常解析。本机实测：传 `datanode_memory_proportion=5:2:2:1:1:1`、`schema_memory_proportion=6:3:1`，启动日志的内存分配数值（Write 5.6G:Read 2.24G:Schema 2.24G:Consensus 1.12G:Pipe 1.12G；SchemaRegion 1.34G:Cache 0.67G:Partition 0.22G）与传入比例**精确吻合**。
- ⚠️ **参数名拼错不会报错**，会被静默追加然后被服务端忽略。上线前用启动日志核对：`docker logs iotdb | grep -E "allocateMemoryFor|update |append "`——`update/append` 行列出每个被写入的参数，`allocateMemoryFor*` 行可验算内存配比是否生效。

---

## 2. 快速决策：按目标规模选配置

负载画像先行（详细算法见《参数调优指南》§1）：`点速率 = 设备数 × 测点数 × 变化率`；`活跃序列数 = 设备数 × 测点数`（随机变化模型下与变化率无关）。

**长跑安全水位速查**（500 测点/设备、30% 随机变化模型，源自四机实测矩阵 + "每 100 万活跃序列 ≥14G 堆"约束）：

| 机器档位 | 参考机型 | DataNode 内存预算¹ | 建议生产设备数 | 对应点速率 |
|---|---|---|---|---|
| 16C / 30G / HDD | 183 | 12G | **≤1400 台** | ~21 万点/秒 |
| 32C / 64G / HDD | 68 | 30G | **≤4000 台** | ~60 万点/秒 |
| 112C / 128G / SSD | 72 | 56G | **≤6800 台** | ~103 万点/秒 |
| 160C / 384G / SSD | 71 | 72G² | **≤9000 台** | ~136 万点/秒 |

¹ `DATANODE_MEMORY_SIZE` 值（总预算，堆按 §1.1 档位自动分出）。同机还跑 TB 全家桶时相应下调并给 TB 显式设 `-Xmx8g~12g`。
² 371G 内存机器可以给更大预算，但 9000 台水位的约束是"序列×堆"公式而非上表预算，加堆可继续上探——先按 §6 浸泡验证再放量。
勘误注记：历史四机矩阵报告中"堆 64G/48G/28G/10G"列记录的是当时传入的 `MAX_HEAP_SIZE` 标签值；因该变量实际无效（§1.1），真实生效的是自动计算堆（68≈27G 与标签接近；71/72 实际更大）。故 71/72 的实测水位**偏保守**，结论方向安全，可直接沿用。

超过单机水位、或需要节点级高可用 → 集群（§4）或双机热备（《IoTDB-1.3.7双机自动热备实施手册》）。

---

## 3. 单机部署最佳配置

**直接用仓库模板** [docker/iotdb/docker-compose-standalone.yml](../../docker/iotdb/docker-compose-standalone.yml)（含自定义内存入口与全部注释），核心参数与推荐值：

```yaml
environment:
  # ---- 内存(第一优先级, 见 §1.1/1.2) ----
  - DATANODE_MEMORY_SIZE=72G        # 节点总预算: 独占机 = 物理内存-8~16G; 全家桶同机 = 减去 TB/Kafka 后的余量
  - CONFIGNODE_MEMORY_SIZE=4G       # 2~4G 足够
  # ---- RPC(1.3.7 必改) ----
  - dn_rpc_address=0.0.0.0
  - dn_rpc_port=6667
  - dn_internal_address=127.0.0.1   # 单机必须 127.0.0.1, 设主机名会 bind 失败
  # ---- 写入并发/吞吐 ----
  - data_region_per_data_node=8     # ≈ 逻辑核数÷2~÷4; 16C→4, 32C→8, 112C→12, 160C→16(实测值)
  - flush_thread_count=0            # 0=自动(=核数)
  - compaction_thread_count=4       # 核数÷2 起步; HDD/写入密集适当加大(大机器实测用 8)
  - max_number_of_points_in_page=10000
  - wal_mode=ASYNC                  # 吞吐优先; 禁止 DISABLE(IoTConsensus 依赖 WAL 起不来)
  # ---- 内存配比(高序列基数必改, 否则 507 拒写) ----
  - datanode_memory_proportion=5:2:2:1:1:1   # 写入吞吐优先(存储:查询:schema:共识:流:空闲)
  - schema_memory_proportion=6:3:1
mem_limit: 96g                      # ≥ DATANODE+CONFIGNODE+20% 原生内存余量
ulimits: { nofile: { soft: 65535, hard: 65535 } }
```

配比二选一（就两种场景，别自创）：

| 场景 | datanode_memory_proportion | 适用 |
|---|---|---|
| **写入吞吐优先** | `5:2:2:1:1:1` | 序列 <400 万（按堆折算），dashboard 查询轻。四机矩阵/生产实测配置 |
| schema 优先 | `3:2:4:1:1:1` | 序列基数逼近堆上限、出现过 `507 Too many timeseries in memory` |

**更优解——设备模板**（同构设备强烈推荐）：`create schema template ... aligned` 后元数据不逐条物化，schema 内存近乎归零且写入实测 +25~50%，可让同堆支撑的序列数大幅上探。

磁盘三条红线：WAL 与数据不同盘共 swap、swap 建议关闭、上查询负载前 HDD 需补测随机读。其余 OS 项模板已带（nofile 65535）。

---

## 4. 集群部署最佳配置（3C3D）

**模板** [docker/iotdb/docker-compose-cluster.yml](../../docker/iotdb/docker-compose-cluster.yml)（3 机 × 1 ConfigNode + 1 DataNode，官方标准形态；仅支持 host/overlay 网络）。单机 §3 的 DataNode 参数**逐台原样适用**，集群额外三类决策：

```yaml
# ConfigNode(每台)
- MEMORY_SIZE=4G                    # 集群纯 ConfigNode 镜像可直接用官方 env(单进程无 §1.2 问题)
- cn_internal_address=${NODE_IP}    # 本机对外 IP
- schema_replication_factor=3       # 元数据副本 = ConfigNode 数
- data_replication_factor=2         # 数据副本: 2=容 1 台故障(推荐), 3=金融级
# DataNode(每台)
- MEMORY_SIZE=72G                   # 按 §2 档位
- dn_rpc_address=${NODE_IP}         # 集群下填本机对外 IP(不是 0.0.0.0 也不是 127.0.0.1)
- dn_internal_address=${NODE_IP}
- dn_seed_config_node=${SEED_CN}
- data_region_per_data_node=8       # 每台按各自核数
```

三条集群铁律：

1. **副本因子只在首次 bootstrap 生效**，三台必须一致，事后改要重建集群——上线前定死。
2. **有效写入容量 = 裸容量 ÷ data_replication_factor**：三台各 60 万点/秒 + 副本 2 → 集群 ≈ 90 万点/秒，不是 180 万。
3. 每台 DataNode 的堆按其承载份额预算：`总序列 × 本机 Region 占比 × 副本因子`，套用"每 100 万序列 ≥14G 堆"。

TB 侧对接（与单机唯一差别是 URL 列表）：

```bash
IOTDB_NODE_URLS=10.0.0.1:6667,10.0.0.2:6667,10.0.0.3:6667
IOTDB_ENABLE_REDIRECTION=true      # 默认开: 写直达 Region leader
IOTDB_ENABLE_AUTO_FETCH=true       # 默认开: 自动感知节点故障/扩容(单机可关: 见调优指南 §3.5)
```

单机 → 集群迁移触发条件：点速率逼近单机拐点 70% 且模板/攒批优化用尽；或序列所需堆超单机内存；或要节点级 HA。

---

## 5. TB 程序侧配套（生产 env 清单）

```bash
# 存储选择
DATABASE_TS_TYPE=iotdb
DATABASE_TS_LATEST_TYPE=iotdb                # 或 redis(重 dashboard 场景微秒级 latest 读)
IOTDB_NODE_URLS=<ip>:6667
# 写缓冲(第一吞吐杠杆, 实测 50→2000ms 拐点 +50%)
IOTDB_WRITE_FLUSH_INTERVAL_MS=1000           # 业务接受秒级入库→1000~2000; 亚秒实时→50~500(吞吐打 6~7 折)
IOTDB_WRITE_MAX_PENDING_PER_SHARD=400000     # ≥ 点速率÷shards×(flush秒)×2~3, 与窗口联动
IOTDB_WRITE_MAX_BACKPRESSURE_WAIT_MS=0       # 0=如实反压到 Kafka(可恢复不丢数, 推荐); >0=超时快失该点保护调用线程
IOTDB_WRITE_STATS_INTERVAL_MS=10000          # 统计日志保持开启(运维判读依据)
# 读路径(与写物理隔离, 查询洪峰不挤占写入)
IOTDB_READ_POOL_SIZE=0                        # 0=auto(2×CPU); 连接数≈写池+读池, IoTDB 连接受限时调小
IOTDB_READ_QUEUE_CAPACITY=0                   # 0=auto(threads×64); 满队 fast-fail 保护规则引擎/状态/订阅线程
IOTDB_QUERY_TIMEOUT_MS=60000                  # 慢查询超时被服务端 kill, 避免读线程无限占用
IOTDB_READ_SLOW_QUERY_MS=1000                # 慢查询告警阈值(iotdbQuery.slow + WARN)
# 连接健壮性(不配则用客户端默认, 部分为 0=无限, IoTDB 卡住会永久等待)
IOTDB_CONNECTION_TIMEOUT_MS=15000            # thrift socket 往返上限(含写 flush RPC)
IOTDB_SESSION_WAIT_TIMEOUT_MS=10000          # 获取 Session 等待上限
IOTDB_INIT_RETRIES=5                         # 启动建库/连通探测重试; 耗尽仍失败 fail-fast(不带病启动)
# TTL(仅全局库级; 不支持数据级 per-tenant/entity TTL)
IOTDB_TTL_MS=0                               # 0=永不过期; >0 设库级 TTL(毫秒)
IOTDB_TTL_FAIL_FAST=false                    # true=TTL 设置失败即阻断启动(把 TTL 当磁盘硬约束时)
# TB 自身
JAVA_OPTS=-Xmx8g                             # 全家桶同机必设, 否则默认吃 1/4 物理内存挤占 IoTDB
```

规则引擎（DB `queue` 表 / UI）：Main 队列 `pack_processing_timeout` 调 **10000~30000ms**（必须 > flush 窗口 + flush 耗时）；强一致需求把策略改 `RETRY_FAILED_AND_TIMED_OUT`（IoTDB 同 ts 同 key 重复写幂等）。

运行纪律：新设备**分批接入**（≤1000 台/批，间隔数分钟）——N 台新设备首写触发 N×测点数的序列创建风暴，实测 200 万条约 2.5 分钟内写延迟秒级（数据不丢但告警刷屏）。

---

## 6. 上线验收 Checklist

| # | 检查项 | 方法 |
|---|---|---|
| 1 | 内存参数真实生效 | `docker logs iotdb \| grep allocateMemoryFor` 验算配比；`docker exec iotdb ps aux \| grep -o '\-Xmx[0-9]*[GM]'` 确认堆 = 预算×档位系数（**不要相信 compose 里写了什么，要看进程实际值**，§1.1 教训） |
| 2 | 调优参数无拼错 | `docker logs iotdb \| grep -E "update \|append "` 逐行核对参数名 |
| 3 | RPC 可达 | 宿主/TB 机 `telnet <ip> 6667`；TB 日志无 `no nodes to connect` |
| 4 | 写通路健康 | TB 日志 `[IoTDB] write buffer` 行：`failed=0`、`backpressure=0`、`flushAvg` 几十 ms 内 |
| 5 | 容量浸泡 | 目标设备数 × `IotdbCapacityBenchmark` paced **≥1 小时**（堆型劣化 50 分钟起爆，短测无效）；新序列先预建再跑干净轮 |
| 6 | 集群副本确认 | `show cluster` 三节点 Running；副本因子与规划一致（事后不可改） |
| 7 | 磁盘水位告警 | 数据盘剩余 <5%（`disk_space_warning_threshold`）时 IoTDB **自动进入只读**，TB 侧写入全失败（`302 read-only`）并伴随连接风暴（本机实测复现）。生产必须配 <85% 告警；触发后清理磁盘 + `SET SYSTEM TO RUNNING` 恢复 |
| 8 | 回滚预案 | 保留上一版镜像 tag 与数据卷备份路径；TB 侧 `DATABASE_TS_TYPE` 可随时切回原存储（数据不迁移） |

运维期"症状 → 动作"对照表见《IoTDB参数调优指南》§5；写缓冲统计日志逐字段判读见《IoTDB技术设计文档》§8.1。

### 6.1 新设备批量接入 SOP（首次投产 / 大批扩容必读）

IoTDB 序列首写自动创建，N 台新设备首次上线会瞬时触发 N×测点数 条序列的 schema 创建风暴（实测 200 万条 ≈ 2.5 分钟内写延迟秒级、规则引擎 pack 超时告警刷屏）。**数据不丢**（写请求已入缓冲最终落库），但会吓人且短时占用大量 schema 内存。分批接入流程：

1. **分批**：每批 ≤1000 台，批间隔 3~5 分钟（等上一批 schema 创建风暴平息）。观察 `[IoTDB] write buffer` 日志 `flushAvg` 回落到几十 ms、`pending` 不再堆积，再放下一批。
2. **可选预建序列**（大批量/严苛窗口推荐）：接入前用 `IotdbCapacityBenchmark` max 模式对目标设备预建 schema，或直接下发 `create aligned timeseries`，避免业务流量与 schema 创建争抢。
3. **接入期临时放宽**：接入窗口内把 Main 队列 `pack_processing_timeout` 临时抬到 30000ms，避免风暴期误报超时触发规则引擎重试放大；接入完成后可回落。
4. **验收**：接入完成后 `count timeseries root.tb.**` 应等于 设备数×测点数；`write buffer` 稳态 `failed=0`。

### 6.2 只有单机时的容量水位（无集群高可用）

单机部署时 IoTDB 无副本、宕机即时序不可写（TB 可临时切回原存储保留业务，见 checklist#8）。长跑水位严格按《IoTDB参数调优指南》"每 100 万活跃序列 ≥14G 堆"折算取，并预留 30% 余量——**单机不像集群能靠副本容错，务必保守**。有高可用要求时上 3C3D 集群（§4）或双机热备（《IoTDB-1.3.7双机自动热备实施手册》）。

### 6.3 孤儿序列清理（设备高流转现场必读）

ThingsBoard 删除设备时**只删 latest 缓存、不删时序本身**（所有存储后端一致的原生行为）。IoTDB 下这些残留序列会永久占用 schema 内存（~190B/条）——设备频繁注册-删除的现场，序列基数只增不减，缓慢侵蚀容量水位（堆是第一约束）。改名/弃用的 key 序列同理残留，且 `findAllKeysByEntityIds`（`show timeseries`）会把废弃 key 一并返回。

运维脚本 [`docker/iotdb/bin/iotdb-purge-orphan-series.sh`](../../docker/iotdb/bin/iotdb-purge-orphan-series.sh) 对比"IoTDB 里的设备序列"与"PG 里存活的设备"，清理差集（孤儿）：

```bash
./iotdb-purge-orphan-series.sh            # dry-run: 仅列出孤儿设备(纯只读, 安全)
./iotdb-purge-orphan-series.sh --apply    # 确认后执行 delete timeseries
```

安全设计：默认 dry-run 不删任何数据；`comm -23` 方向保证"PG 有但 IoTDB 尚未写数据"的正常新设备**不会**被误判为孤儿。建议低峰期运行（`show devices` 对超大序列基数有开销），按设备流转频率定期执行（如每周）或作为定时任务。

---

## 7. 本机 1.3.7 验证记录（2026-07-17，佐证本文档结论）

| 验证项 | 方法 | 结果 |
|---|---|---|
| env 调优参数生效 | 8C/32G Docker VM 起 1.3.7，传 7 项调优参数后核对配置文件与启动日志 | ✅ 全部写入 `iotdb-system.properties`（已有键替换、新键追加）；内存分配数值与 `5:2:2:1:1:1`、`6:3:1` 精确吻合 |
| `MAX_HEAP_SIZE` 无效性 | 传 `MAX_HEAP_SIZE=16G`，查进程实际 `-Xmx` | ✅ 实际 `-Xmx12816M` = 自动计算值（32G VM→建议 16G→4/5 档），设定值被忽略；1.3.2 镜像同样不读该变量 |
| 内存自动分配公式 | 逐段核对 `datanode-env.sh` | ✅ 与 §1.1 公式一致，实测数值验证通过 |
| 写路径兼容 | `IotdbCapacityBenchmark` max 模式 3600 台×500 测点（180 万序列 reset 重建） | ✅ 1088 万点全部落库零失败，均值 45.4 万点/秒（含 schema 创建开销；x86 模拟环境，仅作功能佐证，容量结论以四机裸金属矩阵为准） |
| paced 浸泡（干净轮） | 3600 台×500 测点 @flush=1000ms，300s 限速节拍 | ✅ **1.63 亿点全部落库、零失败、零 overrun、判定可持续**（稳态 54.4 万点/秒，flushAvg 78~267ms）。与 1.3.2 同环境同档锚点持平——**1.3.7 写路径无性能退化** |

## 8. 查询性能实测与官方口径对齐（2026-07-18）

### 8.1 查询性能实测（重点：高频写入下的最新值查询）

工具 `IotdbQueryBenchmark`（SQL 形态与 `IotdbTimeseriesLatestDao`/`IotdbBaseTimeseriesDao` 真实查询路径逐字一致）。环境：本机 8C/32G VM（x86 模拟）、1.3.7、2000 台×500 测点=100 万序列、历史 ~135 点/序列。**边写边查轮 = 30 万点/秒背景写入下测查询**（生产真实形态）：

| 用例（对应 TB 接口） | 空载 P50/P99 | **边写边查 P50/P99** | 空载 QPS(8线程) | **边写边查 QPS** |
|---|---|---|---|---|
| `findLatest` 单 key 最新值（SELECT LAST） | 3ms / 11ms | **1ms / 15ms** | 1207 | **2766** |
| `findAllLatest` 500 测点全量最新值 | 18ms / 238ms | **4ms / 45ms** | 402 | **868** |
| raw 最近 5min desc limit 100 | 2ms / 99ms | **1ms / 3ms** | 2316 | 1974 |
| 聚合 1h 窗口 avg 每 1min | 2ms / 19ms | **1ms / 19ms** | 2456 | 2520 |

三个关键结论：

1. **写入越活跃，最新值查询越快**（边写边查全面优于空载）：持续写入使 LastCache/memtable 全热在内存，`SELECT LAST` 走纯内存路径——"高频写入 + 频繁查最新"恰好是 IoTDB 的甜点场景，两者相互增益而非争抢。
2. **查询不拖累写入**：8 线程混合查询压满期间，背景 30 万点/秒写入全程零 overrun、flushAvg 127~211ms 正常。
3. 宽设备全量 latest（500 测点一次取回）P50 4ms，dashboard 场景余量充足；若 latest 读 QPS 需求 >数千/秒再考虑 `ts_latest.type=redis`。

### 8.2 查询内存安全性：频繁查询不会累积内存、大查询不会崩服务端（实测）

针对"查询频繁是否导致内存越来越大、会不会查崩"的专项验证（2026-07-18，同 §8.1 环境）：

**实验一：查询风暴浸泡**。16 线程混合查询（含最重的 500 测点全量 latest）连续 4 轮约 10 分钟 + 全程 30 万点/秒背景写入：

- 服务端内存在写入 memtable 建立工作水位（~16G）后，整个风暴期 **16.03G → 16.17G 基本平坦**——无查询导致的单调增长；零 GC 告警。
- 4 轮性能**无劣化**（单 key latest QPS 1534→3003→2900→3377，第 4 轮反而最快——缓存越查越热）；背景写入全程零 overrun。
- 首轮冷数据下有 324 次客户端瞬时错误（服务端无对应错误记录，为连接/超时竞争），数据热后第 2~4 轮零错误——冷启动首分钟的抖动属预期。

**实验二：炸弹查询**。风暴高压期叠加三发极端查询（100 万序列全模式匹配 `select * from root.tb.**`、全设备全测点宽查询、全库聚合扫描）：

- **三发全部被服务端主动拒绝**：`1707: There is not enough memory for Query ...`（明确报告需 280MB 而查询配额池仅剩 159MB）——fail-fast 拒绝该条查询，服务端全程 running、`OOMKilled=false`，其余查询与写入不受任何影响。

**原理**：IoTDB 查询内存是**配额制**（`datanode_memory_proportion` 的查询段，本配置 2/12）——每条查询执行前向配额池预留内存、结束即归还，跨查询不累积；超配额的查询被拒绝而非拖垮进程。结论：**查询频繁不会把内存越查越大，单个大查询也不会崩服务端**。

两个生产注意项：① 被 1707 拒绝时 TB 侧该次 API 调用会失败——大范围历史查询要做分页/限流，前端处理报错；② 本配置为写入优先（查询段仅 2/12 ≈ 堆×1/6），若生产查询并发高或范围大，把 `datanode_memory_proportion` 调回默认 `3:3:1:1:1:1`（查询段 3/12）即可扩大查询配额。

### 8.3 与官方性能口径对齐

| 维度 | 官方口径 | 本项目实测 | 对齐结论 |
|---|---|---|---|
| 写入（稠密 Tablet 形态） | 单机"千万点/秒级"（VLDB 论文 3000 万；官方 benchmark 工具示例 512 万点/秒） | 独立 harness 稠密纯写 **~310 万点/秒**（8C VM/4G 堆小容器，x86 模拟） | ✅ 同量级；官方数字为多核大内存真机，按硬件折算对齐 |
| 写入（变化上报稀疏形态） | 官方未发布此形态数字 | 稀疏形态服务端每点 CPU 成本 ≈ 稠密的 6~7 倍；71 真机（160C/SSD）实测 **≥263 万点/秒**（17500 台），按形态系数折算 ≈ 稠密口径千万级 | ✅ 折算后与官方量级一致；差异源于负载形态而非实现损耗 |
| 查询延迟 | "毫秒级"聚合、raw 数百 ms（十亿点）；benchANT 评其读延迟比多数对手低 20 倍 | 单 key latest P50 **1ms**、聚合 P50 1~2ms、宽设备 P50 4ms（边写边查） | ✅ 完全对齐"毫秒级"宣称 |

> 官方参考：[IoTDB Performance](https://iotdb.apache.org/UserGuide/V1.2.x/IoTDB-Introduction/Performance.html) · [Benchmark Tool](https://iotdb.apache.org/UserGuide/latest/Tools-System/Benchmark.html) · [VLDB 论文](https://www.vldb.org/pvldb/vol13/p2901-wang.pdf) · [benchANT 评测](https://benchant.com/blog/apache-iotdb-performance)

**复测附记（排障过程本身就是生产教训）**：首两轮浸泡在 160s 前后"卡死"（写入停摆、SessionPool 重连风暴、服务端 CPU 反而空闲），排查确认根因是 **Docker VM 磁盘写到 95%、触发 IoTDB 磁盘水位保护（`disk_space_warning_threshold=5%`）自动进入只读**，客户端表现为 `302: system is read-only` + flush 全失败。清理磁盘至 27% 后同参数干净轮零瑕疵通过。两条教训已纳入正文：① 磁盘水位告警是上线硬性检查项（§6#7）；② "写入停摆但服务端空闲"的排查顺序：磁盘只读 → 网络 → GC（《参数调优指南》§5 症状表）。
