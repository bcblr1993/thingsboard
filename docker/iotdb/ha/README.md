# Apache IoTDB 1.3.7 双机自动热备

本目录提供两台 standalone IoTDB 的自动主备交付物。Keepalived 提供业务 VIP，
角色控制器只允许当前 VIP 主节点创建单方向 Pipe；故障切换后，新主自动把同步方向
反转到恢复后的旧主。两个方向不会同时运行，避免 Pipe 数据回环。

完整现场实施、SQL 验收和回滚证据见
[IoTDB 1.3.7 双机自动热备实施手册](../../../docs/iotdb/IoTDB-1.3.7双机自动热备实施手册.md)。

## 支持范围

| 项目 | 当前基线 |
|---|---|
| IoTDB | `1.3.7-standalone` |
| 主机 | Linux AMD64 |
| 容器 | Docker 23+ |
| Compose | `docker compose` v2 或独立 `docker-compose` v2.16 |
| 网络 | 两节点同一可达网段，支持 VRRP 协议 112 |
| 数据目录 | `/srv/iotdb-ha/data` |

示例拓扑：

- A：`10.8.8.66`，优先级 150。
- B：`10.8.8.67`，优先级 100。
- 业务 VIP：`10.8.8.65:6667`。
- 业务程序只能连接 VIP，不配置两个物理 IP。

这些地址可以在 `node.env` 修改。代理子网 `172.31.255.0/29` 也必须根据现场路由
检查后使用。

## 工作原理

1. 启动时先关闭业务和 Pipe 出口，清理本机遗留 outgoing Pipe。
2. Keepalived 选出的主机获得 VIP，但业务门仍保持关闭。
3. 控制器确认对端没有 VIP 后，以短租约开放业务端口。
4. 对端可用时创建 history + realtime Pipe，历史追平后只保留 realtime。
5. 主机故障后备机自动接管；旧主恢复后成为备机，由新主反向补齐。
6. 状态机失去进展时，短租约自动失效并由 Docker 重启容器。

物理节点的 `6667` 对普通客户端保持拒绝，只允许 VIP 写入和对端复制流量。

## 资源保护

| 控制 | 默认值 |
|---|---:|
| Pipe 单任务限速 | `20 MiB/s` |
| Pipe 全局限速 | `20 MiB/s` |
| 代理 TBF | `180 Mbit/s` |
| burst / latency | `1 MiB / 100 ms` |
| 活跃发送并发 | `2` |
| batch / 压缩 | `1 MiB / LZ4` |
| 代理容器 | `0.5 CPU / 128 MiB` |
| IoTDB 容器 | `24 GiB` 上限 |

TBF 只挂在同步代理的 veth `eth0`，不会修改宿主机业务网卡的根 qdisc。

## 文件说明

```text
docker/iotdb/ha/
├── Dockerfile
├── docker-compose.yml
├── node.env.example
└── bin/
    ├── entrypoint-ha.sh
    ├── role-controller.sh
    ├── fence.sh
    ├── pipe-proxy.sh
    ├── health-loop.sh
    ├── ha-status.sh
    └── ha-compose.sh
```

`node.env`、密钥、数据、日志、状态和镜像归档均已加入 `.gitignore`，不得提交。

## 构建镜像

在仓库根目录执行：

```bash
docker build --platform linux/amd64 \
  -t local/iotdb-ha:1.3.7-ha1 \
  docker/iotdb/ha
```

离线交付：

```bash
docker save local/iotdb-ha:1.3.7-ha1 | gzip \
  > iotdb-ha-1.3.7-ha1-amd64.tar.gz
sha256sum iotdb-ha-1.3.7-ha1-amd64.tar.gz
```

## 服务器目录

```text
/srv/iotdb-ha/
├── app/
├── data/
├── logs/
├── state/
├── backups/
└── secrets/
    ├── iotdb_admin_password
    └── vrrp_auth_pass
```

`secrets` 目录权限为 `0700`，两个密钥文件为 `0600`。两端 IoTDB 管理密码一致；
VRRP 密码只允许 1–8 位字母数字并保持一致。

## 节点配置

```bash
cd /srv/iotdb-ha/app
cp node.env.example node.env
chmod 0600 node.env
```

A 可使用示例身份。B 需要交换 `LOCAL_IP/PEER_IP`，并设置：

```text
NODE_ID=B
VRRP_PRIORITY=100
PEER_PRIORITY=150
VRRP_ROUTER_ID=IOTDB_HA_B
PIPE_BASE_NAME=ha_b_to_a
```

## 启动

两端先验证：

```bash
./bin/ha-compose.sh config --quiet
```

首次部署必须先启动 A：

```bash
./bin/ha-compose.sh up -d
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
```

等待 A 为 `MASTER_SOLO` 并持有 VIP 后，再在 B 执行同一启动命令。最终应为：

```text
A: MASTER / MASTER_SYNCED / REALTIME / VIP=YES
B: BACKUP / STANDBY_READY / NONE / VIP=NO
```

必须通过 `bin/ha-compose.sh`，或显式传入 `--env-file node.env`。Compose 的 service
`env_file` 不负责 YAML 变量插值，遗漏后可能使用错误的代理地址和子网。

## 常用操作

```bash
./bin/ha-compose.sh ps
./bin/ha-compose.sh logs --tail 200 iotdb-ha
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
docker exec iotdb-pipe-proxy tc -s qdisc show dev eth0
```

停止本机并触发对端接管：

```bash
./bin/ha-compose.sh stop iotdb-ha pipe-proxy
```

恢复：

```bash
./bin/ha-compose.sh up -d
```

## 验收

- 只有一台持有 VIP。
- 只有主机存在 outgoing Pipe，备机 `SHOW PIPES` 为空。
- 经 VIP 写入后两端 `count/sum/max_time` 一致。
- 双向停止主机时均自动接管，无人工确认。
- 恢复节点能自动补齐停机期间写入。
- qdisc 为 `rate 180Mbit burst 1024Kb lat 100ms`，代理无异常丢包。
- 业务容器、CPU、内存、磁盘 iowait 和延迟没有异常。

## 重要边界

- Pipe 是异步复制，RPO 不能严格为零。
- Pipe 不同步删除和 TTL 运维操作，需要两端一致执行。
- 两节点完全网络分区没有多数派；需要绝对防双主时必须增加第三见证节点。
- 不要人工同时创建 A→B 和 B→A Pipe，也不要绕过 VIP 直写物理节点。
