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
validate_runtime_env
mkdir -p "${RUNTIME_DIR}"

sql_countdown=0
while true; do
  if nc -z -w 1 127.0.0.1 "${IOTDB_RPC_PORT}" >/dev/null 2>&1; then
    date +%s > "${TCP_OK_FILE}"
  fi

  if (( sql_countdown <= 0 )); then
    if sql_is_healthy; then
      date +%s > "${SQL_OK_FILE}"
      sql_countdown=5
    else
      sql_countdown=1
    fi
  else
    sql_countdown=$((sql_countdown - 1))
  fi

  sleep 2
done

