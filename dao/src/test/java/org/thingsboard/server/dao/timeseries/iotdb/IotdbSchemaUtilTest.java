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

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.utils.Binary;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Key/measurement handling: quote round-trips through the reference-parsed node name, and
 * {@code leafKey} must be backtick-aware so a dotted key survives the read path (a plain
 * lastIndexOf('.') would split inside the backticks). Reserved words are rejected up front.
 */
public class IotdbSchemaUtilTest {

    private static final String DEV = "root.tb.DEVICE.u_x.";

    /** leafKey must recover the exact original key from the stored (possibly quoted) node path. */
    @Test
    public void leafKeyRoundTripsAllKeyShapes() {
        assertEquals("normal_key", IotdbSchemaUtil.leafKey(DEV + "normal_key"));
        assertEquals("sensor.temp", IotdbSchemaUtil.leafKey(DEV + "`sensor.temp`"));   // dot inside backticks
        assertEquals("sensor-temp", IotdbSchemaUtil.leafKey(DEV + "`sensor-temp`"));
        assertEquals("a b", IotdbSchemaUtil.leafKey(DEV + "`a b`"));
        assertEquals("123", IotdbSchemaUtil.leafKey(DEV + "`123`"));
        assertEquals("a`b", IotdbSchemaUtil.leafKey(DEV + "`a``b`"));                    // escaped backtick
        assertEquals("a.b.c", IotdbSchemaUtil.leafKey(DEV + "`a.b.c`"));                 // multiple dots
    }

    /** quote() escapes backticks so IoTDB parses the reference to the true node name. */
    @Test
    public void quoteEscapesBackticks() {
        assertEquals("`sensor.temp`", IotdbSchemaUtil.quote("sensor.temp"));
        assertEquals("`a``b`", IotdbSchemaUtil.quote("a`b"));
        // quote → leafKey is an identity round-trip for every shape
        for (String k : new String[]{"normal_key", "sensor.temp", "a b", "123", "a`b", "a.b.c"}) {
            assertEquals(k, IotdbSchemaUtil.leafKey(DEV + IotdbSchemaUtil.quote(k)), "round-trip: " + k);
        }
    }

    /** Reserved words / empty are rejected (quote can't save them); everything else is storable. */
    @Test
    public void illegalKeyReasonFlagsOnlyReserved() {
        assertNotNull(IotdbSchemaUtil.illegalKeyReason("time"));
        assertNotNull(IotdbSchemaUtil.illegalKeyReason("Timestamp"));  // case-insensitive
        assertNotNull(IotdbSchemaUtil.illegalKeyReason("root"));
        assertNotNull(IotdbSchemaUtil.illegalKeyReason(""));
        assertNotNull(IotdbSchemaUtil.illegalKeyReason(null));
        // these are storable (will be quoted), NOT flagged illegal
        assertNull(IotdbSchemaUtil.illegalKeyReason("sensor.temp"));
        assertNull(IotdbSchemaUtil.illegalKeyReason("sensor-temp"));
        assertNull(IotdbSchemaUtil.illegalKeyReason("a b"));
        assertNull(IotdbSchemaUtil.illegalKeyReason("123"));
        assertNull(IotdbSchemaUtil.illegalKeyReason("a`b"));
        assertNull(IotdbSchemaUtil.illegalKeyReason("normal_key"));
    }

    /**
     * JSON 与 STRING 必须在 IoTDB 序列类型层面区分, 否则读回都变 StringDataEntry(丢 JSON 类型)。
     * JSON→IoTDB STRING 类型, 普通 STRING→TEXT; 读 STRING 列还原为 JsonDataEntry, TEXT 列为
     * StringDataEntry。对齐 SQL 后端独立 json_v 列的保真契约。
     */
    @Test
    public void jsonMapsToStringTypeAndRoundTripsAsJson() {
        assertEquals(TSDataType.STRING, IotdbSchemaUtil.toIotdbType(DataType.JSON));
        assertEquals(TSDataType.TEXT, IotdbSchemaUtil.toIotdbType(DataType.STRING));

        // 写入侧: JSON 值(String)必须包装成 Binary(STRING 列以 Binary 承载)
        Object wrapped = IotdbSchemaUtil.toIotdbValue(TSDataType.STRING, "{\"a\":1}");
        assertInstanceOf(Binary.class, wrapped);

        // 读回侧: STRING 列 → JsonDataEntry
        Field jsonField = new Field(TSDataType.STRING);
        jsonField.setBinaryV(new Binary("{\"a\":1}", StandardCharsets.UTF_8));
        KvEntry json = IotdbSchemaUtil.toKvEntry("payload", jsonField);
        assertInstanceOf(JsonDataEntry.class, json);
        assertEquals("{\"a\":1}", json.getValueAsString());

        // TEXT 列仍是 StringDataEntry(普通字符串不受影响)
        Field textField = new Field(TSDataType.TEXT);
        textField.setBinaryV(new Binary("hello", StandardCharsets.UTF_8));
        KvEntry str = IotdbSchemaUtil.toKvEntry("name", textField);
        assertInstanceOf(StringDataEntry.class, str);
        assertEquals("hello", str.getValueAsString());
    }
}
