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

vrrp=$(read_state "${VRRP_STATE_FILE}" STARTING)
agent=$(read_state "${AGENT_STATE_FILE}" STARTING)
pipe_state=$(read_state "${PIPE_STATE_FILE}" UNKNOWN)
sql=DOWN
vip=NO
[[ $(file_age_seconds "${TCP_OK_FILE}") -le ${TCP_HEALTH_MAX_AGE_SECONDS:-5} \
   && $(file_age_seconds "${SQL_OK_FILE}") -le ${SQL_HEALTH_MAX_AGE_SECONDS:-35} ]] && sql=UP
local_vip_present && vip=YES

printf 'node=%s priority=%s vrrp=%s agent=%s pipe=%s sql=%s vip=%s\n' \
  "${NODE_ID}" "${VRRP_PRIORITY}" "${vrrp}" "${agent}" "${pipe_state}" "${sql}" "${vip}"
