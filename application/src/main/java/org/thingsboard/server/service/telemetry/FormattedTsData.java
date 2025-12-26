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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.beans.ConstructorProperties;

@Schema
public class FormattedTsData implements Comparable<FormattedTsData>{
    //分桶后的ts
    private long ts;
    //原始ts
    private long originalTs;
    private Object value;



    @Override
    public int compareTo(FormattedTsData o) {
        return Long.compare(ts, o.ts);
    }

    public static FormattedTsDataBuilder builder() {
        return new FormattedTsDataBuilder();
    }

    @Schema(description = "Timestamp last updated timeseries, in milliseconds", example = "1609459200000",
            accessMode = Schema.AccessMode.READ_ONLY)
    public long getTs() {
        return this.ts;
    }

    @Schema(description = "Timestamp last updated timeseries, in milliseconds", example = "1609459200000",
            accessMode = Schema.AccessMode.READ_ONLY)
    public long getOriginalTs() {
        return this.originalTs;
    }

    @Schema(description = "Object representing value of timeseries key", example = "20",
            accessMode = Schema.AccessMode.READ_ONLY)
    public Object getValue() {
        return this.value;
    }

    public void setTs(final long ts) {
        this.ts = ts;
    }

    public void setOriginalTs(final long originalTs) {
        this.originalTs = originalTs;
    }

    public void setValue(final Object value) {
        this.value = value;
    }

    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof FormattedTsData)) {
            return false;
        } else {
            FormattedTsData other = (FormattedTsData)o;
            if (!other.canEqual(this)) {
                return false;
            } else if (this.getTs() != other.getTs()) {
                return false;
            } else if (this.getOriginalTs() != other.getOriginalTs()) {
                return false;
            } else {
                Object this$value = this.getValue();
                Object other$value = other.getValue();
                if (this$value == null) {
                    if (other$value == null) {
                        return true;
                    }
                } else if (this$value.equals(other$value)) {
                    return true;
                }

                return false;
            }
        }
    }

    protected boolean canEqual(final Object other) {
        return other instanceof FormattedTsData;
    }

    public int hashCode() {
        int PRIME = 31;
        int result = 1;
        long $ts = this.getTs();
        result = result * 59 + (int)($ts >>> 32 ^ $ts);
        long $originalTs = this.getOriginalTs();
        result = result * 59 + (int)($originalTs >>> 32 ^ $originalTs);
        Object $value = this.getValue();
        result = result * 59 + ($value == null ? 43 : $value.hashCode());
        return result;
    }

    public String toString() {
        long var10000 = this.getTs();
        return "FormattedTsData(ts=" + var10000 + ", originalTs=" + this.getOriginalTs() + ", value=" + String.valueOf(this.getValue()) + ")";
    }

    @ConstructorProperties({"ts", "originalTs", "value"})
    public FormattedTsData(final long ts, final long originalTs, final Object value) {
        this.ts = ts;
        this.originalTs = originalTs;
        this.value = value;
    }

    public FormattedTsData() {
    }

    public static class FormattedTsDataBuilder {
        private long ts;
        private long originalTs;
        private Object value;

        FormattedTsDataBuilder() {
        }

        public FormattedTsDataBuilder ts(final long ts) {
            this.ts = ts;
            return this;
        }

        public FormattedTsDataBuilder originalTs(final long originalTs) {
            this.originalTs = originalTs;
            return this;
        }

        public FormattedTsDataBuilder value(final Object value) {
            this.value = value;
            return this;
        }

        public FormattedTsData build() {
            return new FormattedTsData(this.ts, this.originalTs, this.value);
        }

        public String toString() {
            long var10000 = this.ts;
            return "FormattedTsData.FormattedTsDataBuilder(ts=" + var10000 + ", originalTs=" + this.originalTs + ", value=" + String.valueOf(this.value) + ")";
        }
    }
}
