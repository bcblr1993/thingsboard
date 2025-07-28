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
package org.thingsboard.server.common.data.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.thingsboard.server.common.data.kv.DataType;

import java.math.BigDecimal;

public class TypeCastUtil {

    private TypeCastUtil() {}

    public static Pair<DataType, Object> castValue(String value) {
        if (isNumber(value)) {
            String formattedValue = value.replace(',', '.');
            try {
                BigDecimal bd = new BigDecimal(formattedValue);
                if (bd.stripTrailingZeros().scale() > 0 || isSimpleDouble(formattedValue)) {
                    if (bd.scale() <= 16) {
                        return Pair.of(DataType.DOUBLE, bd.doubleValue());
                    }
                } else {
                    return Pair.of(DataType.LONG, bd.longValueExact());
                }
            } catch (RuntimeException ignored) {}
        } else if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Pair.of(DataType.BOOLEAN, Boolean.parseBoolean(value));
        } else if (isJson(value)) {
            try {
                Gson gson = new Gson();
                JsonElement jsonElement = gson.fromJson(value, JsonElement.class);
                return Pair.of(DataType.JSON, jsonElement);
            } catch (Exception e) {
                // Not JSON, continue to treat as string
            }
        }
        return Pair.of(DataType.STRING, value);
    }

    private static boolean isJson(String value) {
        value = value.trim();
        return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"));
    }

    public static Pair<DataType, Number> castToNumber(String value) {
        if (isNumber(value)) {
            String formattedValue = value.replace(',', '.');
            BigDecimal bd = new BigDecimal(formattedValue);
            if (bd.stripTrailingZeros().scale() > 0 || isSimpleDouble(formattedValue)) {
                if (bd.scale() <= 16) {
                    return Pair.of(DataType.DOUBLE, bd.doubleValue());
                } else {
                    return Pair.of(DataType.DOUBLE, bd);
                }
            } else {
                return Pair.of(DataType.LONG, bd.longValueExact());
            }
        } else {
            throw new IllegalArgumentException("'" + value + "' can't be parsed as number");
        }
    }

    private static boolean isNumber(String value) {
        return NumberUtils.isNumber(value.replace(',', '.'));
    }

    private static boolean isSimpleDouble(String valueAsString) {
        return valueAsString.contains(".") && !valueAsString.contains("E") && !valueAsString.contains("e");
    }

}
