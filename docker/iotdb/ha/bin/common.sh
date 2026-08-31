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

RUNTIME_DIR=${RUNTIME_DIR:-/run/iotdb-ha}
STATE_DIR=${STATE_DIR:-/var/lib/iotdb-ha}
VRRP_STATE_FILE="${RUNTIME_DIR}/vrrp.state"
ROLE_CHANGED_FILE="${RUNTIME_DIR}/role.changed"
AGENT_STATE_FILE="${RUNTIME_DIR}/agent.state"
SQL_OK_FILE="${RUNTIME_DIR}/sql.ok"
TCP_OK_FILE="${RUNTIME_DIR}/tcp.ok"
CONTROLLER_OK_FILE="${RUNTIME_DIR}/controller.ok"
CONTROLLER_PROGRESS_FILE="${RUNTIME_DIR}/controller.progress"
CONTROLLER_BUSY_UNTIL_FILE="${RUNTIME_DIR}/controller.busy-until"
CONTROLLER_STALLED_FILE="${RUNTIME_DIR}/controller.stalled"
LEASE_OK_FILE="${RUNTIME_DIR}/lease.ok"
PROXY_OK_FILE="${RUNTIME_DIR}/proxy.ok"
PROXY_BYTES_FILE="${RUNTIME_DIR}/proxy.tx-bytes"
FORCE_FAULT_FILE="${RUNTIME_DIR}/force-fault"
PIPE_STATE_FILE="${RUNTIME_DIR}/pipe.state"
FENCE_DESIRED_FILE="${RUNTIME_DIR}/fence.desired"

log() {
  printf '%s [iotdb-ha] %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*"
}

die() {
  log "FATAL: $*"
  exit 1
}

require_env() {
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || die "missing required environment variable: ${name}"
  done
}

validate_runtime_env() {
  require_env NODE_ID LOCAL_IP PEER_IP VIP INTERFACE VRRP_PRIORITY PEER_PRIORITY \
    VRRP_VIRTUAL_ROUTER_ID VRRP_ROUTER_ID IOTDB_RPC_PORT HA_STATUS_PORT \
    PIPE_BASE_NAME PIPE_PROXY_HOST PIPE_PROXY_PORT \
    PIPE_RATE_LIMIT_BYTES_PER_SECOND PIPE_PARALLEL_TASKS \
    PIPE_BATCH_SIZE_BYTES PIPE_COMPRESSOR IOTDB_ADMIN_PASSWORD_FILE VRRP_AUTH_FILE

  [[ "${NODE_ID}" =~ ^[A-Za-z0-9_-]+$ ]] || die "invalid NODE_ID"
  [[ "${INTERFACE}" =~ ^[A-Za-z0-9_.:-]+$ ]] || die "invalid INTERFACE"
  [[ "${PIPE_BASE_NAME}" =~ ^[A-Za-z0-9_]+$ ]] || die "invalid PIPE_BASE_NAME"
  [[ "${LOCAL_IP}" =~ ^[0-9.]+$ && "${PEER_IP}" =~ ^[0-9.]+$ && "${VIP}" =~ ^[0-9.]+$ \
      && "${PIPE_PROXY_HOST}" =~ ^[0-9.]+$ ]] \
    || die "only IPv4 LOCAL_IP/PEER_IP/VIP/PIPE_PROXY_HOST are supported"
  [[ "${VRRP_PRIORITY}" =~ ^[0-9]+$ && "${PEER_PRIORITY}" =~ ^[0-9]+$ ]] \
    || die "invalid VRRP priority"
  [[ "${IOTDB_RPC_PORT}" =~ ^[0-9]+$ && "${HA_STATUS_PORT}" =~ ^[0-9]+$ \
      && "${PIPE_PROXY_PORT}" =~ ^[0-9]+$ ]] \
    || die "invalid TCP port"
  [[ "${PIPE_RATE_LIMIT_BYTES_PER_SECOND}" =~ ^[0-9]+$ \
      && "${PIPE_PARALLEL_TASKS}" =~ ^[0-9]+$ \
      && "${PIPE_BATCH_SIZE_BYTES}" =~ ^[0-9]+$ ]] \
    || die "invalid Pipe resource limit"
  [[ "${PIPE_COMPRESSOR}" =~ ^(lz4|snappy|gzip|zstd|lzma2)$ ]] || die "invalid PIPE_COMPRESSOR"
}

atomic_write() {
  local target=$1 value=$2 temp
  temp="${target}.tmp.$$"
  printf '%s\n' "${value}" > "${temp}"
  mv -f "${temp}" "${target}"
}

read_state() {
  local file=$1 fallback=$2
  if [[ -s "${file}" ]]; then
    tr -d '\r\n' < "${file}"
  else
    printf '%s' "${fallback}"
  fi
}

file_age_seconds() {
  local file=$1 now modified
  [[ -e "${file}" ]] || { printf '%s' 999999; return; }
  now=$(date +%s)
  modified=$(stat -c %Y "${file}" 2>/dev/null || printf '0')
  printf '%s' "$((now - modified))"
}

read_admin_password() {
  [[ -r "${IOTDB_ADMIN_PASSWORD_FILE}" ]] || return 1
  tr -d '\r\n' < "${IOTDB_ADMIN_PASSWORD_FILE}"
}

run_sql() {
  local sql=$1 password rc=0 now timeout_seconds
  password=$(read_admin_password) || return 1
  timeout_seconds=${IOTDB_SQL_TIMEOUT_SECONDS:-10}
  [[ "${timeout_seconds}" =~ ^[0-9]+$ ]] || return 1

  if [[ "${CONTROLLER_WATCHDOG_ACTIVE:-false}" == true ]]; then
    now=$(date +%s)
    atomic_write "${CONTROLLER_PROGRESS_FILE}" "${now}"
    atomic_write "${CONTROLLER_BUSY_UNTIL_FILE}" "$((now + timeout_seconds + 5))"
  fi

  # CLI 是独立 JVM；极端情况下 TERM 可能卡住，必须有 KILL 截止，不能让
  # 健康探针或 HA 状态机永久等待。
  timeout --kill-after=2 "${timeout_seconds}" /iotdb/sbin/start-cli.sh \
    -h 127.0.0.1 \
    -p "${IOTDB_RPC_PORT}" \
    -u root \
    -pw "${password}" \
    -e "${sql}" || rc=$?

  if [[ "${CONTROLLER_WATCHDOG_ACTIVE:-false}" == true ]]; then
    atomic_write "${CONTROLLER_PROGRESS_FILE}" "$(date +%s)"
    rm -f "${CONTROLLER_BUSY_UNTIL_FILE}"
  fi
  return "${rc}"
}

sql_is_healthy() {
  local output
  output=$(run_sql 'SHOW VERSION;' 2>&1) || return 1
  [[ "${output}" == *"Version"* && "${output}" != *"IoTDBSQLException"* ]]
}

pipe_history_id() {
  printf '%s_history' "${PIPE_BASE_NAME}"
}

pipe_realtime_id() {
  printf '%s_realtime' "${PIPE_BASE_NAME}"
}

pipe_show() {
  local output
  output=$(run_sql 'SHOW PIPES;' 2>&1) || return 1
  [[ "${output}" != *"IoTDBSQLException"* ]] || return 1
  printf '%s\n' "${output}" \
    | grep -Eq '\|[[:space:]]*ID[[:space:]]*\|.*\|[[:space:]]*State[[:space:]]*\|' \
    || return 1
  printf '%s\n' "${output}"
}

pipe_id_exists() {
  local id=$1 output
  output=$(run_sql "SHOW PIPE ${id};" 2>&1) || return 2
  [[ "${output}" != *"IoTDBSQLException"* ]] || return 2
  printf '%s\n' "${output}" \
    | grep -Eq '\|[[:space:]]*ID[[:space:]]*\|.*\|[[:space:]]*State[[:space:]]*\|' \
    || return 2
  printf '%s\n' "${output}" | tr -d ' ' | grep -Fq "|${id}|"
}

pipe_id_is_running() {
  local id=$1 output
  output=$(run_sql "SHOW PIPE ${id};" 2>&1) || return 2
  [[ "${output}" != *"IoTDBSQLException"* ]] || return 2
  printf '%s\n' "${output}" \
    | grep -Eq '\|[[:space:]]*ID[[:space:]]*\|.*\|[[:space:]]*State[[:space:]]*\|' \
    || return 2
  printf '%s\n' "${output}" | tr -d ' ' | grep -Eq "\|${id}\|.*\|RUNNING\|"
}

drop_pipe_id() {
  local id=$1 output
  output=$(run_sql "DROP PIPE IF EXISTS ${id};" 2>&1) || return 1
  [[ "${output}" != *"IoTDBSQLException"* ]]
}

drop_outgoing_pipes() {
  local output compact id
  # 1.3.7 会把全量 Pipe 自动拆为 history/realtime；同时清理 base id，兼容
  # 早期 realtime-only 测试对象。调用前必须已经关闭 Pipe egress 租约。
  drop_pipe_id "$(pipe_history_id)" || return 1
  drop_pipe_id "$(pipe_realtime_id)" || return 1
  drop_pipe_id "${PIPE_BASE_NAME}" || return 1
  output=$(pipe_show) || return 1
  compact=$(printf '%s\n' "${output}" | tr -d ' ')
  for id in "$(pipe_history_id)" "$(pipe_realtime_id)" "${PIPE_BASE_NAME}"; do
    printf '%s\n' "${compact}" | grep -Fq "|${id}|" && return 1
  done
  return 0
}

outgoing_pipe_exists() {
  local output compact id
  output=$(pipe_show) || return 2
  compact=$(printf '%s\n' "${output}" | tr -d ' ')
  for id in "$(pipe_history_id)" "$(pipe_realtime_id)" "${PIPE_BASE_NAME}"; do
    printf '%s\n' "${compact}" | grep -Fq "|${id}|" && return 0
  done
  return 1
}

create_full_pipe() {
  local output password
  password=$(read_admin_password) || return 1
  [[ "${password}" == root || "${password}" =~ ^[A-Za-z0-9]{16,64}$ ]] \
    || { log "admin password must be root during bootstrap or 16-64 alphanumeric characters"; return 1; }

  output=$(run_sql "CREATE PIPE ${PIPE_BASE_NAME} WITH SOURCE ('source'='iotdb-source','source.history.enable'='true','source.realtime.enable'='true','source.inclusion'='data.insert') WITH SINK ('sink'='iotdb-thrift-sink','sink.node-urls'='${PIPE_PROXY_HOST}:${PIPE_PROXY_PORT}','sink.username'='root','sink.password'='${password}','sink.parallel.tasks'='${PIPE_PARALLEL_TASKS}','sink.batch.enable'='true','sink.batch.max-delay-ms'='${PIPE_BATCH_DELAY_MS:-200}','sink.batch.size-bytes'='${PIPE_BATCH_SIZE_BYTES}','sink.compressor'='${PIPE_COMPRESSOR}','sink.rate-limit-bytes-per-second'='${PIPE_RATE_LIMIT_BYTES_PER_SECOND}');" 2>&1) || {
    log "CREATE PIPE command failed: ${output}"
    return 1
  }
  [[ "${output}" != *"IoTDBSQLException"* ]] || {
    log "CREATE PIPE rejected: ${output}"
    return 1
  }

  pipe_id_is_running "$(pipe_history_id)" && pipe_id_is_running "$(pipe_realtime_id)"
}

realtime_pipe_running() {
  pipe_id_is_running "$(pipe_realtime_id)"
}

history_pipe_running() {
  pipe_id_is_running "$(pipe_history_id)"
}

history_pipe_caught_up() {
  local remaining
  remaining=$(pipe_remaining_event_count "$(pipe_history_id)") || return 1
  [[ "${remaining}" == 0 ]]
}

pipe_remaining_event_count() {
  local id=$1 output row remaining
  output=$(run_sql "SHOW PIPE ${id};" 2>&1) || return 1
  row=$(printf '%s\n' "${output}" | grep -F "|${id}" | head -n 1) || return 1
  remaining=$(printf '%s\n' "${row}" | awk -F'|' '{gsub(/[[:space:]]/, "", $9); print $9}')
  [[ "${remaining}" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "${remaining}"
}

proxy_is_healthy() {
  [[ $(file_age_seconds "${PROXY_OK_FILE}") -le ${PIPE_PROXY_HEARTBEAT_MAX_AGE_SECONDS:-4} ]]
}

local_vip_present() {
  ip -4 address show dev "${INTERFACE}" | grep -Fq " ${VIP}/"
}

vip_lease_present() {
  local output
  output=$(timeout --kill-after=1 2 nft list set inet iotdb_ha vip_write 2>/dev/null) || return 1
  printf '%s\n' "${output}" | grep -Fq "${VIP}"
}

peer_status() {
  timeout "${PEER_STATUS_TIMEOUT_SECONDS:-2}" nc -w "${PEER_STATUS_TIMEOUT_SECONDS:-2}" \
    "${PEER_IP}" "${HA_STATUS_PORT}" < /dev/null 2>/dev/null | head -n 1
}

status_value() {
  local line=$1 wanted=$2 item
  for item in ${line}; do
    case "${item}" in
      "${wanted}="*) printf '%s' "${item#*=}"; return 0 ;;
    esac
  done
  return 1
}
