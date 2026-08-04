# Apache IoTDB 1.3.7 双机自动热备实施手册

## 1. 结论与适用边界

本方案面向 `10.8.8.66 / 10.8.8.67` 两台 32 核、约 64 GiB、1 Gbit/s
服务器，使用 Apache IoTDB **1.3.7 standalone**。业务统一连接
`10.8.8.65:6667`，不感知当前主机；主机、IoTDB 进程或主网卡故障时，
Keepalived 无人工确认自动迁移 VIP，恢复节点自动作为备机追平，默认不抢回。

这里的“双向同步”是**方向可自动反转**，不是 A→B 和 B→A 同时运行：
正常时只有当前主节点向备节点同步；发生切换后，新主先独立承接写入，旧主
恢复并清理旧方向后，再创建新主→旧主的全量补齐和实时同步。IoTDB 1.3.7
Pipe 请求会继续转发，同时开启两个可达方向会造成数据回环，因此被严格禁止。

必须接受以下边界：

- Pipe 是异步复制，不是同步提交；RPO 不是严格 0。普通 Pipe 基于封闭
  TsFile，本方案把 seq/unseq memtable 定时 flush 设为 1 秒，空闲写入通常
  约 2 秒内进入复制链路，但宕机瞬间仍可能损失尚未封闭的数据。
- Pipe 不同步删除操作；需要删除或 TTL 变更时必须制定两端一致的运维步骤。
- 只有两个节点时，完全网络分区下不存在多数派，任何纯双机方案都无法数学上
  同时保证“持续可写”和“绝不双主”。本实现能自动处理进程、主机、网卡故障，
  并在 HA TCP 仍可达时根据对端实际 VIP 和优先级消除双主；要覆盖完全隔离，
  应增加第三见证节点（可评估 `10.8.8.68`），不能靠脚本猜测。

## 2. 拓扑与状态机

| 项目 | A 节点 | B 节点 |
|---|---|---|
| 物理 IP | `10.8.8.66` | `10.8.8.67` |
| 主网卡 | `eno2` | `eno2` |
| VRRP 优先级 | 150 | 100 |
| Pipe 名称 | `ha_a_to_b` | `ha_b_to_a` |
| 业务 VIP | `10.8.8.65/24` | `10.8.8.65/24` |

状态变化如下：

1. 备机先由 nftables 关闭业务和 Pipe 出口，删除本机可能遗留的 outgoing Pipe，
   报告 `STANDBY_READY` 后才启动 Keepalived。
2. 新主拿到 VIP 后先保持业务门关闭，查询对端是否实际持有 VIP；确认没有
   可检测双主后才打开 client-only 短租约。
3. 对端不可达时进入 `MASTER_SOLO`，业务继续写本机，不创建无法连接的 Pipe。
4. 对端恢复并报告 `STANDBY_READY` 后，创建 history + realtime 单方向 Pipe；
   history 积压连续三次为 0 后删除 history，只保留 realtime。
5. 降级时先关闭业务/Pipe 租约并让出 VIP，再在备机路径清理旧 Pipe；慢 SQL
   不会阻塞 VIP 让位。

所有放行都是 10 秒 nftables 短租约，并带角色、VIP、控制器代次三重校验；
同步代理还有 6 秒文件租约。旧线程、迟到的 Keepalived 异步回调或旧代次 token
不能重新开放出口。控制器主循环无进展且不处于有截止时间的 SQL 操作时，
看门狗先 fail-closed，再终止关键子进程，由 Docker 自动重启整个节点。

## 3. 带宽和资源保护

默认值以“不影响业务”为优先级，而不是追求最快追平：

| 控制层 | 默认值 | 作用 |
|---|---:|---|
| Pipe 单任务限速 | `20 MiB/s` | 约 167.8 Mbit/s |
| Pipe 全局限速 | `20 MiB/s` | 防止参数遗漏或多 sink 叠加 |
| 代理 TBF 硬上限 | `180 Mbit/s` | 1 Gbit/s 链路约 18%，限制突发 |
| TBF burst / latency | `1 MiB / 100 ms` | 平滑发送，不占满物理链路 |
| 活跃发送并发 | `2` | 限制序列化、连接和 CPU 压力 |
| batch / 压缩 | `1 MiB / LZ4` | CPU、内存和网络的保守折中 |
| 同步代理容器 | `0.5 CPU / 128 MiB` | 同步异常不能拖垮业务服务 |
| IoTDB HA 容器 | `24 GiB` 上限 | DataNode 18G + ConfigNode 2G，留守护开销 |

IoTDB 的速率参数控制 RPC payload 平均值，独立代理的 TBF 控制实际网络出口；
两层必须同时保留。代理使用独立 Docker bridge，TBF 只作用于其 veth，禁止在
宿主机 `eno2` 上替换根 qdisc。全量追平时若业务 CPU、磁盘 iowait 或延迟升高，
优先把 `PIPE_RATE_LIMIT_BYTES_PER_SECOND` 和 `PIPE_QOS_RATE_MBIT` 下调，
不要增加并发。

`SHOW PIPE` 查询错误与“确认 Pipe 未运行”是三态处理：单次 CLI/负载抖动只报
DEGRADED，不会 DROP/CREATE；连续 3 次确认异常才修复，失败后按 15、30、60…
最高 300 秒退避。DROP 未确认成功时禁止 CREATE，避免全量同步风暴放大故障。

## 4. 目录和密钥

每台机器使用独立的新目录，不复用或删除旧 IoTDB 数据：

```text
/srv/iotdb-ha/
├── app/                 # 本目录 docker/iotdb/ha 的部署副本
├── data/                # 新 IoTDB 1.3.7 数据
├── logs/
├── state/
└── secrets/
    ├── iotdb_admin_password
    └── vrrp_auth_pass
```

密钥文件权限设为 `0600`。`iotdb_admin_password` 初装可为 `root`；投产轮换时两端
必须使用相同的 16–64 位字母数字密码，并在当前方向重建 Pipe。VRRP PASS 最多
8 位字母数字，两端一致。不得把密码写入 `node.env`、Compose 或日志。

## 5. 部署

1. 确认 VIP 未被占用，`6667/16667/16668` 空闲，并确认
   `172.31.255.0/29` 不与现有 Docker 网络或路由冲突。
2. 把构建好的 AMD64 镜像导入两端：

   ```bash
   gzip -dc iotdb-ha-1.3.7-ha1-amd64.tar.gz | docker load
   ```

3. 复制 `docker/iotdb/ha` 到 `/srv/iotdb-ha/app`，由
   `node.env.example` 分别生成 A/B 的 `node.env`。B 需要交换
   `LOCAL_IP/PEER_IP`、设置优先级 `100/150`、改 `NODE_ID=B`、
   `VRRP_ROUTER_ID=IOTDB_HA_B`、`PIPE_BASE_NAME=ha_b_to_a`。
4. 验证配置，必须经过统一入口：

   ```bash
   cd /srv/iotdb-ha/app
   ./bin/ha-compose.sh config
   ```

5. 为了首次确定 A 为主，先启动 A，等到 `MASTER_SOLO` 且 VIP 存在，再启动 B：

   ```bash
   ./bin/ha-compose.sh up -d
   docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
   ```

6. B 达到 `STANDBY_READY` 后，A 会自动创建 Pipe。最终 A 应为
   `MASTER_SYNCED/REALTIME`，B 为 `BACKUP/STANDBY_READY/NONE`。

## 6. 验收标准

- 任意时刻只有一台持有 `10.8.8.65`，且只有该节点 nft `vip_write` 集合有元素。
- 只有当前主存在 outgoing Pipe；备机 `SHOW PIPES` 为空。
- `SHOW PIPES` 中密码为 `******`，sink 包含 20 MiB/s、并发 2、1 MiB、LZ4。
- 同步代理 `tc qdisc show dev eth0` 显示 `rate 180Mbit burst 1024Kb`。
- 正常写入后两端 `count/sum/max_time` 一致。
- 停止当前主后，无人工操作由备机拿到 VIP 并可写；旧主恢复后保持备机，
  新主仍持 VIP（`nopreempt`），反向 Pipe 自动补齐故障期间写入。
- 控制器停止进展时，业务和 Pipe 租约自动关闭，容器自动重启。
- 同步期间宿主机物理网卡平均同步流量不超过 180 Mbit/s，并观察业务延迟、
  CPU、内存、磁盘 iowait 无异常。

本地 AMD64 镜像的双节点验证结果：初始三点两端均为
`count=3, sum=60, max_time=3000`；停止主节点后约 3 秒自动接管，在新主写入
第 4 点；旧主恢复后自动反向全量补齐，两端为
`count=4, sum=100, max_time=4000`，且高优先级旧主未抢回 VIP。代理 TBF 为
`180Mbit / 1024Kb / 100ms`，Pipe 参数和密码脱敏均已实测。

## 7. 现场部署验收记录（2026-07-14）

已在 `10.8.8.66 / 10.8.8.67` 实际部署并完成双向故障注入。两端加载的镜像均为
`sha256:f381daa308db0ef68b970fa000a12d83bb9e48e189f6ef6a7d4ddbd07d09f6f6`，
离线镜像包 SHA-256 为
`fe3801046b1fca5f442711d07b580ef21ee2ed85862f04c83b52bc026c0c9ef1`。

现场验收结果：

- 初始由 A 持有 VIP，经 VIP 写入 3 点；A、B 均为
  `count=3, sum=60, max_time=3000`，B 的 `SHOW PIPES` 为空。
- 停止 A 后，B 从停止命令发出到本机获得 VIP 用时约 `0.852 s`，无需确认；
  经 VIP 在 B 写入第 4 点后为 `count=4, sum=100, max_time=4000`。
- A 恢复后保持 BACKUP，B 自动创建反向 Pipe 并补齐 A；A 直接查询得到同一结果。
- 停止 B 后，A 从停止命令发出到获得 VIP 用时约 `0.714 s`；经 VIP 在 A
  写入第 5 点后为 `count=5, sum=150, max_time=5000`。
- B 恢复后自动追平。最终 A 为 `MASTER_SYNCED/REALTIME`，B 为
  `BACKUP/STANDBY_READY/NONE`；A 仅有一个 RUNNING realtime Pipe，B 无
  outgoing Pipe，两端验收数据完全一致。
- 从外部来源对 A、B 物理 IP 的 `6667` 发起连接，两端均在主机抓包中返回
  TCP RST；业务只允许通过 `10.8.8.65:6667`，避免绕过 VIP 向备机写入。

限速采用独立、受控的 128 MiB 实流量验证：代理实际发送 `134355916` 字节，
用时 `6.197 s`，平均 `173.45 Mbit/s`；TBF 显示
`rate 180Mbit burst 1024Kb lat 100ms`、`dropped 0`。A/B 的宿主机 `eno2`
仍是原有 `mq/pfifo_fast`，未挂 TBF，因此不会把业务总网卡一起限速。

验收后的瞬时资源快照：A 的 IoTDB 为 `22.83% CPU / 1.505 GiB`，同步代理为
`0.62% CPU / 6.113 MiB`；B 的 IoTDB 为 `12.48% CPU / 1.343 GiB`，同步代理为
`0.91% CPU / 4.156 MiB`。代理上限仍是 `0.5 CPU / 128 MiB`。A 上原有
`iotcloud/postgres/redis/cassandra/kafka` 容器在整个切换和限速验收后均保持运行。

## 8. 回滚和故障处置

回滚时先把业务连接从 VIP 切回明确的单节点，再执行
`./bin/ha-compose.sh down`。保留 `/srv/iotdb-ha/data`、日志、state 和镜像；
禁止删除、覆盖或拿另一端目录直接覆盖当前目录。需要恢复时按时间点和主从角色
判断数据新旧，先做只读核对，再决定数据迁移。

出现 `MASTER_SYNC_DEGRADED_*` 时 VIP 继续留在当前主，避免切到可能落后的备机；
先查代理、网络、Pipe backlog 和磁盘，不要手工同时启动反向 Pipe。出现完全
网络分区时，双机方案的安全边界已经触发，应先隔离写入口或启用第三见证方案。
