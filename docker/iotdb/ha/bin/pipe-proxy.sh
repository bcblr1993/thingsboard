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

QOS_RATE=${PIPE_QOS_RATE_MBIT:-180}
QOS_BURST=${PIPE_QOS_BURST:-1mb}
QOS_LATENCY=${PIPE_QOS_LATENCY_MS:-100}
PROXY_LEASE=${PIPE_PROXY_LEASE_SECONDS:-6}
PROXY_LEASE_FILE="${RUNTIME_DIR}/pipe-proxy.lease"
proxy_pid=

[[ "${QOS_RATE}" =~ ^[0-9]+$ ]] || die "invalid PIPE_QOS_RATE_MBIT"
[[ "${QOS_BURST}" =~ ^[0-9]+(b|kb|mb)$ ]] || die "invalid PIPE_QOS_BURST"
[[ "${QOS_LATENCY}" =~ ^[0-9]+$ ]] || die "invalid PIPE_QOS_LATENCY_MS"
[[ "${PROXY_LEASE}" =~ ^[0-9]+$ ]] || die "invalid PIPE_PROXY_LEASE_SECONDS"

stop_proxy() {
  if [[ -n "${proxy_pid}" ]]; then
    kill -TERM -- "-${proxy_pid}" >/dev/null 2>&1 || true
    wait "${proxy_pid}" >/dev/null 2>&1 || true
    proxy_pid=
    log "Pipe proxy fenced; all relay connections closed"
  fi
}

shutdown_proxy() {
  stop_proxy
  exit 0
}
trap shutdown_proxy TERM INT QUIT

# TBF 位于代理自己的 veth 上，只整形 A/B 同步流量，不替换宿主机
# 物理网卡 qdisc，也不会限速业务容器。
tc qdisc replace dev eth0 root tbf \
  rate "${QOS_RATE}mbit" \
  burst "${QOS_BURST}" \
  latency "${QOS_LATENCY}ms"
log "Pipe proxy QoS ready: rate=${QOS_RATE}mbit burst=${QOS_BURST} latency=${QOS_LATENCY}ms"

while true; do
  date +%s > "${PROXY_OK_FILE}"
  proxy_tx_bytes=$(< /sys/class/net/eth0/statistics/tx_bytes)
  [[ "${proxy_tx_bytes}" =~ ^[0-9]+$ ]] && atomic_write "${PROXY_BYTES_FILE}" "${proxy_tx_bytes}"
  lease_age=$(file_age_seconds "${PROXY_LEASE_FILE}")
  if (( lease_age <= PROXY_LEASE )); then
    if [[ -z "${proxy_pid}" ]] || ! kill -0 "${proxy_pid}" 2>/dev/null; then
      stop_proxy
      setsid socat \
        "TCP-LISTEN:${PIPE_PROXY_PORT},reuseaddr,fork" \
        "TCP:${PEER_IP}:${IOTDB_RPC_PORT},connect-timeout=5" &
      proxy_pid=$!
      log "Pipe proxy lease active; relaying localhost:${PIPE_PROXY_PORT} to ${PEER_IP}:${IOTDB_RPC_PORT}"
    fi
  else
    stop_proxy
  fi
  sleep 1
done
