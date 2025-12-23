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
package org.thingsboard.server.service.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema
public class FormattedTsData implements Comparable<FormattedTsData>{
    //分桶后的ts
    private final long ts;
    //原始ts
    private final long originalTs;
    private final Object value;

    public FormattedTsData(long ts, long originalTs, Object value) {
        super();
        this.ts = ts;
        this.originalTs = originalTs;
        this.value = value;
    }

    @Schema(description = "Timestamp last updated timeseries, in milliseconds", example = "1609459200000", accessMode = Schema.AccessMode.READ_ONLY)
    public long getTs() {
        return ts;
    }

    @Schema(description = "Timestamp last updated timeseries, in milliseconds", example = "1609459200000", accessMode = Schema.AccessMode.READ_ONLY)
    public long getOriginalTs() {
        return originalTs;
    }

    @Schema(description = "Object representing value of timeseries key", example = "20", accessMode = Schema.AccessMode.READ_ONLY)
    public Object getValue() {
        return value;
    }

    @Override
    public int compareTo(FormattedTsData o) {
        return Long.compare(ts, o.ts);
    }

}
