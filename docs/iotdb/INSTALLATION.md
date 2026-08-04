# 安装与部署指南

本文覆盖三种使用方式：构建 ThingsBoard、启动单机 IoTDB 进行开发联调，以及部署
IoTDB 1.3.7 双机自动热备。生产部署前必须先在目标网络完成故障切换和数据回补验收。

## 1. 版本与资源要求

| 组件 | 要求 |
|---|---|
| 操作系统 | Linux AMD64；开发环境也可使用 macOS |
| Java | JDK 17 |
| Maven | 3.8 或更高 |
| Node.js / Yarn | Maven 前端插件下载 Node.js 20.18.0、Yarn 1.22.22 |
| Docker | 23 或更高 |
| Docker Compose | v2；同时兼容独立命令 `docker-compose` v2.16 |
| IoTDB | 1.3.7 |

完整 ThingsBoard 构建建议至少准备 16 GiB 内存和足够的 Maven、Node 缓存空间。
现场 HA 基线是每台 32 逻辑核、约 64 GiB 内存、1 Gbit/s 网络和独立数据盘。

## 2. 获取代码

```bash
git clone https://github.com/bcblr1993/thingsboard-4.1-iotdb-ha.git
cd thingsboard-4.1-iotdb-ha
git status -sb
```

默认分支是 `main`。不要把生产配置、`node.env`、数据目录或密钥复制进 Git 跟踪目录。

## 3. 构建 ThingsBoard

### 3.1 全量构建

```bash
java -version
mvn -version
mvn clean install -DskipTests
```

应用包生成在 `application/target/`。前端 Node.js 和 Yarn 由 `ui-ngx/pom.xml` 中的
Maven 插件管理，不要求全局安装同版本 Node.js。

### 3.2 只验证 IoTDB DAO 模块

```bash
mvn -pl dao -am compile -DskipTests
```

如果修改了 IoTDB 写入或查询实现，至少运行相关单元测试，并在真实 IoTDB 1.3.7
环境执行容量或回归工具。不要仅凭编译成功判断生产可用。

## 4. 本地单机 IoTDB

```bash
docker compose -f docker/iotdb/docker-compose-standalone.yml up -d
docker compose -f docker/iotdb/docker-compose-standalone.yml ps
docker logs --tail 100 iotdb
```

本地联调配置：

```bash
export DATABASE_TS_TYPE=iotdb
export DATABASE_TS_LATEST_TYPE=iotdb
export IOTDB_NODE_URLS=127.0.0.1:6667
export IOTDB_USER=root
export IOTDB_PASSWORD='<local-only-password>'
export IOTDB_DATABASE=root.tb
```

如果继续用 Redis 保存最新值，将 `DATABASE_TS_LATEST_TYPE` 设置为 `redis`。

停止本地实例：

```bash
docker compose -f docker/iotdb/docker-compose-standalone.yml down
```

不要在仍需保留数据时附加 `-v`。

## 5. 构建 HA 镜像

HA 镜像固定基于 IoTDB 1.3.7 standalone，并加入 Keepalived、nftables、状态控制器
和限速代理：

```bash
docker build --platform linux/amd64 \
  -t local/iotdb-ha:1.3.7-ha1 \
  docker/iotdb/ha

docker image inspect local/iotdb-ha:1.3.7-ha1 \
  --format '{{.Id}} {{.Architecture}} {{.Os}}'
```

离线交付：

```bash
docker save local/iotdb-ha:1.3.7-ha1 | gzip \
  > iotdb-ha-1.3.7-ha1-amd64.tar.gz
sha256sum iotdb-ha-1.3.7-ha1-amd64.tar.gz
```

导入目标机前必须再次核对 SHA-256。

## 6. 准备两台 HA 服务器

以下地址是已验证示例，可在 `node.env` 中替换：

| 项目 | A | B |
|---|---|---|
| 物理 IP | `10.8.8.66` | `10.8.8.67` |
| 网卡 | `eno2` | `eno2` |
| VRRP 优先级 | 150 | 100 |
| VIP | `10.8.8.65/24` | `10.8.8.65/24` |

两端执行：

```bash
install -d -m 0755 \
  /srv/iotdb-ha/app \
  /srv/iotdb-ha/data \
  /srv/iotdb-ha/logs \
  /srv/iotdb-ha/state \
  /srv/iotdb-ha/backups
install -d -m 0700 /srv/iotdb-ha/secrets
```

上线前检查：

```bash
ss -lnt | grep -E ':(6667|16667|16668)\b' || true
docker network ls
ip -4 address show
ip route
```

必须确认 VIP 未被占用、端口空闲、`172.31.255.0/29` 不与现场路由或 Docker 网络冲突。

## 7. 下发应用和节点配置

把 `docker/iotdb/ha/` 复制到两端 `/srv/iotdb-ha/app/`，然后：

```bash
cd /srv/iotdb-ha/app
cp node.env.example node.env
chmod 0600 node.env
```

A 保持示例身份；B 至少修改：

```text
NODE_ID=B
LOCAL_IP=10.8.8.67
PEER_IP=10.8.8.66
VRRP_PRIORITY=100
PEER_PRIORITY=150
VRRP_ROUTER_ID=IOTDB_HA_B
PIPE_BASE_NAME=ha_b_to_a
```

密钥文件：

```text
/srv/iotdb-ha/secrets/iotdb_admin_password
/srv/iotdb-ha/secrets/vrrp_auth_pass
```

两端的 IoTDB 管理密码必须一致；VRRP PASS 只使用 1–8 位字母数字并保持一致。
文件权限必须为 `0600`。不要在命令历史、`node.env` 或 Compose 中写真实密码。

## 8. 验证并启动

两端先验证配置：

```bash
cd /srv/iotdb-ha/app
./bin/ha-compose.sh config --quiet
```

首次上线顺序：

1. 只启动 A。
2. 确认 A 为 `MASTER_SOLO`、持有 VIP 且容器 healthy。
3. 启动 B。
4. 等待 A 进入 `MASTER_SYNCED/REALTIME`，B 进入 `BACKUP/STANDBY_READY/NONE`。

启动命令：

```bash
./bin/ha-compose.sh up -d
docker exec iotdb-ha /opt/iotdb-ha/bin/ha-status.sh
```

必须使用 `ha-compose.sh`。该脚本同时支持 `docker compose` 和独立的
`docker-compose`，并强制传入 `--env-file node.env`，避免网络变量静默采用默认值。

## 9. ThingsBoard 接入 VIP

只有在 HA 状态和数据一致性验收通过后，才切换应用：

```bash
export DATABASE_TS_TYPE=iotdb
export DATABASE_TS_LATEST_TYPE=iotdb
export IOTDB_NODE_URLS=10.8.8.65:6667
export IOTDB_USER='<iotdb-user>'
export IOTDB_PASSWORD='<from-secret-store>'
export IOTDB_DATABASE=root.tb
export IOTDB_ENABLE_REDIRECTION=true
export IOTDB_ENABLE_AUTO_FETCH=true
```

`IOTDB_NODE_URLS` 只能写 VIP。不要同时配置 A/B 物理 IP，否则应用可能绕过 fencing
直接连接备机。

## 10. 上线验收

- A/B 任意时刻只有一台持有 VIP。
- 主机只有一个 RUNNING realtime Pipe；备机 `SHOW PIPES` 为空。
- 经 VIP 写入数据后，两端 `count/sum/max_time` 一致。
- 停止主机后备机自动接管，经 VIP 仍能写入。
- 原主恢复后保持备机，并自动补齐停机期间的数据。
- 代理 qdisc 为 `180Mbit / 1024Kb / 100ms`，宿主机业务网卡未挂 TBF。
- 同步期间 CPU、内存、磁盘 iowait 和业务延迟符合现场阈值。

完整 SQL、现场结果和回滚方法见
[双机自动热备实施手册](IoTDB-1.3.7双机自动热备实施手册.md)。

## 11. 回滚

回滚前先停止应用写入或将应用切到经过确认的单节点，随后：

```bash
cd /srv/iotdb-ha/app
./bin/ha-compose.sh down
```

保留 `/srv/iotdb-ha/data`、`logs`、`state`、`backups` 和离线镜像。禁止用另一端
数据目录直接覆盖当前目录；必须先比较数据时间点，再制定恢复方案。
