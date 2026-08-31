#!/usr/bin/env bash
#
# Copyright © 2016-2025 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# 清理 IoTDB 中"已删除设备"的孤儿时序。
#
# 背景: ThingsBoard 删除设备时只删 latest 缓存, 不会删除 IoTDB 里的时序本身
#       (所有存储后端一致的原生行为)。IoTDB 下残留序列会永久占用 schema 内存
#       (~190B/条), 设备高流转的现场序列基数只增不减, 缓慢侵蚀容量水位。
#       本脚本对比"IoTDB 里的设备序列"与"PG 里存活的设备", 清理其差集(孤儿)。
#
# 安全: 默认 dry-run(只列出孤儿, 不删任何数据); 确认无误后加 --apply 才执行。
#       建议在业务低峰期运行(show devices 对超大序列基数有一定开销)。
#
# 用法:
#   ./iotdb-purge-orphan-series.sh              # dry-run, 仅列出孤儿设备
#   ./iotdb-purge-orphan-series.sh --apply      # 真正 delete timeseries
#
# 可用环境变量覆盖:
#   IOTDB_CONTAINER(默认 iotdb) PG_CONTAINER(默认 postgres) PG_DB(默认 thingsboard)
#   PG_USER(默认 postgres) IOTDB_USER/IOTDB_PASSWORD(默认 root/root) IOTDB_DATABASE(默认 root.tb)
#
set -euo pipefail

IOTDB_CONTAINER=${IOTDB_CONTAINER:-iotdb}
PG_CONTAINER=${PG_CONTAINER:-postgres}
PG_DB=${PG_DB:-thingsboard}
PG_USER=${PG_USER:-postgres}
IOTDB_USER=${IOTDB_USER:-root}
IOTDB_PASSWORD=${IOTDB_PASSWORD:-root}
DB_ROOT=${IOTDB_DATABASE:-root.tb}

APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true

cli() {
  docker exec "$IOTDB_CONTAINER" /iotdb/sbin/start-cli.sh -h 127.0.0.1 \
    -u "$IOTDB_USER" -pw "$IOTDB_PASSWORD" -e "$1" 2>/dev/null
}

echo "== IoTDB 孤儿序列清理 (dry-run=$([ "$APPLY" = true ] && echo NO || echo YES)) =="

# 1) IoTDB 里所有 DEVICE 的设备节点(u_<uuid>)
iotdb_devices=$(cli "show devices $DB_ROOT.DEVICE.**" | grep -oE "u_[0-9a-f_]{36}" | sort -u || true)

# 2) PG 里存活的设备 id, 转成同样的 u_<uuid>(横杠→下划线)
pg_devices=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A \
  -c "select 'u_'||replace(id::text,'-','_') from device" 2>/dev/null | sort -u || true)

# 3) 孤儿 = IoTDB 有、PG 没有
orphans=$(comm -23 <(echo "$iotdb_devices") <(echo "$pg_devices") | grep -E "^u_" || true)

n_iotdb=$(echo "$iotdb_devices" | grep -c . || true)
n_pg=$(echo "$pg_devices" | grep -c . || true)
n_orphan=$(echo "$orphans" | grep -c . || true)
echo "IoTDB 设备序列: $n_iotdb   PG 存活设备: $n_pg   孤儿(待清理): $n_orphan"

if [ "$n_orphan" -eq 0 ]; then
  echo "无孤儿设备, 无需清理。"
  exit 0
fi

echo "--- 孤儿设备列表 ---"
echo "$orphans"

if [ "$APPLY" != true ]; then
  echo ""
  echo "[dry-run] 未删除任何数据。确认上表无误后, 重新以 --apply 运行执行清理。"
  exit 0
fi

echo ""
echo "!! 即将 delete timeseries $n_orphan 个孤儿设备的全部序列, 5 秒后开始 (Ctrl-C 取消) !!"
sleep 5
echo "$orphans" | while read -r u; do
  [ -z "$u" ] && continue
  cli "delete timeseries $DB_ROOT.DEVICE.$u.**" >/dev/null && echo "已清理 $u"
done
echo "完成。建议随后观察 IoTDB schema 内存(allocateMemoryForSchemaRegion)回落。"
