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

ROLE=${1:-}
case "${ROLE}" in
  MASTER|BACKUP|FAULT|STOP) ;;
  *) die "invalid Keepalived role notification: ${ROLE}" ;;
esac

# Keepalived 2.0.x 异步 fork 通知脚本，MASTER/BACKUP/FAULT 回调可能迟到。
# 每个回调都只按“此刻内核是否实际持有 VIP”重算有效角色；锁冲突时短暂
# 重试，不能让一次丢失的回调造成 VIP 黑洞。
ok=false
for _ in 1 2 3 4 5; do
  if /opt/iotdb-ha/bin/fence.sh reconcile-role; then
    ok=true
    break
  fi
  sleep 0.2
done
[[ "${ok}" == true ]] || die "failed to reconcile Keepalived role after ${ROLE} notification"
log "Keepalived notification=${ROLE}, effective=$(read_state "${VRRP_STATE_FILE}" UNKNOWN); role reconciled"
