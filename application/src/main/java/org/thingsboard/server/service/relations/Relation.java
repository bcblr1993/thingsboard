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
package org.thingsboard.server.service.relations;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 *  Relation对象表示CSV文件中的一行数据。
 *  @author junhao.feng
 */
@Slf4j
public class Relation implements Serializable {
    /**
     * 关联上级类型
     */
    private String fromType;
    /**
     * 关联上级名称
     */
    private String fromName;
    /**
     * 关联下级类型
     */
    private String toType;
    /**
     * 关联下级名称
     */
    private String toName;
    /**
     * 关联类型
     */
    private String type;
    /**
     * 分组类型
     */
    private String typeGroup;
    /**
     * 附加json
     */
    private JsonNode additionalInfo; // 使用 JsonNode 表示附加信息

    // Getters and Setters
    public String getFromType() {
        return fromType;
    }

    public void setFromType(String fromType) {
        this.fromType = fromType;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToType() {
        return toType;
    }

    public void setToType(String toType) {
        this.toType = toType;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTypeGroup() {
        return typeGroup;
    }

    public void setTypeGroup(String typeGroup) {
        this.typeGroup = typeGroup;
    }

    public JsonNode getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(JsonNode additionalInfo) {
        this.additionalInfo = additionalInfo;
    }

    @Override
    public String toString() {
        return "Relation{" +
                "fromType='" + fromType + '\'' +
                ", fromName='" + fromName + '\'' +
                ", toType='" + toType + '\'' +
                ", toName='" + toName + '\'' +
                ", type='" + type + '\'' +
                ", typeGroup='" + typeGroup + '\'' +
                ", additionalInfo=" + additionalInfo +
                '}';
    }
}
