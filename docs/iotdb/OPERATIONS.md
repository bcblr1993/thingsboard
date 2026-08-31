# IoTDB HA 日常运维与故障处理

本文默认 HA 安装在 `/srv/iotdb-ha`。所有 Compose 操作都通过
`/srv/iotdb-ha/app/bin/ha-compose.sh` 执行。

## 1. 正常状态

| 节点 | VRRP | Agent | Pipe | VIP |
|---|---|---|---|---|
| 当前主 | `MASTER` | `MASTER_SYNCED` | `REALTIME` | `YES` |
| 备用机 | `BACKUP` | `STANDBY_READY` | `NONE` | `NO` |

对端临时不可达时，主机可能显示 `MASTER_SOLO/NONE`。业务仍由 VIP 承接；对端恢复
为 `STANDBY_READY` 后，主机会自动执行历史补齐并回到 `MASTER_SYNCED/REALTIME`。

## 2. 每日检查

```bash
cd /srv/iotdb-ha/app
./bin/ha-compose.sh ps
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
docker exec iotdb-pipe-proxy tc -s qdisc show dev eth0
df -h /srv/iotdb-ha/data /srv/iotdb-ha/logs
free -h
```

同时检查：

- 任意时刻仅一台服务器存在 VIP。
- 主机 Pipe 的 `RemainingEventCount` 不持续增长。
- 备机 `SHOW PIPES` 为空。
- 容器没有持续重启，日志没有反复 `CREATE/DROP PIPE`。
- 磁盘使用率、iowait、应用写入延迟和失败计数没有异常。

## 3. 常用命令

```bash
cd /srv/iotdb-ha/app

# 启动或恢复
./bin/ha-compose.sh up -d

# 查看状态
./bin/ha-compose.sh ps
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh

# 查看日志
./bin/ha-compose.sh logs --tail 200 iotdb-ha
./bin/ha-compose.sh logs --tail 200 pipe-proxy

# 优雅停止本机 HA
./bin/ha-compose.sh stop iotdb-ha pipe-proxy
```

不要手工给备机创建 Pipe，也不要在两端同时运行 outgoing Pipe。

## 4. 自动切换行为

主机、IoTDB 进程或主网卡故障时：

1. 原主短租约过期或主动 fencing，业务端口关闭。
2. 备机自动获得 VIP，无需人工确认。
3. 新主在对端不可达时以 `MASTER_SOLO` 承接写入。
4. 旧主恢复后先清理旧方向 Pipe，再作为备机等待新主反向补齐。

`nopreempt` 避免恢复节点立刻抢回 VIP。若需要最终恢复 A 为主，应选择维护窗口，
先确认两端数据一致，再停止当前 B 主机，让 A 自动接管，最后重新启动 B。

## 5. 带宽和资源调整

默认限制：

- Pipe 单任务和全局：`20 MiB/s`。
- 网络代理 TBF：`180 Mbit/s`。
- Pipe 活跃发送并发：`2`。
- 代理：`0.5 CPU / 128 MiB`。

修改 `/srv/iotdb-ha/app/node.env` 时，两端保持一致：

```text
PIPE_RATE_LIMIT_BYTES_PER_SECOND=20971520
PIPE_QOS_RATE_MBIT=180
PIPE_PARALLEL_TASKS=2
```

如果同步影响业务，优先降低带宽，不要增加并发。变更后执行：

```bash
./bin/ha-compose.sh up -d --force-recreate
```

随后重新检查主备状态、qdisc 和数据一致性。TBF 只能挂在同步代理 `eth0`，禁止修改
宿主机 `eno2` 的根 qdisc。

## 6. 常见故障

### `MASTER_SOLO` 持续存在

检查对端容器、`16668/tcp`、VRRP 协议 112、物理路由和 nftables。不要为了消除
状态提示而手工创建 Pipe；对端恢复为 `STANDBY_READY` 后系统会自行修复。

### `MASTER_SYNC_DEGRADED_*`

检查：

```bash
docker logs --tail 300 iotdb-ha
docker logs --tail 300 iotdb-pipe-proxy
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
docker exec iotdb-pipe-proxy tc -s qdisc show dev eth0
```

确认对端 IoTDB、磁盘空间、代理租约、Pipe backlog 和网络是否正常。状态机对单次
查询抖动只降级，连续确认异常后才重建，并有最高 300 秒退避；不要并行运行人工重建。

### 两端都没有 VIP

先检查两端容器健康、Keepalived 日志、网卡名和 `node.env`。系统在角色不一致时会
fail-closed，宁可暂时没有写入口也不会贸然开放双写。确认不存在双主后再恢复容器。

### 完全网络分区

纯双机没有多数派，无法同时保证持续写入和绝不双主。不要解除 fencing 强行双写。
对该故障有强一致要求时，应增加第三见证节点或使用带多数派的一致性架构。

## 7. 备份

至少备份：

- `/srv/iotdb-ha/data`
- `/srv/iotdb-ha/app/node.env`（按敏感配置保管）
- `/srv/iotdb-ha/secrets`（加密、限制权限）
- 离线镜像及其 SHA-256
- `/srv/iotdb-ha/DEPLOYMENT-REPORT.md`

数据备份应使用 IoTDB 官方快照/备份能力或在一致性维护窗口执行。不要在写入过程中
直接复制活跃数据目录并把文件副本当作一致性备份。

## 8. 升级

1. 备份并记录当前镜像 ID、配置、角色和数据校验结果。
2. 在隔离环境构建并验证新镜像。
3. 先升级备机，保持其无 VIP、无 outgoing Pipe。
4. 验证后在维护窗口切换，让已升级节点承接业务。
5. 升级另一端并等待追平。
6. 再做一次双向切换、数据一致性和限速测试。

IoTDB 大版本升级不能只替换镜像，必须同时遵循官方数据格式和兼容性流程。

## 9. 安全边界

- 密码只放在 `/srv/iotdb-ha/secrets`，权限 `0600`。
- `node.env` 权限 `0600`，但不存密码。
- 业务只访问 VIP；外部客户端不得访问物理 IP 的 `6667`。
- Pipe 是异步复制，RPO 不严格为零；删除和 TTL 运维需要两端一致执行。
