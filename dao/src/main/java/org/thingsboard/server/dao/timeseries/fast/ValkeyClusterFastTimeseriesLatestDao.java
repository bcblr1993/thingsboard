/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.timeseries.fast;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.server.dao.util.ValkeyClusterFastTsLatestDao;

/**
 * {@code database.ts_latest.type=valkey-cluster-fast}
 * <p>
 * Valkey 集群的高吞吐 latest 实现。Valkey 与 Redis 协议完全兼容，故复用
 * {@link AbstractFastTimeseriesLatestDao} 的全部逻辑；独立成类是为了：
 * <ul>
 *   <li>配置层面可一键 A/B 切换（{@code redis-cluster-fast} ↔ {@code valkey-cluster-fast}）</li>
 *   <li>日志与后续指标可按后端区分，便于对比</li>
 * </ul>
 * Valkey 采用 BSD 3-Clause（OSI 认证开源，Linux 基金会治理），无商用限制。
 */
@Component
@ValkeyClusterFastTsLatestDao
@Slf4j
public class ValkeyClusterFastTimeseriesLatestDao extends AbstractFastTimeseriesLatestDao {

    @Override
    protected String backendName() {
        return "valkey-cluster-fast";
    }
}
