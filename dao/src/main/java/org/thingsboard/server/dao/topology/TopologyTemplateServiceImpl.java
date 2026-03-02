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
package org.thingsboard.server.dao.topology;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TopologyTemplateId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.Collectors;

import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.data.topology.DeploymentRequest;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.thingsboard.server.common.data.AttributeScope;

import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;

import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.asset.AssetProfile;
import org.thingsboard.server.dao.asset.AssetProfileService;
import org.thingsboard.server.dao.device.DeviceProfileService;

@Service
@Slf4j
public class TopologyTemplateServiceImpl extends AbstractEntityService implements TopologyTemplateService {

    public static final String INCORRECT_TOPOLOGY_TEMPLATE_ID = "Incorrect topologyTemplateId ";

    @Autowired
    private TopologyTemplateDao topologyTemplateDao;

    @Autowired
    private DataValidator<TopologyTemplate> topologyTemplateValidator;

    @Autowired
    private AssetService assetService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private RelationService relationService;

    @Autowired
    private AttributesService attributesService;

    @Autowired
    private org.thingsboard.server.dao.device.DeviceCredentialsService deviceCredentialsService;

    @Autowired
    private DeviceProfileService deviceProfileService;

    @Autowired
    private AssetProfileService assetProfileService;

    @Autowired
    private org.thingsboard.server.dao.audit.AuditLogService auditLogService;

    @Override
    public TopologyTemplate findTopologyTemplateById(TenantId tenantId, TopologyTemplateId topologyTemplateId) {
        log.trace("Executing findTopologyTemplateById [{}]", topologyTemplateId);
        Validator.validateId(topologyTemplateId, id -> INCORRECT_TOPOLOGY_TEMPLATE_ID + id);
        return topologyTemplateDao.findById(tenantId, topologyTemplateId.getId());
    }

    @Transactional
    @Override
    public TopologyTemplate saveTopologyTemplate(TopologyTemplate topologyTemplate) {
        log.trace("Executing saveTopologyTemplate [{}]", topologyTemplate);
        topologyTemplateValidator.validate(topologyTemplate, TopologyTemplate::getTenantId);
        return topologyTemplateDao.save(topologyTemplate.getTenantId(), topologyTemplate);
    }

    @Transactional
    @Override
    public void deleteTopologyTemplate(TenantId tenantId, TopologyTemplateId topologyTemplateId) {
        log.trace("Executing deleteTopologyTemplate [{}]", topologyTemplateId);
        Validator.validateId(topologyTemplateId, id -> INCORRECT_TOPOLOGY_TEMPLATE_ID + id);
        topologyTemplateDao.removeById(tenantId, topologyTemplateId.getId());
    }

    @Override
    public PageData<TopologyTemplate> findTopologyTemplatesByTenantId(TenantId tenantId, PageLink pageLink, boolean includeSystem) {
        log.trace("Executing findTopologyTemplatesByTenantId, tenantId [{}], pageLink [{}], includeSystem [{}]", tenantId, pageLink, includeSystem);
        Validator.validateId(tenantId, id -> "Incorrect tenantId " + id);
        Validator.validatePageLink(pageLink);
        return topologyTemplateDao.findTopologyTemplatesByTenantId(tenantId.getId(), pageLink, includeSystem);
    }

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Override
    public org.thingsboard.server.common.data.topology.PreviewNode previewTopology(TenantId tenantId, DeploymentRequest request) {
        log.info("Previewing topology for tenant [{}]", tenantId);
        JsonNode rootNode = getRootNode(request);
        // Preview does not save entities, so parentId is null and we return the root PreviewNode
        java.util.Set<String> excludedPaths = request.getExcludedPaths() != null ? request.getExcludedPaths() : java.util.Collections.emptySet();
        List<org.thingsboard.server.common.data.topology.PreviewNode> roots = traverseNode(tenantId, rootNode, null, request.getGlobalParams(), "", "", "", 0, true, new java.util.HashSet<>(), new java.util.HashSet<>(), excludedPaths, null, 0);
        return roots.isEmpty() ? null : roots.get(0);
    }

    @Transactional
    @Override
    public org.thingsboard.server.common.data.topology.PreviewNode deployTopology(TenantId tenantId, DeploymentRequest request, org.thingsboard.server.common.data.User user) {
        log.info("Deploying topology for tenant [{}]", tenantId);
        JsonNode rootNode = getRootNode(request);
        // Pre-fetch all existing profile names to avoid Duplicate Key errors and repeated DB lookups
        java.util.Set<String> verifiedDeviceProfiles = deviceProfileService.findDeviceProfileNamesByTenantId(tenantId, false)
                .stream().map(org.thingsboard.server.common.data.EntityInfo::getName).collect(Collectors.toSet());
        java.util.Set<String> verifiedAssetProfiles = assetProfileService.findAssetProfileNamesByTenantId(tenantId, false)
                .stream().map(org.thingsboard.server.common.data.EntityInfo::getName).collect(Collectors.toSet());

        // Pre-calculate total nodes
        int totalNodes = 0;
        java.util.Set<String> excludedPaths = request.getExcludedPaths() != null ? request.getExcludedPaths() : java.util.Collections.emptySet();
        try {
            List<org.thingsboard.server.common.data.topology.PreviewNode> countingRoots = traverseNode(tenantId, rootNode, null, request.getGlobalParams(), "", "", "", 0, true, new java.util.HashSet<>(), new java.util.HashSet<>(), excludedPaths, null, 0);
            totalNodes = countNodes(countingRoots);
            log.info("Project Deployment Progress [{}] - Evaluation complete. Ready to deploy a total of {} nodes.", tenantId, totalNodes);
        } catch (Exception e) {
            log.warn("Project Deployment Progress - Failed to pre-calculate total nodes.", e);
        }

        java.util.concurrent.atomic.AtomicInteger currentCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        // Deploy saves entities and returns the created structure with IDs
        List<org.thingsboard.server.common.data.topology.PreviewNode> roots = traverseNode(tenantId, rootNode, null, request.getGlobalParams(), "", "", "", 0, false, verifiedDeviceProfiles, verifiedAssetProfiles, excludedPaths, currentCounter, totalNodes);
        
        log.info("Project Deployment Progress [{}] - All {} nodes deployed successfully.", tenantId, totalNodes);

        org.thingsboard.server.common.data.topology.PreviewNode result = roots.isEmpty() ? null : roots.get(0);

        // Audit Log
        if (result != null && request.getTemplateId() != null) {
            try {
                TopologyTemplateId templateId = new TopologyTemplateId(java.util.UUID.fromString(request.getTemplateId()));
                TopologyTemplate template = topologyTemplateDao.findById(tenantId, templateId.getId());
                JsonNode actionData = mapper.valueToTree(result);
                auditLogService.logEntityAction(
                        tenantId,
                        (CustomerId) null,
                        user.getId(),
                        user.getName(),
                        templateId,
                        template,
                        org.thingsboard.server.common.data.audit.ActionType.DEPLOY_TOPOLOGY,
                        null,
                        actionData
                );
            } catch (Exception e) {
                log.error("Failed to log deployment action", e);
            }
        }

        return result;
    }

    private JsonNode getRootNode(DeploymentRequest request) {
        JsonNode rootNode;
        try {
            rootNode = mapper.valueToTree(request.getFinalTopology());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid topology structure: " + e.getMessage());
        }

        if (rootNode == null) {
            throw new IllegalArgumentException("Final topology is empty");
        }
        if (rootNode.isTextual()) {
            try {
                rootNode = mapper.readTree(rootNode.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid topology JSON text: " + e.getMessage());
            }
        }
        return rootNode;
    }

    private int countNodes(List<org.thingsboard.server.common.data.topology.PreviewNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return 0;
        int count = 0;
        for (org.thingsboard.server.common.data.topology.PreviewNode node : nodes) {
            count++;
            if (node.getChildren() != null) {
                count += countNodes(node.getChildren());
            }
        }
        return count;
    }

    /**
     * Traverses the topology tree.
     * @param preview If true, returns a list of PreviewNodes and does NOT save to DB.
     *                If false, saves to DB and returns empty list.
     */
    private List<org.thingsboard.server.common.data.topology.PreviewNode> traverseNode(TenantId tenantId, JsonNode nodeConfig, EntityId parentId, Map<String, String> globalParams, String parentPath, String parentHierarchicalIndex, String parentName, int depth, boolean preview, java.util.Set<String> verifiedDeviceProfiles, java.util.Set<String> verifiedAssetProfiles, java.util.Set<String> excludedPaths, java.util.concurrent.atomic.AtomicInteger currentCounter, int totalNodes) {
        List<org.thingsboard.server.common.data.topology.PreviewNode> previewNodes = new ArrayList<>();

        if (!nodeConfig.isObject()) {
            log.warn("Node configuration is not a JSON object: {}", nodeConfig);
            return previewNodes;
        }
        int count = nodeConfig.has("defaultCount") ? nodeConfig.get("defaultCount").asInt(1) : 1;
        if (count < 1) {
            throw new IllegalArgumentException("Node count must be at least 1");
        }
        
        String type = nodeConfig.has("type") ? nodeConfig.get("type").asText() : "ASSET";
        String namePattern = nodeConfig.has("namePattern") ? nodeConfig.get("namePattern").asText() : "";
        if (namePattern.isEmpty() && nodeConfig.has("name")) {
            namePattern = nodeConfig.get("name").asText();
        }
        String entityTypeLabel = nodeConfig.has("entityTypeLabel") ? nodeConfig.get("entityTypeLabel").asText() : "";
        
        // Loop to create instances
        for (int i = 1; i <= count; i++) {
            // Calculate Hierarchical Index: e.g. "1-2-3"
            String loopIndex = String.valueOf(i);
            
            // Logic: Hierarchical Index should start accumulating BELOW Station (depth 1).
            // Depth 0: Company
            // Depth 1: Station
            // Depth 2: Unit (Start index here, "1")
            // Depth 3: Device (Index "1-1")
            
            String currentPath;
            String nextParentPath;
            String currentHierarchicalIndex;
            String nextParentHierarchicalIndex;
            
            if (depth < 2) {
                 currentPath = loopIndex; 
                 nextParentPath = ""; 
                 
                 currentHierarchicalIndex = loopIndex;
                 nextParentHierarchicalIndex = "";
            } else {
                 currentPath = parentPath.isEmpty() ? loopIndex : parentPath + "-" + loopIndex;
                 nextParentPath = currentPath;
                 
                 currentHierarchicalIndex = parentHierarchicalIndex.isEmpty() ? loopIndex : parentHierarchicalIndex + "-" + loopIndex;
                 nextParentHierarchicalIndex = currentHierarchicalIndex;
            }
            
            // Check Exclusion by Unique Path
            if (excludedPaths != null && excludedPaths.contains(currentPath)) {
                log.debug("Skipping excluded node: {}", currentPath);
                continue;
            }
            
            // Prepared Params for Name Resolution
            Map<String, String> nameParams = globalParams;
            if (globalParams != null && globalParams.containsKey("useStationNameAsPrefix") && Boolean.parseBoolean(globalParams.get("useStationNameAsPrefix"))) {
                if (globalParams.containsKey("stationName")) {
                    nameParams = new java.util.HashMap<>(globalParams);
                    // Override stationSn with stationName so that ${StationSn} resolves to Station Name
                    nameParams.put("stationSn", globalParams.get("stationName"));
                }
            }

            // Resolve name
            String name;
            // Override name for Company (depth 0) and Station (depth 1) if provided in params
            if (depth == 0 && globalParams != null && globalParams.containsKey("companyName")) {
                name = globalParams.get("companyName");
            } else if (depth == 1 && globalParams != null && globalParams.containsKey("stationName")) {
                name = globalParams.get("stationName");
            } else {
                name = resolveName(namePattern, nameParams, i, entityTypeLabel, currentHierarchicalIndex, parentName);
            }
            
            // Determine Label: User wants to use Station SN as label for all entities
            // Determine Label: User wants to use Station SN or Station Name as label for all entities
            String label = entityTypeLabel;
            boolean useStationName = false;
            if (globalParams != null && globalParams.containsKey("useStationNameAsPrefix")) {
                // Frontend sends boolean, which becomes string "true"/"false" in the map
                useStationName = Boolean.parseBoolean(globalParams.get("useStationNameAsPrefix"));
            }

            if (useStationName) {
                if (globalParams != null && globalParams.containsKey("stationName")) {
                    label = globalParams.get("stationName");
                }
            } else {
                if (globalParams != null && globalParams.containsKey("stationSn")) {
                    label = globalParams.get("stationSn");
                }
            }
            
            // Determine Profile
            String profileName = "default";
            if (nodeConfig.has("profileType") && "custom".equals(nodeConfig.get("profileType").asText())) {
                if (nodeConfig.has("customProfileName") && !nodeConfig.get("customProfileName").asText().isEmpty()) {
                    profileName = nodeConfig.get("customProfileName").asText();

                    // Check if profile exists, if not create it (only if not in preview mode)
                    // Check if profile exists, if not create it (only if not in preview mode)
                    if (!preview) {
                         // Check if profile exists in our verified set (which contains DB profiles + newly created ones)
                         if ("DEVICE".equalsIgnoreCase(type)) {
                             if (!verifiedDeviceProfiles.contains(profileName)) {
                                 // Not in DB and not created by us yet -> Create it
                                 DeviceProfile deviceProfile = new DeviceProfile();
                                 deviceProfile.setTenantId(tenantId);
                                 deviceProfile.setName(profileName);
                                 deviceProfile.setType(DeviceProfileType.DEFAULT);
                                 deviceProfile.setTransportType(DeviceTransportType.DEFAULT);
                                 
                                 org.thingsboard.server.common.data.device.profile.DeviceProfileData profileData = 
                                     new org.thingsboard.server.common.data.device.profile.DeviceProfileData();
                                 profileData.setConfiguration(new org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration());
                                 profileData.setTransportConfiguration(new org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileTransportConfiguration());
                                 profileData.setProvisionConfiguration(new org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration(null));
                                 deviceProfile.setProfileData(profileData);
                                 
                                 try {
                                     deviceProfileService.saveDeviceProfile(deviceProfile);
                                     verifiedDeviceProfiles.add(profileName);
                                 } catch (Exception e) {
                                     // If we hit duplicate key, it means it existed but logic missed it. 
                                     // We can swallow checking exception or re-throw. 
                                     // But since we pre-loaded, this should be very rare (race condition).
                                     log.warn("Failed to create device profile {}, possibly duplicate.", profileName);
                                     verifiedDeviceProfiles.add(profileName); // Assume it exists now
                                 }
                             }
                         } else {
                             if (!verifiedAssetProfiles.contains(profileName)) {
                                 // Not in DB and not created by us yet -> Create it
                                 AssetProfile assetProfile = new AssetProfile();
                                 assetProfile.setTenantId(tenantId);
                                 assetProfile.setName(profileName);
                                 
                                 try {
                                     assetProfileService.saveAssetProfile(assetProfile);
                                     verifiedAssetProfiles.add(profileName);
                                 } catch (Exception e) {
                                     log.warn("Failed to create asset profile {}, possibly duplicate.", profileName);
                                     verifiedAssetProfiles.add(profileName);
                                 }
                             }
                         }
                    }
                }
            }
            
            EntityId createdEntityId = null;
            org.thingsboard.server.common.data.topology.PreviewNode previewNode = null;

            // Always create PreviewNode to track structure and IDs
            previewNode = new org.thingsboard.server.common.data.topology.PreviewNode();
            previewNode.setName(name);
            previewNode.setType(type);
            previewNode.setLabel(label);
            previewNode.setPath(currentPath);

            try {
                if ("DEVICE".equalsIgnoreCase(type)) {
                    if (!preview) {
                        Device device = deviceService.findDeviceByTenantIdAndName(tenantId, name);
                        if (device == null) {
                            device = new Device();
                            device.setTenantId(tenantId);
                            device.setName(name);
                        }
                        device.setType(profileName); // Use determined profile
                        device.setLabel(label); // Use stationSn as label
                        Device savedDevice = deviceService.saveDevice(device);
                        createdEntityId = savedDevice.getId();
                        previewNode.setId(createdEntityId.toString());
                        
                        // Update Device Credentials
                        org.thingsboard.server.common.data.security.DeviceCredentials credentials = 
                            deviceCredentialsService.findDeviceCredentialsByDeviceId(tenantId, savedDevice.getId());
                        if (credentials != null) {
                            try {
                                credentials.setCredentialsType(DeviceCredentialsType.MQTT_BASIC);
                                credentials.setCredentialsId(savedDevice.getName());
                                
                                // Determine Credential Strategy
                                String credentialStrategy = nodeConfig.has("credentialStrategy") ? nodeConfig.get("credentialStrategy").asText() : "name";
                                String credentialLabel = entityTypeLabel;

                                if ("pinyin".equalsIgnoreCase(credentialStrategy)) {
                                    credentialLabel = toPinyin(entityTypeLabel);
                                } else if ("custom".equalsIgnoreCase(credentialStrategy)) {
                                    if (nodeConfig.has("customCredentialName")) {
                                        credentialLabel = nodeConfig.get("customCredentialName").asText();
                                    }
                                }
                                
                                String credentialName = resolveName(namePattern, globalParams, i, credentialLabel, currentHierarchicalIndex, parentName);
                                credentials.setCredentialsId(credentialName);

                                BasicMqttCredentials mqttCredentials = new BasicMqttCredentials();
                                mqttCredentials.setClientId(credentialName);
                                mqttCredentials.setUserName(credentialName);
                                mqttCredentials.setPassword(credentialName);
                                
                                credentials.setCredentialsValue(mapper.writeValueAsString(mqttCredentials));
                                deviceCredentialsService.updateDeviceCredentials(tenantId, credentials);
                                
                                // Populate PreviewNode with credentials so they can be returned to FE
                                previewNode.setCredentialsId(credentials.getCredentialsId());
                                previewNode.setCredentialsValue(credentials.getCredentialsValue());

                            } catch (Exception e) {
                                log.error("Failed to update credentials for device {}", savedDevice.getName(), e);
                            }
                        }
                    } else {
                        // For preview, simulate credentials
                        // For preview, simulate credentials
                        String credentialStrategy = nodeConfig.has("credentialStrategy") ? nodeConfig.get("credentialStrategy").asText() : "name";
                        String credentialLabel = entityTypeLabel;

                        if ("pinyin".equalsIgnoreCase(credentialStrategy)) {
                            credentialLabel = toPinyin(entityTypeLabel);
                        } else if ("custom".equalsIgnoreCase(credentialStrategy)) {
                            if (nodeConfig.has("customCredentialName")) {
                                credentialLabel = nodeConfig.get("customCredentialName").asText();
                            }
                        }
                        
                        String credentialName = resolveName(namePattern, globalParams, i, credentialLabel, currentHierarchicalIndex, parentName);
                        
                        previewNode.setCredentialsId(credentialName);
                         BasicMqttCredentials mqttCredentials = new BasicMqttCredentials();
                        mqttCredentials.setClientId(credentialName);
                        mqttCredentials.setUserName(credentialName);
                        mqttCredentials.setPassword(credentialName);
                        previewNode.setCredentialsValue(mapper.writeValueAsString(mqttCredentials));
                    }
                } else {
                    if (!preview) {
                        Asset asset = null;
                        // For Company (Depth 0) and Station (Depth 1), check if asset already exists
                        if (depth == 0 || depth == 1) {
                            asset = assetService.findAssetByTenantIdAndName(tenantId, name);
                        }
                        
                        if (asset == null) {
                            asset = new Asset();
                            asset.setTenantId(tenantId);
                            asset.setName(name);
                            asset.setType(profileName); // Use determined profile
                            asset.setLabel(label); // Use stationSn as label
                            asset = assetService.saveAsset(asset);
                        }
                        
                        createdEntityId = asset.getId();
                        previewNode.setId(createdEntityId.toString());
                    }
                }

                if (!preview && currentCounter != null) {
                    int current = currentCounter.incrementAndGet();
                    if (current % 10 == 0 || current == totalNodes) {
                        log.info("Project Deployment Progress [{}]: [{}/{}] Nodes created. (Recent: {} [{}])", tenantId, current, totalNodes, name, type);
                    }
                }

                if (!preview) {
                    if (nodeConfig.has("attributes")) {
                        JsonNode attrs = nodeConfig.get("attributes");
                        if (attrs.isTextual()) {
                            try {
                                attrs = mapper.readTree(attrs.asText());
                            } catch (Exception e) {
                                log.error("Failed to parse attributes text: {}", attrs.asText(), e);
                            }
                        }
                        
                        List<AttributeKvEntry> attributesFn = new ArrayList<>();
                        if (attrs != null) {
                            if (attrs.isArray()) {
                                for (JsonNode element : attrs) {
                                    if (element.has("key") && element.has("value")) {
                                        attributesFn.add(toAttributeKvEntry(element.get("key").asText(), element.get("value")));
                                    }
                                }
                            } else if (attrs.isObject()) {
                                 java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = attrs.fields();
                                 while (fields.hasNext()) {
                                     java.util.Map.Entry<String, JsonNode> entry = fields.next();
                                     String key = entry.getKey();
                                     JsonNode value = entry.getValue();
                                     boolean added = false;
                                     // Check if value is a nested KV object
                                     if (value.isObject() && value.has("key") && value.has("value")) {
                                         attributesFn.add(toAttributeKvEntry(value.get("key").asText(), value.get("value")));
                                         added = true;
                                     } else if (value.isTextual()) {
                                         try {
                                             JsonNode parsed = mapper.readTree(value.asText());
                                             if (parsed.isObject() && parsed.has("key") && parsed.has("value")) {
                                                 attributesFn.add(toAttributeKvEntry(parsed.get("key").asText(), parsed.get("value")));
                                                 added = true;
                                             }
                                         } catch (Exception e) {
                                             // Not a JSON object, treat as simple string value
                                         }
                                     }
                                     
                                     if (!added) {
                                         if (value.isTextual() && value.asText().startsWith("EXP::")) {
                                             String expressionTpl = value.asText().substring(5);
                                             String resolvedText = resolveName(expressionTpl, globalParams, i, entityTypeLabel, currentHierarchicalIndex, name);
                                             attributesFn.add(new org.thingsboard.server.common.data.kv.BaseAttributeKvEntry(new org.thingsboard.server.common.data.kv.StringDataEntry(key, resolvedText), System.currentTimeMillis()));
                                         } else {
                                             attributesFn.add(toAttributeKvEntry(key, value));
                                         }
                                     }
                                 }
                            }
                        }
                        
                        if (!attributesFn.isEmpty()) {
                             attributesService.save(tenantId, createdEntityId, AttributeScope.SERVER_SCOPE, attributesFn);
                        }
                    }

                    // Create Relation (only in deploy mode)
                    if (parentId != null) {
                        EntityRelation relation = new EntityRelation();
                        relation.setFrom(parentId);
                        relation.setTo(createdEntityId);
                        relation.setType("Contains");
                        relation.setTypeGroup(RelationTypeGroup.COMMON);
                        
                        // Support for Additional Info on Relation
                        if (nodeConfig.has("relationAdditionalInfo")) {
                            JsonNode relInfo = nodeConfig.get("relationAdditionalInfo");
                            if (relInfo.isTextual()) {
                                try {
                                    relInfo = mapper.readTree(relInfo.asText());
                                } catch (Exception e) {
                                    log.error("Failed to parse relationAdditionalInfo text: {}", relInfo.asText(), e);
                                }
                            }
                            if (relInfo.isObject()) {
                                relation.setAdditionalInfo(relInfo);
                            }
                        }

                        relationService.saveRelation(tenantId, relation);
                    }
                }

                // Process Children
                if (nodeConfig.has("subNodes")) {
                    JsonNode subNodes = nodeConfig.get("subNodes");
                    if (subNodes.isTextual()) {
                        try {
                            subNodes = mapper.readTree(subNodes.asText());
                        } catch (Exception e) {
                            log.error("Failed to parse subNodes text: {}", subNodes.asText(), e);
                        }
                    }
                    if (subNodes != null && subNodes.isArray()) {
                        for (int j = 0; j < subNodes.size(); j++) {
                            JsonNode subNode = subNodes.get(j);
                            // Pass nextParentIndex and subNode index 'j' to avoid collision between sibling config blocks
                            // Format: parentIndex-j (we append j to parent index before passing to recursive call??)
                            // No, the 'i' loop above generates instances. 'j' loop generates types of children.
                            // We need to pass a prefix that includes 'j'.
                            // Current 'nextParentIndex' is unique for the INSTANCE (e.g. "1-2").
                            // If we just pass "1-2", then children from subNode[0] and subNode[1] will both start with "1-2-1"... COLLISION!
                            // So we must append 'j' to the parent index for the next level.
                            
                            // Let's modify the recursive call to accept a "pathPrefix".
                            // But traverseNode generates indices based on "parentIndex".
                            // If we change nextParentIndex to "1-2-0", then children will generate "1-2-0-1". 
                            // This works and preserves uniqueness.
                            
                            // If there is only one subNode config block, adding 'j' (0) is redundant and breaks user expectation (e.g. 1-0-1 instead of 1-1).
                            // Only append 'j' if there are MULTIPLE sibling config blocks to avoid index collision among their generated instances.
                            String childParentPath;
                            if (subNodes.size() == 1) {
                                childParentPath = nextParentPath;
                            } else {
                                childParentPath = nextParentPath.isEmpty() ? String.valueOf(j) : nextParentPath + "-" + j;
                            }
                            
                            List<org.thingsboard.server.common.data.topology.PreviewNode> children = traverseNode(tenantId, subNode, createdEntityId, globalParams, childParentPath, nextParentHierarchicalIndex, name, depth + 1, preview, verifiedDeviceProfiles, verifiedAssetProfiles, excludedPaths, currentCounter, totalNodes);
                            if (previewNode != null) {
                                children.forEach(previewNode::addChild);
                            }
                        }
                    }
                }
                
                previewNodes.add(previewNode);

            } catch (Exception e) {
                log.error("Failed to process node: {}", name, e);
                // Re-throw to trigger Transaction Rollback
                throw new RuntimeException("Failed to process node " + name + ": " + e.getMessage(), e);
            }
        }
        return previewNodes;
    }

    private String resolveName(String pattern, Map<String, String> params, int index, String type, String hierarchicalIndex, String parentName) {
        String result = pattern;
        if (params != null) {
             for (Map.Entry<String, String> entry : params.entrySet()) {
                 String key = entry.getKey();
                 String value = entry.getValue();
                 if (value != null) {
                     // Case-insensitive replacement
                     String regex = "(?i)\\$\\{" + Pattern.quote(key) + "\\}";
                     result = result.replaceAll(regex, Matcher.quoteReplacement(value));
                 }
             }
        }
        result = result.replace("${Index}", String.valueOf(index));
        result = result.replace("${HierarchicalIndex}", hierarchicalIndex);
        result = result.replace("${Type}", type);
        result = result.replace("${ParentName}", parentName == null ? "" : parentName);
        // Fallback for unset variables? Leave them or clear?
        return result;
    }

    private AttributeKvEntry toAttributeKvEntry(String key, JsonNode value) {
        long ts = System.currentTimeMillis();
        if (value.isBoolean()) {
            return new BaseAttributeKvEntry(new BooleanDataEntry(key, value.asBoolean()), ts);
        } else if (value.isDouble()) {
            return new BaseAttributeKvEntry(new DoubleDataEntry(key, value.asDouble()), ts);
        } else if (value.isLong() || value.isInt()) {
            return new BaseAttributeKvEntry(new LongDataEntry(key, value.asLong()), ts);
        } else if (value.isObject() || value.isArray()) {
            return new BaseAttributeKvEntry(new org.thingsboard.server.common.data.kv.JsonDataEntry(key, value.toString()), ts);
        } else {
            return new BaseAttributeKvEntry(new StringDataEntry(key, value.asText()), ts);
        }
    }
    private String toPinyin(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                try {
                    String[] pinyinArray = net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(c);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        sb.append(pinyinArray[0].charAt(0));
                    } else {
                        sb.append(c);
                    }
                } catch (Exception e) {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
