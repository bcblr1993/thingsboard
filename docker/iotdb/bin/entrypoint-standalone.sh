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

DATANODE_MEMORY_SIZE=${DATANODE_MEMORY_SIZE:-72G}
CONFIGNODE_MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE:-4G}

if [[ ! "${DATANODE_MEMORY_SIZE}" =~ ^[0-9]+[GM]$ ]]; then
  echo "DATANODE_MEMORY_SIZE must use a value such as 72G or 73728M" >&2
  exit 1
fi
if [[ ! "${CONFIGNODE_MEMORY_SIZE}" =~ ^[0-9]+[GM]$ ]]; then
  echo "CONFIGNODE_MEMORY_SIZE must use a value such as 4G or 4096M" >&2
  exit 1
fi

sed -i -E "0,/^MEMORY_SIZE=.*/s//MEMORY_SIZE=${DATANODE_MEMORY_SIZE}/" /iotdb/conf/datanode-env.sh
sed -i -E "0,/^MEMORY_SIZE=.*/s//MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE}/" /iotdb/conf/confignode-env.sh

grep -Fqx "MEMORY_SIZE=${DATANODE_MEMORY_SIZE}" /iotdb/conf/datanode-env.sh
grep -Fqx "MEMORY_SIZE=${CONFIGNODE_MEMORY_SIZE}" /iotdb/conf/confignode-env.sh

echo "IoTDB standalone memory budgets: DataNode=${DATANODE_MEMORY_SIZE}, ConfigNode=${CONFIGNODE_MEMORY_SIZE}"
exec entrypoint.sh all
