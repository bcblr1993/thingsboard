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

set -euo pipefail

source /opt/iotdb-ha/bin/common.sh
validate_runtime_env

mkdir -p "${RUNTIME_DIR}" "${STATE_DIR}"
[[ -r "${IOTDB_ADMIN_PASSWORD_FILE}" ]] || die "IoTDB admin password file is not readable"
[[ -r "${VRRP_AUTH_FILE}" ]] || die "VRRP auth file is not readable"

atomic_write "${VRRP_STATE_FILE}" STARTING
atomic_write "${AGENT_STATE_FILE}" STARTING
atomic_write "${PIPE_STATE_FILE}" UNKNOWN

# 第一条动作必须是内核 fail-closed。IoTDB 的持久 Pipe 会在进程重启时自动恢复，
# 因此不能先启动数据库、再补防火墙。
/opt/iotdb-ha/bin/fence.sh init

DATANODE_MEMORY_SIZE=${DATANODE_MEMORY_SIZE:-18G}
CONFIGNODE_MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE:-2G}
[[ "${DATANODE_MEMORY_SIZE}" =~ ^[0-9]+[GM]$ ]] \
  || die "DATANODE_MEMORY_SIZE must use the format 18G or 18432M"
[[ "${CONFIGNODE_MEMORY_SIZE}" =~ ^[0-9]+[GM]$ ]] \
  || die "CONFIGNODE_MEMORY_SIZE must use the format 2G or 2048M"
sed -i -E "0,/^MEMORY_SIZE=.*/s//MEMORY_SIZE=${DATANODE_MEMORY_SIZE}/" /iotdb/conf/datanode-env.sh
sed -i -E "0,/^MEMORY_SIZE=.*/s//MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE}/" /iotdb/conf/confignode-env.sh
grep -Fqx "MEMORY_SIZE=${DATANODE_MEMORY_SIZE}" /iotdb/conf/datanode-env.sh \
  || die "failed to configure DataNode memory budget"
grep -Fqx "MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE}" /iotdb/conf/confignode-env.sh \
  || die "failed to configure ConfigNode memory budget"
log "IoTDB memory budgets configured: DataNode=${DATANODE_MEMORY_SIZE}, ConfigNode=${CONFIGNODE_MEMORY_SIZE}"

/opt/iotdb-ha/bin/render-keepalived.sh "${RUNTIME_DIR}/keepalived.conf"

iotdb_pid=
health_pid=
status_pid=
controller_pid=
keepalived_pid=
shutting_down=false

stop_fast() {
  local pid=${1:-} grace=${2:-5} i
  [[ -n "${pid}" ]] || return 0
  kill -TERM "${pid}" >/dev/null 2>&1 || true
  for ((i = 0; i < grace; i++)); do
    kill -0 "${pid}" >/dev/null 2>&1 || break
    sleep 1
  done
  kill -KILL "${pid}" >/dev/null 2>&1 || true
  wait "${pid}" >/dev/null 2>&1 || true
}

shutdown_all() {
  local rc=${1:-0}
  [[ "${shutting_down}" == false ]] || return 0
  shutting_down=true
  set +e

  log "shutting down HA node (rc=${rc})"
  atomic_write "${AGENT_STATE_FILE}" STOPPING
  /opt/iotdb-ha/bin/fence.sh shutdown >/dev/null 2>&1 || true

  # 先停角色控制器和 Keepalived，让 priority-0 通告/VRRP 超时立即触发
  # 对端接管；本地 FLUSH 或 IoTDB 停机不能阻塞故障切换。
  stop_fast "${controller_pid}" 2
  controller_pid=
  stop_fast "${keepalived_pid}" 5
  keepalived_pid=

  if [[ $(file_age_seconds "${SQL_OK_FILE}") -le 25 ]]; then
    run_sql 'FLUSH;' >/dev/null 2>&1 || true
  fi

  stop_fast "${status_pid}" 2
  stop_fast "${health_pid}" 2

  [[ -n "${iotdb_pid}" ]] && kill -TERM "${iotdb_pid}" >/dev/null 2>&1 || true
  [[ -n "${iotdb_pid}" ]] && wait "${iotdb_pid}" >/dev/null 2>&1 || true
  exit "${rc}"
}

trap 'shutdown_all 143' TERM INT QUIT

log "starting IoTDB 1.3.7 behind closed client and Pipe egress leases"
/iotdb/sbin/entrypoint.sh all &
iotdb_pid=$!
printf '%s\n' "${iotdb_pid}" > "${RUNTIME_DIR}/iotdb.pid"

/opt/iotdb-ha/bin/status-server.sh &
status_pid=$!
printf '%s\n' "${status_pid}" > "${RUNTIME_DIR}/status-server.pid"

/opt/iotdb-ha/bin/health-loop.sh &
health_pid=$!
printf '%s\n' "${health_pid}" > "${RUNTIME_DIR}/health-loop.pid"

deadline=$((SECONDS + 240))
until [[ $(file_age_seconds "${TCP_OK_FILE}") -le ${TCP_HEALTH_MAX_AGE_SECONDS:-5} \
         && $(file_age_seconds "${SQL_OK_FILE}") -le ${SQL_HEALTH_MAX_AGE_SECONDS:-35} ]]; do
  kill -0 "${iotdb_pid}" 2>/dev/null || die "IoTDB exited during startup"
  (( SECONDS < deadline )) || die "IoTDB semantic health did not become ready within 240 seconds"
  sleep 2
done
log "IoTDB semantic health is ready"

/opt/iotdb-ha/bin/role-controller.sh &
controller_pid=$!
printf '%s\n' "${controller_pid}" > "${RUNTIME_DIR}/controller.pid"

deadline=$((SECONDS + 120))
until [[ $(file_age_seconds "${CONTROLLER_OK_FILE}") -le 4 \
         && "$(read_state "${AGENT_STATE_FILE}" UNKNOWN)" == STANDBY_READY ]]; do
  kill -0 "${controller_pid}" 2>/dev/null || die "role controller exited during startup"
  (( SECONDS < deadline )) || die "controller did not fence and drop stale outgoing Pipes"
  sleep 1
done

log "starting Keepalived after stale Pipe cleanup and controller heartbeat"
keepalived --dont-fork --log-console --log-detail -f "${RUNTIME_DIR}/keepalived.conf" &
keepalived_pid=$!
printf '%s\n' "${keepalived_pid}" > "${RUNTIME_DIR}/keepalived.pid"

set +e
wait -n "${iotdb_pid}" "${health_pid}" "${status_pid}" "${controller_pid}" "${keepalived_pid}"
child_rc=$?
set -e
log "a critical child process exited with rc=${child_rc}"
shutdown_all "${child_rc}"
