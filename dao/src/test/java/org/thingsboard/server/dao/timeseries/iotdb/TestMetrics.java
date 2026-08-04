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
package org.thingsboard.server.dao.timeseries.iotdb;

/** Test helper: a no-Spring {@link IotdbQueryMetrics} with a high slow-query threshold. */
final class TestMetrics {

    private TestMetrics() {
    }

    static IotdbQueryMetrics create() {
        IotdbQueryMetrics m = new IotdbQueryMetrics();
        try {
            java.lang.reflect.Field f = IotdbQueryMetrics.class.getDeclaredField("slowQueryMs");
            f.setAccessible(true);
            f.setLong(m, 60000);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return m;
    }
}
