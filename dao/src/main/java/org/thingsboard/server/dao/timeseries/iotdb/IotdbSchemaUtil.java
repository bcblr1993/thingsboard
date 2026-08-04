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
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DataType;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Path / type mapping shared by the IoTDB historical and latest DAOs so both stores
 * address exactly the same series (which lets duplicate writes merge in the buffer).
 */
public final class IotdbSchemaUtil {

    private IotdbSchemaUtil() {
    }

    /** {@code <database>.<ENTITY_TYPE>.u_<uuid with '-' -> '_'>} */
    public static String devicePath(String database, EntityId entityId) {
        return database + "." + entityId.getEntityType().name() + "." + sanitize(entityId.getId());
    }

    public static String measurementPath(String database, EntityId entityId, String key) {
        return devicePath(database, entityId) + "." + quote(key);
    }

    public static String sanitize(UUID id) {
        return "u_" + id.toString().replace('-', '_');
    }

    /** Backtick-quote a telemetry key so special characters are handled in SQL. */
    public static String quote(String key) {
        return "`" + key.replace("`", "``") + "`";
    }

    // IoTDB 保留标识符: 即使反引号转义也无法作为测点名(实测 time/timestamp → 507/509)。
    private static final java.util.Set<String> RESERVED = java.util.Set.of("time", "timestamp", "root");
    private static final java.util.Set<String> WARNED_ILLEGAL = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 返回 key 无法存入 IoTDB 的原因(连反引号转义也救不了); {@code null} = 可存储
     * (含点/横杠/空格/纯数字/反引号的 key 会被 {@link #quote} 包裹后正常写入)。
     */
    public static String illegalKeyReason(String key) {
        if (key == null || key.isEmpty()) {
            return "空 key";
        }
        if (RESERVED.contains(key.toLowerCase())) {
            return "IoTDB 保留字(time/timestamp/root), 转义也无法作为测点名";
        }
        return null;
    }

    /** 每个非法 key 只提示一次(返回 true), 避免高频写入把日志刷屏。 */
    public static boolean shouldWarnIllegalOnce(String key) {
        return WARNED_ILLEGAL.add(key == null ? "" : key);
    }

    /**
     * 从 IoTDB 返回的时序全路径提取叶子测点名。节点名可能是裸名, 也可能是反引号包裹
     * (含特殊字符时, 如 {@code root.tb.DEVICE.u_x.`sensor.temp`}); 反引号内的点与字面
     * 反引号({@code ``})都不是路径边界, 需从右端配对解析 —— 简单 {@code lastIndexOf('.')}
     * 会在反引号内的点错误分割。
     */
    public static String leafKey(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (path.charAt(path.length() - 1) == '`') {
            int close = path.length() - 1;
            int k = close - 1;
            int start = -1;
            while (k >= 0) {
                if (path.charAt(k) == '`') {
                    if (k - 1 >= 0 && path.charAt(k - 1) == '`') {
                        k -= 2; // 跳过转义的字面反引号 ``
                    } else {
                        start = k; // 开引号
                        break;
                    }
                } else {
                    k--;
                }
            }
            if (start >= 0) {
                return path.substring(start + 1, close).replace("``", "`");
            }
        }
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    public static TSDataType toIotdbType(DataType type) {
        switch (type) {
            case BOOLEAN:
                return TSDataType.BOOLEAN;
            case LONG:
                return TSDataType.INT64;
            case DOUBLE:
                return TSDataType.DOUBLE;
            case JSON:
                // IoTDB 1.3.7 有独立 STRING 类型: JSON 存为 STRING, 与普通 STRING(存 TEXT)在
                // 序列类型层面区分, 读回即可精确还原 JsonDataEntry(对齐 SQL 的独立 json_v 列)。
                return TSDataType.STRING;
            case STRING:
            default:
                return TSDataType.TEXT;
        }
    }

    public static Object rawValue(TsKvEntry e) {
        switch (e.getDataType()) {
            case BOOLEAN:
                return e.getBooleanValue().orElse(null);
            case LONG:
                return e.getLongValue().orElse(null);
            case DOUBLE:
                return e.getDoubleValue().orElse(null);
            case JSON:
                return e.getJsonValue().orElse(null);
            case STRING:
            default:
                return e.getStrValue().orElse(null);
        }
    }

    /** TEXT 与 STRING 列都以 {@link Binary} 承载。 */
    public static Object toIotdbValue(TSDataType type, Object raw) {
        if ((type == TSDataType.TEXT || type == TSDataType.STRING) && raw instanceof String) {
            return new Binary((String) raw, StandardCharsets.UTF_8);
        }
        return raw;
    }

    public static KvEntry toKvEntry(String key, Field f) {
        switch (f.getDataType()) {
            case BOOLEAN:
                return new BooleanDataEntry(key, f.getBoolV());
            case INT32:
                return new LongDataEntry(key, (long) f.getIntV());
            case INT64:
                return new LongDataEntry(key, f.getLongV());
            case FLOAT:
                return new DoubleDataEntry(key, (double) f.getFloatV());
            case DOUBLE:
                return new DoubleDataEntry(key, f.getDoubleV());
            case STRING:
                // IoTDB STRING 类型专用于承载 TB 的 JSON(见 toIotdbType), 精确还原为 JsonDataEntry
                return new JsonDataEntry(key, f.getStringValue());
            case TEXT:
            default:
                return new StringDataEntry(key, f.getStringValue());
        }
    }
}
