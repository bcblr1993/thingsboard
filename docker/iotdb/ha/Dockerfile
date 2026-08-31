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

ARG IOTDB_IMAGE=apache/iotdb:1.3.7-standalone@sha256:06a4e3ba164597042d7a0eb4f3680dc54f28f9db4cd1a39fa065e4192f97a453
FROM ${IOTDB_IMAGE}

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        iproute2 \
        iputils-arping \
        keepalived \
        netcat-openbsd \
        nftables \
        procps \
        socat \
        util-linux \
    && rm -rf /var/lib/apt/lists/*

COPY bin/ /opt/iotdb-ha/bin/

RUN chmod 0755 /opt/iotdb-ha/bin/*.sh \
    && mkdir -p /var/lib/iotdb-ha /run/iotdb-ha

LABEL org.opencontainers.image.title="IoTDB 1.3.7 automatic HA node" \
      org.opencontainers.image.description="Apache IoTDB 1.3.7 with Keepalived, role fencing and controlled Pipe direction" \
      org.opencontainers.image.version="1.3.7-ha1"

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/opt/iotdb-ha/bin/container-health.sh"]

ENTRYPOINT ["/usr/bin/dumb-init", "--", "/opt/iotdb-ha/bin/entrypoint-ha.sh"]

