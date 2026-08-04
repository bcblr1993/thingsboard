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

[[ $(file_age_seconds "${TCP_OK_FILE}") -le ${CONTAINER_TCP_HEALTH_MAX_AGE_SECONDS:-10} ]] || exit 1
[[ $(file_age_seconds "${SQL_OK_FILE}") -le ${CONTAINER_SQL_HEALTH_MAX_AGE_SECONDS:-45} ]] || exit 1
[[ $(file_age_seconds "${CONTROLLER_OK_FILE}") -le 8 ]] || exit 1

for pid_file in iotdb.pid keepalived.pid controller.pid status-server.pid health-loop.pid; do
  [[ -s "${RUNTIME_DIR}/${pid_file}" ]] || exit 1
  pid=$(tr -d '\r\n' < "${RUNTIME_DIR}/${pid_file}")
  kill -0 "${pid}" 2>/dev/null || exit 1
done

if local_vip_present; then
  if [[ $(file_age_seconds "${LEASE_OK_FILE}") -le ${LEASE_HEARTBEAT_MAX_AGE_SECONDS:-6} ]] \
     && vip_lease_present; then
    :
  elif [[ "$(read_state "${VRRP_STATE_FILE}" STARTING)" == MASTER \
            && $(file_age_seconds "${ROLE_CHANGED_FILE}") -le ${MASTER_PENDING_GRACE_SECONDS:-6} ]]; then
    :
  else
    exit 1
  fi
fi

agent_state=$(read_state "${AGENT_STATE_FILE}" UNKNOWN)
case "${agent_state}" in
  FENCED_PIPE_ERROR|MASTER_PIPE_ERROR|MASTER_SYNC_DEGRADED_*) exit 1 ;;
esac

exit 0
