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

OUTPUT=${1:-${RUNTIME_DIR}/keepalived.conf}
[[ -r "${VRRP_AUTH_FILE}" ]] || die "VRRP auth file is not readable: ${VRRP_AUTH_FILE}"
AUTH_PASS=$(tr -d '\r\n' < "${VRRP_AUTH_FILE}")
[[ "${AUTH_PASS}" =~ ^[A-Za-z0-9]{1,8}$ ]] || die "VRRP auth pass must be 1-8 alphanumeric characters"

cat > "${OUTPUT}" <<EOF
global_defs {
  router_id ${VRRP_ROUTER_ID}
  script_user root
  enable_script_security
}

vrrp_script chk_iotdb_ha {
  script "/opt/iotdb-ha/bin/check-vrrp-health.sh"
  interval 1
  timeout 1
  fall 3
  rise 3
  weight 0
  init_fail
}

vrrp_instance VI_IOTDB {
  state BACKUP
  interface ${INTERFACE}
  virtual_router_id ${VRRP_VIRTUAL_ROUTER_ID}
  priority ${VRRP_PRIORITY}
  advert_int 1
  nopreempt
  track_src_ip

  unicast_src_ip ${LOCAL_IP}
  unicast_peer {
    ${PEER_IP}
  }

  authentication {
    auth_type PASS
    auth_pass ${AUTH_PASS}
  }

  virtual_ipaddress {
    ${VIP}/24 dev ${INTERFACE}
  }

  track_script {
    chk_iotdb_ha
  }

  notify_master "/opt/iotdb-ha/bin/notify-role.sh MASTER"
  notify_backup "/opt/iotdb-ha/bin/notify-role.sh BACKUP"
  notify_fault  "/opt/iotdb-ha/bin/notify-role.sh FAULT"
  notify_stop   "/opt/iotdb-ha/bin/notify-role.sh STOP"

  garp_master_delay 1
  garp_master_repeat 5
  garp_master_refresh 60
  garp_master_refresh_repeat 2
}
EOF

chmod 0600 "${OUTPUT}"
keepalived --config-test --log-console -f "${OUTPUT}"
