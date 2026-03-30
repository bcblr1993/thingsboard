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
package org.thingsboard.server.common.data.hierarchy;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HierarchyNode {

    @Schema(description = "Entity ID")
    private UUID id;

    @Schema(description = "Entity Type")
    private EntityType entityType;

    @Schema(description = "Node Name")
    private String name;

    @Schema(description = "Relation Type (if any)")
    private String relationType;

    @Schema(description = "Additional Info")
    private JsonNode additionalInfo;

    @Schema(description = "Sub-nodes")
    private List<HierarchyNode> children = new ArrayList<>();

    /**
     * 层级节点在构建时的逻辑状态记录:
     * 0 - 正常 (Normal)
     * 1 - 循环引用 (isCycle)
     * 2 - 多重引用/参照 (isReference)
     * 3 - 同时满足循环和参照 (Both)
     */
    @Schema(description = "Node hierarchy status (0: Normal, 1: Cycle, 2: Reference, 3: Both)")
    private int status;
    
    public HierarchyNode(UUID id, EntityType entityType, String name) {
        this.id = id;
        this.entityType = entityType;
        this.name = name;
        this.children = new ArrayList<>();
    }
}
