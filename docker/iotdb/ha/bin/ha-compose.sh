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

cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ -r node.env ]] || {
  printf 'missing %s/node.env\n' "$PWD" >&2
  exit 1
}

# node.env 既是容器 env_file，也是 Compose 网络 IP/子网插值来源；所有
# 运维入口统一经过本脚本，避免漏写 --env-file 后静默采用默认网络。
if docker compose version >/dev/null 2>&1; then
  exec docker compose --env-file node.env "$@"
elif command -v docker-compose >/dev/null 2>&1; then
  exec docker-compose --env-file node.env "$@"
else
  printf 'Docker Compose v2 is required\n' >&2
  exit 1
fi
