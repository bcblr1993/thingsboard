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
package org.thingsboard.server.service.topology;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.hierarchy.HierarchyNode;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.sql.asset.AssetRepository;
import org.thingsboard.server.dao.sql.device.DeviceRepository;
import org.thingsboard.server.dao.tenant.TenantService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultEntityTopologyService implements EntityTopologyService {

    private final TenantService tenantService;
    private final AssetRepository assetRepository;
    private final DeviceRepository deviceRepository;
    private final RelationService relationService;

    @Override
    public HierarchyNode getEntityTopology(TenantId tenantId) {
        // 1. Fetch Tenant Name
        String tenantName = tenantService.findTenantById(tenantId).getName();
        HierarchyNode rootNode = new HierarchyNode(tenantId.getId(), EntityType.TENANT, tenantName);

        // 2. Fetch all Assets (lightweight: only id + name, no additional_info or other heavy fields)
        Map<UUID, HierarchyNode> nodeMap = new HashMap<>();
        List<Object[]> assetRows = assetRepository.findTopologyNodesByTenantId(tenantId.getId());
        for (Object[] row : assetRows) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            nodeMap.put(id, new HierarchyNode(id, EntityType.ASSET, name));
        }

        // 3. Fetch all Devices (lightweight: only id + name, no device_data jsonb or other heavy fields)
        List<Object[]> deviceRows = deviceRepository.findTopologyNodesByTenantId(tenantId.getId());
        for (Object[] row : deviceRows) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            nodeMap.put(id, new HierarchyNode(id, EntityType.DEVICE, name));
        }

        // 4. Fetch all Common Relations (optimized with INNER JOIN + explicit from_type)
        List<EntityRelation> relations = relationService.findAllByTenantId(tenantId);
        Map<UUID, List<EntityRelation>> adj = new HashMap<>();
        Map<UUID, Integer> inDegree = new HashMap<>();
        nodeMap.keySet().forEach(id -> inDegree.put(id, 0));

        for (EntityRelation relation : relations) {
            UUID fromId = relation.getFrom().getId();
            UUID toId = relation.getTo().getId();
            if (nodeMap.containsKey(fromId) && nodeMap.containsKey(toId)) {
                adj.computeIfAbsent(fromId, k -> new ArrayList<>()).add(relation);
                inDegree.merge(toId, 1, Integer::sum);
            }
        }

        // 5. Build Tree with cycle detection
        Set<UUID> globalVisited = new HashSet<>();
        if (rootNode.getChildren() == null) {
            rootNode.setChildren(new ArrayList<>());
        }

        // First pass: True roots (In-degree 0)
        for (UUID id : nodeMap.keySet()) {
            if (inDegree.get(id) == 0) {
                HierarchyNode treeRoot = copyNode(nodeMap.get(id));
                rootNode.getChildren().add(treeRoot);
                expandChildrenRecursive(treeRoot, adj, nodeMap, globalVisited, new HashSet<>());
            }
        }

        // Second pass: Islands / Cycles (Nodes not reachable from true roots)
        for (UUID id : nodeMap.keySet()) {
            if (!globalVisited.contains(id)) {
                HierarchyNode cycleRoot = copyNode(nodeMap.get(id));
                rootNode.getChildren().add(cycleRoot);
                expandChildrenRecursive(cycleRoot, adj, nodeMap, globalVisited, new HashSet<>());
            }
        }

        // Sort root level: nodes with children first
        rootNode.getChildren().sort((a, b) -> {
            boolean aHasChildren = a.getChildren() != null && !a.getChildren().isEmpty();
            boolean bHasChildren = b.getChildren() != null && !b.getChildren().isEmpty();
            return Boolean.compare(bHasChildren, aHasChildren);
        });

        return rootNode;
    }

    private void expandChildrenRecursive(HierarchyNode parent, Map<UUID, List<EntityRelation>> adj,
                                         Map<UUID, HierarchyNode> nodeMap, Set<UUID> globalVisited, Set<UUID> currentPath) {
        UUID parentId = parent.getId();
        globalVisited.add(parentId);
        currentPath.add(parentId);

        List<EntityRelation> childRels = adj.get(parentId);
        if (childRels != null) {
            for (EntityRelation rel : childRels) {
                UUID childId = rel.getTo().getId();
                HierarchyNode originalChild = nodeMap.get(childId);
                if (originalChild == null) continue;

                HierarchyNode childToLink = copyNode(originalChild);
                childToLink.setRelationType(rel.getType());
                childToLink.setAdditionalInfo(rel.getAdditionalInfo());

                int nodeStatus = 0;
                if (currentPath.contains(childId)) {
                    nodeStatus |= 1; // 循环引用 (isCycle)
                }
                if (globalVisited.contains(childId)) {
                    nodeStatus |= 2; // 多重引用/已读参考 (isReference)
                }

                if (nodeStatus > 0) {
                    childToLink.setStatus(nodeStatus);
                    parent.getChildren().add(childToLink);
                } else {
                    parent.getChildren().add(childToLink);
                    expandChildrenRecursive(childToLink, adj, nodeMap, globalVisited, new HashSet<>(currentPath));
                }
            }
        }

        // Sort children at this level: nodes with children first
        parent.getChildren().sort((a, b) -> {
            boolean aHasChildren = a.getChildren() != null && !a.getChildren().isEmpty();
            boolean bHasChildren = b.getChildren() != null && !b.getChildren().isEmpty();
            return Boolean.compare(bHasChildren, aHasChildren);
        });
    }

    private HierarchyNode copyNode(HierarchyNode original) {
        return new HierarchyNode(original.getId(), original.getEntityType(), original.getName());
    }

}
