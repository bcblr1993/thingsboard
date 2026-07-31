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
import org.thingsboard.server.dao.util.RedisClusterFastTsLatestDao;

/**
 * {@code database.ts_latest.type=redis-cluster-fast}
 * <p>
 * Redis 集群的高吞吐 latest 实现。逻辑见 {@link AbstractFastTimeseriesLatestDao}：
 * 单哈希 + 整设备批读批写 + 多 key 批量读，<b>完整保留时间戳守卫等三条保证</b>。
 * <p>
 * 与现网 {@code RedisClusterTimeseriesLatestDao} 并存、互斥装配，不影响既有部署。
 */
@Component
@RedisClusterFastTsLatestDao
@Slf4j
public class RedisClusterFastTimeseriesLatestDao extends AbstractFastTimeseriesLatestDao {

    @Override
    protected String backendName() {
        return "redis-cluster-fast";
    }
}
