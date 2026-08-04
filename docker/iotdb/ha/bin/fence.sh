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

TABLE=iotdb_ha
VIP_SET=vip_write
PIPE_SET=pipe_egress
LEASE=${FENCE_LEASE_SECONDS:-10}
PROXY_LEASE_FILE="${RUNTIME_DIR}/pipe-proxy.lease"
MODE=${1:-}
TOKEN=${2:-}

init_table() {
  timeout --kill-after=1 2 nft delete table inet "${TABLE}" >/dev/null 2>&1 || true
  timeout --kill-after=1 3 nft -f - <<EOF
table inet ${TABLE} {
  set ${VIP_SET} {
    type ipv4_addr
    flags timeout
    timeout ${LEASE}s
    gc-interval 1s
  }

  set ${PIPE_SET} {
    type ipv4_addr
    flags timeout
    timeout ${LEASE}s
    gc-interval 1s
  }

  chain input {
    type filter hook input priority -100; policy accept;
    ip saddr ${PEER_IP} ip protocol 112 accept
    ip protocol 112 drop
    iifname "lo" tcp dport ${IOTDB_RPC_PORT} accept
    ip saddr ${PEER_IP} tcp dport ${IOTDB_RPC_PORT} accept
    ip daddr @${VIP_SET} tcp dport ${IOTDB_RPC_PORT} accept
    tcp dport ${IOTDB_RPC_PORT} reject with tcp reset
    iifname "lo" tcp dport ${HA_STATUS_PORT} accept
    ip saddr ${PEER_IP} tcp dport ${HA_STATUS_PORT} accept
    tcp dport ${HA_STATUS_PORT} reject with tcp reset
  }

  chain output {
    type filter hook output priority -100; policy accept;
    ip daddr @${PIPE_SET} tcp dport ${PIPE_PROXY_PORT} accept
    ip daddr ${PIPE_PROXY_HOST} tcp dport ${PIPE_PROXY_PORT} reject with tcp reset
    # 永久阻断任何遗留的直连 Pipe；新 Pipe 必须经独立限速代理。
    ip daddr ${PEER_IP} tcp dport ${IOTDB_RPC_PORT} reject with tcp reset
    ip daddr @${VIP_SET} tcp dport ${IOTDB_RPC_PORT} accept
    ip daddr ${VIP} tcp dport ${IOTDB_RPC_PORT} reject with tcp reset
  }
}
EOF
}

apply_mode() {
  local allow_vip=$1 allow_pipe=$2

  # 同步代理与 HA 容器不共享网络命名空间，使用短租约文件做第二道
  # fail-closed 门。控制器停止续租后，代理会主动杀掉全部转发连接。
  if [[ "${allow_pipe}" == true ]]; then
    mkdir -p "${RUNTIME_DIR}"
  else
    rm -f "${PROXY_LEASE_FILE}"
  fi

  {
    printf 'flush set inet %s %s\n' "${TABLE}" "${VIP_SET}"
    printf 'flush set inet %s %s\n' "${TABLE}" "${PIPE_SET}"
    if [[ "${allow_vip}" == true ]]; then
      printf 'add element inet %s %s { %s timeout %ss }\n' "${TABLE}" "${VIP_SET}" "${VIP}" "${LEASE}"
    fi
    if [[ "${allow_pipe}" == true ]]; then
      printf 'add element inet %s %s { %s timeout %ss }\n' "${TABLE}" "${PIPE_SET}" "${PIPE_PROXY_HOST}" "${LEASE}"
    fi
  } | timeout --kill-after=1 2 nft -f - || return 1

  if [[ "${allow_pipe}" == true ]]; then
    atomic_write "${PROXY_LEASE_FILE}" "$(date +%s)"
  fi
}

master_guard_ok() {
  [[ "$(read_state "${VRRP_STATE_FILE}" STARTING)" == MASTER ]] \
    && local_vip_present \
    && [[ ! -e "${CONTROLLER_STALLED_FILE}" ]] \
    && [[ ! -e "${FORCE_FAULT_FILE}" ]]
}

set_fenced_locked() {
  atomic_write "${FENCE_DESIRED_FILE}" fenced
  rm -f "${LEASE_OK_FILE}"
  apply_mode false false || return 1
}

validate_lease_request() {
  local lease_mode=$1
  [[ "${lease_mode}" == client || "${lease_mode}" == synced ]] || return 2
  [[ "${TOKEN}" =~ ^[A-Za-z0-9_.:-]{8,128}$ ]] || return 2
}

authorize_lease_locked() {
  local lease_mode=$1 allow_pipe=false
  validate_lease_request "${lease_mode}" || return $?
  [[ "${lease_mode}" == synced ]] && allow_pipe=true
  rm -f "${LEASE_OK_FILE}"
  atomic_write "${FENCE_DESIRED_FILE}" "${lease_mode}:${TOKEN}"
  if master_guard_ok; then
    apply_mode true "${allow_pipe}" || return 1
    atomic_write "${LEASE_OK_FILE}" "$(date +%s)"
    return 0
  fi
  set_fenced_locked || true
  return 1
}

renew_lease_locked() {
  local lease_mode=$1 allow_pipe=false
  validate_lease_request "${lease_mode}" || return $?
  [[ "${lease_mode}" == synced ]] && allow_pipe=true
  [[ "$(read_state "${FENCE_DESIRED_FILE}" fenced)" == "${lease_mode}:${TOKEN}" ]] || return 1
  if master_guard_ok; then
    apply_mode true "${allow_pipe}" || return 1
    atomic_write "${LEASE_OK_FILE}" "$(date +%s)"
    return 0
  fi
  set_fenced_locked || true
  return 1
}

revoke_lease_locked() {
  local lease_mode=$1
  validate_lease_request "${lease_mode}" || return $?
  if [[ "$(read_state "${FENCE_DESIRED_FILE}" fenced)" == "${lease_mode}:${TOKEN}" ]]; then
    set_fenced_locked || return 1
  fi
}

reconcile_role_locked() {
  local effective_role=BACKUP current_role
  local_vip_present && effective_role=MASTER
  current_role=$(read_state "${VRRP_STATE_FILE}" STARTING)
  [[ "${current_role}" == "${effective_role}" ]] && return 0
  # Keepalived 2.0.x 的 notify 是异步 fork，回调到达顺序不能作为事实源。
  # 在同一锁内按内核 VIP 实况发布角色并先封闭租约，迟到回调无害。
  atomic_write "${VRRP_STATE_FILE}" "${effective_role}"
  atomic_write "${ROLE_CHANGED_FILE}" "$(date +%s)"
  set_fenced_locked || return 1
}

shutdown_locked() {
  atomic_write "${VRRP_STATE_FILE}" STOPPING
  atomic_write "${ROLE_CHANGED_FILE}" "$(date +%s)"
  set_fenced_locked || return 1
}

mkdir -p "${RUNTIME_DIR}"
exec 9>"${RUNTIME_DIR}/fence.lock"
flock -x -w 2 9 || die "timed out acquiring fence lock"

case "${MODE}" in
  init)
    init_table
    set_fenced_locked
    ;;
  fenced)
    set_fenced_locked
    ;;
  authorize-client)
    authorize_lease_locked client
    ;;
  renew-client)
    renew_lease_locked client
    ;;
  revoke-client)
    revoke_lease_locked client
    ;;
  authorize-synced)
    authorize_lease_locked synced
    ;;
  renew-synced)
    renew_lease_locked synced
    ;;
  revoke-synced)
    revoke_lease_locked synced
    ;;
  reconcile-role)
    reconcile_role_locked
    ;;
  shutdown)
    shutdown_locked
    ;;
  verify)
    timeout --kill-after=1 2 nft list table inet "${TABLE}"
    ;;
  *)
    printf 'usage: %s {init|fenced|authorize-client TOKEN|renew-client TOKEN|revoke-client TOKEN|authorize-synced TOKEN|renew-synced TOKEN|revoke-synced TOKEN|reconcile-role|shutdown|verify}\n' "$0" >&2
    exit 2
    ;;
esac
