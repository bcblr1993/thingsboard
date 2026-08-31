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

printf 'node=%s local=%s peer=%s vip=%s interface=%s\n' \
  "${NODE_ID}" "${LOCAL_IP}" "${PEER_IP}" "${VIP}" "${INTERFACE}"
printf 'vrrp=%s agent=%s pipe=%s vip_local=%s sql_age=%ss controller_age=%ss\n' \
  "$(read_state "${VRRP_STATE_FILE}" UNKNOWN)" \
  "$(read_state "${AGENT_STATE_FILE}" UNKNOWN)" \
  "$(read_state "${PIPE_STATE_FILE}" UNKNOWN)" \
  "$(local_vip_present && printf YES || printf NO)" \
  "$(file_age_seconds "${SQL_OK_FILE}")" \
  "$(file_age_seconds "${CONTROLLER_OK_FILE}")"

printf 'peer: '
peer_status || printf 'UNREACHABLE\n'

printf '\nPipe:\n'
pipe_show || true

printf '\nHA nftables:\n'
/opt/iotdb-ha/bin/fence.sh verify
