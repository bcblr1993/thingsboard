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

set -uo pipefail

source /opt/iotdb-ha/bin/common.sh

[[ ! -e "${FORCE_FAULT_FILE}" ]] || exit 1
[[ $(file_age_seconds "${TCP_OK_FILE}") -le ${TCP_HEALTH_MAX_AGE_SECONDS:-5} ]] || exit 1
[[ $(file_age_seconds "${SQL_OK_FILE}") -le ${SQL_HEALTH_MAX_AGE_SECONDS:-35} ]] || exit 1
[[ $(file_age_seconds "${CONTROLLER_OK_FILE}") -le 4 ]] || exit 1
ip link show dev "${INTERFACE}" 2>/dev/null | grep -q 'UP' || exit 1
ip -4 address show dev "${INTERFACE}" 2>/dev/null | grep -Fq " ${LOCAL_IP}/" || exit 1
if local_vip_present; then
  desired=$(read_state "${FENCE_DESIRED_FILE}" fenced)
  if [[ $(file_age_seconds "${LEASE_OK_FILE}") -le ${LEASE_HEARTBEAT_MAX_AGE_SECONDS:-6} \
        && ( "${desired}" == client:* || "${desired}" == synced:* ) ]] \
     && vip_lease_present; then
    :
  elif [[ "$(read_state "${VRRP_STATE_FILE}" STARTING)" == MASTER \
            && $(file_age_seconds "${ROLE_CHANGED_FILE}") -le ${MASTER_PENDING_GRACE_SECONDS:-6} ]]; then
    # 新主先保持业务门关闭并探测 peer_vip；只给健康脚本一个短暂选举
    # 宽限，避免为了通过 fall 计数而制造可检测双主下的双写窗口。
    :
  else
    exit 1
  fi
fi
exit 0
