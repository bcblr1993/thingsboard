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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.entity.relation.TbEntityRelationService;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * 设备 资产 关联关系导入service
 *
 * @author junhao.feng
 */
@Slf4j
@Service
@TbCoreComponent
public class RelationsBulkImportServiceImpl implements RelationsBulkImportService{

    @Autowired
    private AssetService assetService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private TbEntityRelationService tbEntityRelationService;

    // 用于处理JSON解析
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 设备类型
     */
    public static final String DEVICE = "DEVICE";
    /**
     * 资产类型
     */
    public static final String ASSET = "ASSET";
    /**
     * 关联上级类型
     */
    public static final String FROM_TYPE = "fromType";
    /**
     * 关联上级名称
     */
    public static final String FROM_NAME = "fromName";
    /**
     * 关联下级类型
     */
    public static final String TO_TYPE = "toType";
    /**
     * 关联下级名称
     */
    public static final String TO_NAME = "toName";
    /**
     * 关联类型
     */
    public static final String TYPE = "type";
    /**
     * 分组类型
     */
    public static final String TYPE_GROUP = "typeGroup";
    /**
     * 附加json
     */
    public static final String ADDITIONAL_INFO = "additionalInfo";
    /**
     * 名称
     */
    public static final String NAME = "name";


    /**
     * 上传 关联关系.CSV 文件并解析
     *
     * @param file       关联关系.CSV 文件
     * @param tenantId   tenantId
     * @param customerId customerId
     * @param user       user
     */
    public void processBulkImport(MultipartFile file, TenantId tenantId, CustomerId customerId, User user) throws Exception {
        List<Relation> relations = new ArrayList<>();
        char delimiter = detectDelimiter(file);

        // 自动检测编码并去掉 BOM
        try (InputStream is = file.getInputStream();
             BOMInputStream bomIn = new BOMInputStream(is);
             Reader reader = new InputStreamReader(bomIn);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setDelimiter(delimiter)
                     .build())) {

            for (CSVRecord record : csvParser) {
                try {
                    Relation relation = parseRecord(record);
                    relations.add(relation);
                } catch (ThingsboardException e) {
                    log.error("Failed to parse CSV line {}: {}", record.getRecordNumber(), e.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("Error reading or parsing CSV file", e);
            throw new ThingsboardException("Failed to parse CSV file: " + e.getMessage(), ThingsboardErrorCode.GENERAL);
        }

        List<EntityRelation> entityRelations = relations.stream()
                .map(r -> convert(r, tenantId))
                .toList();

        entityRelations.forEach(entityRelation -> {
            try {
                tbEntityRelationService.save(tenantId, customerId, entityRelation, user);
                log.info("Successfully saved relation: {}", entityRelation);
            } catch (ThingsboardException e) {
                log.error("Failed to save relation [{}]: {}", entityRelation, e.getMessage());
            }
        });
    }


    /**
     * 资产、设备关联关系预览
     *
     * @param device   设备集合 CSV
     * @param asset    资产集合 CSV
     * @param relation 关联关系 CSV
     */
    public TreeViewVO processTreeView(MultipartFile device, MultipartFile asset, MultipartFile relation) throws Exception {
        // 解析设备和资产 CSV
        List<String> devices = parseNameList(device, DEVICE);
        List<String> assets = parseNameList(asset, ASSET);

        // 解析关系 CSV
        Map<String, TreeNode> nodeMap = new HashMap<>();
        Set<String> relations = new HashSet<>();
        char relationDelimiter = detectDelimiter(relation);

        try (InputStream is = relation.getInputStream();
             BOMInputStream bomIn = new BOMInputStream(is);
             Reader reader = new InputStreamReader(bomIn);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setDelimiter(relationDelimiter)
                     .build())) {

            for (CSVRecord record : csvParser) {
                validateRelationFields(record);
                String fromName = record.get(FROM_NAME);
                String toName = record.get(TO_NAME);

                TreeNode parentNode = nodeMap.computeIfAbsent(fromName, TreeNode::new);
                TreeNode childNode = nodeMap.computeIfAbsent(toName, TreeNode::new);
                parentNode.addChild(childNode);

                relations.add(toName);
            }

        } catch (IOException e) {
            log.error("Error reading or parsing relation CSV", e);
            throw new ThingsboardException("Failed to parse relation CSV: " + e.getMessage(), ThingsboardErrorCode.GENERAL);
        }

        // 组装根节点
        List<TreeNode> roots = nodeMap.keySet().stream()
                .filter(nodeName -> !relations.contains(nodeName))
                .map(nodeMap::get)
                .toList();

        // 组装返回对象
        TreeViewVO treeViewVO = new TreeViewVO();
        treeViewVO.setNodes(roots);
        treeViewVO.setNoRelation(findNamesNotInSet(devices, assets, nodeMap.keySet()));
        treeViewVO.setOnlyRelation(findUniqueNames(nodeMap.keySet(), devices, assets));

        return treeViewVO;
    }


    /**
     * 解析 CSV 文件为名称列表
     */
    private List<String> parseNameList(MultipartFile file, String fileType) throws Exception {
        List<String> names = new ArrayList<>();
        char delimiter = detectDelimiter(file);

        try (InputStream is = file.getInputStream();
             BOMInputStream bomIn = new BOMInputStream(is);
             Reader reader = new InputStreamReader(bomIn);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setDelimiter(delimiter)
                     .build())) {

            for (CSVRecord record : csvParser) {
                if (!record.isMapped(NAME)) {
                    throw new ThingsboardException("Missing required field: NAME at " + fileType + " CSV line " + record.getRecordNumber(),
                            ThingsboardErrorCode.INVALID_ARGUMENTS);
                }
                names.add(record.get(NAME));
            }

        } catch (IOException e) {
            log.error("Error reading or parsing " + fileType + " CSV", e);
            throw new ThingsboardException("Failed to parse " + fileType + " CSV: " + e.getMessage(), ThingsboardErrorCode.GENERAL);
        }

        return names;
    }


    /**
     * 验证关系 CSV 的必填字段
     */
    private void validateRelationFields(CSVRecord record) throws ThingsboardException {
        List<String> requiredFields = List.of(FROM_TYPE, FROM_NAME, TO_TYPE, TO_NAME, TYPE, TYPE_GROUP, ADDITIONAL_INFO);
        for (String field : requiredFields) {
            if (!record.isMapped(field)) {
                throw new ThingsboardException("Missing required field: " + field + " at relation CSV line " + record.getRecordNumber(),
                        ThingsboardErrorCode.INVALID_ARGUMENTS);
            }
        }
    }


    /**
     * 解析 CSVRecord 为 Relation 对象
     */
    private Relation parseRecord(CSVRecord record) throws ThingsboardException {
        Relation relation = new Relation();

        relation.setFromType(getRequiredField(record, FROM_TYPE));
        relation.setFromName(getRequiredField(record, FROM_NAME));
        relation.setToType(getRequiredField(record, TO_TYPE));
        relation.setToName(getRequiredField(record, TO_NAME));
        relation.setType(getRequiredField(record, TYPE));
        relation.setTypeGroup(getRequiredField(record, TYPE_GROUP));

        if (record.isMapped(ADDITIONAL_INFO)) {
            String additionalInfoStr = record.get(ADDITIONAL_INFO);
            if (additionalInfoStr != null && !additionalInfoStr.trim().isEmpty()) {
                try {
                    JsonNode additionalInfoNode = objectMapper.readTree(additionalInfoStr);
                    relation.setAdditionalInfo(additionalInfoNode);
                } catch (IOException e) {
                    throw new ThingsboardException(
                            "Field 'additionalInfo' must be valid JSON at CSV line " + record.getRecordNumber(),
                            ThingsboardErrorCode.INVALID_ARGUMENTS
                    );
                }
            }
        }

        return relation;
    }


    /**
     * 获取必填字段，如果缺失则抛异常
     */
    private String getRequiredField(CSVRecord record, String fieldName) throws ThingsboardException {
        if (record.isMapped(fieldName)) {
            return record.get(fieldName);
        } else {
            throw new ThingsboardException("Missing required field: " + fieldName + " at CSV line " + record.getRecordNumber(),
                    ThingsboardErrorCode.INVALID_ARGUMENTS);
        }
    }


    /**
     * 设备和资产中存在,但在关联关系中不存在的
     *
     * @param devices   设备集合
     * @param assets    资产集合
     * @param relations 关联关系集合
     * @return 数据集合
     */
    public static Set<String> findNamesNotInSet(List<String> devices, List<String> assets, Set<String> relations) {
        // 合并两个列表到一个 Set 中（去重）
        Set<String> combinedLists = new HashSet<>();
        combinedLists.addAll(devices);
        combinedLists.addAll(assets);

        // 找到合并列表中但不在 Set 中的名称
        return combinedLists.stream()
                .filter(name -> !relations.contains(name)) // 不在 Set 中
                .collect(Collectors.toSet());
    }


    /**
     * 关联关系中存在,但设备和资产中不存在的
     *
     * @param relations 关联关系集合
     * @param devices   设备集合
     * @param assets    资产集合
     * @return 数据集合
     */
    public static Set<String> findUniqueNames(Set<String> relations, List<String> devices, List<String> assets) {
        // 合并两个列表到一个 Set 中
        Set<String> combinedLists = new HashSet<>();
        combinedLists.addAll(devices);
        combinedLists.addAll(assets);

        // 找到 Set 中但不在两个 List 中的名称
        return relations.stream()
                .filter(name -> !combinedLists.contains(name)) // 不在两个 List 中
                .collect(Collectors.toSet());
    }


    /**
     * 将 Relation 对象转换为 EntityRelation 对象
     *
     * @param relation Relation 对象，包含了关联关系的各个字段
     * @param tenantId 当前租户的 TenantId，用于查询 entityId
     * @return EntityRelation 关系对象
     */
    private EntityRelation convert(Relation relation, TenantId tenantId) {
        // 空值检查，确保传入的 Relation 和 TenantId 不为空
        if (relation == null || tenantId == null) {
            throw new IllegalArgumentException("Relation and TenantId must not be null");
        }

        // 创建一个新的 EntityRelation 对象
        EntityRelation entityRelation = new EntityRelation();

        // 获取关系的来源和目标信息
        String fromType = relation.getFromType();
        String fromName = relation.getFromName();

        // 检查 fromType 和 fromName 是否为空，防止空值导致的错误
        if (fromType == null || fromName == null) {
            log.error("Invalid fromType or fromName for Relation: {}", relation);
            throw new IllegalArgumentException("fromType and fromName cannot be null");
        }

        // 根据 fromType、fromName 和 tenantId 查找 entityId
        try {
            entityRelation.setFrom(findEntityId(fromType, fromName, tenantId));
        } catch (ThingsboardException e) {
            log.error("Error finding entityId for fromType: {}, fromName: {}", fromType, fromName, e);
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        String toType = relation.getToType();
        String toName = relation.getToName();

        // 检查 toType 和 toName 是否为空，防止空值导致的错误
        if (toType == null || toName == null) {
            log.error("Invalid toType or toName for Relation: {}", relation);
            throw new IllegalArgumentException("toType and toName cannot be null");
        }

        // 根据 toType、toName 和 tenantId 查找 entityId
        try {
            entityRelation.setTo(findEntityId(toType, toName, tenantId));
        } catch (ThingsboardException e) {
            log.error("Error finding entityId for toType: {}, toName: {}", toType, toName, e);
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        // 设置关系的其他字段
        String type = relation.getType();
        if (type != null) {
            entityRelation.setType(type); // 设置关系类型
        } else {
            log.warn("Relation type is null for Relation: {}", relation);
            throw new IllegalArgumentException("type cannot be null");
        }

        // 设置关系的类型组，使用默认的公共类型
        entityRelation.setTypeGroup(RelationTypeGroup.COMMON);

        // 解析并设置附加信息 (additionalInfo)
        JsonNode additionalInfo = relation.getAdditionalInfo();
        if (additionalInfo != null) {
            entityRelation.setAdditionalInfo(additionalInfo);
        } else {
            log.warn("Additional Info is null for Relation: {}", relation);
            throw new IllegalArgumentException("additionalInfo cannot be null");
        }

        // 返回构建的 EntityRelation 对象
        return entityRelation;
    }

    /**
     * 根据 fromType、fromName 和 tenantId 查找 entityId
     *
     * @param fromType 实体类型
     * @param fromName 实体名称
     * @param tenantId 租户 ID
     * @return entityId 实体的 ID
     * @throws ThingsboardException 如果查找实体 ID 出现问题
     */
    private EntityId findEntityId(String fromType, String fromName, TenantId tenantId) throws ThingsboardException {
        // 根据传入的 type 参数进行不同的处理
        switch (fromType) {
            // 如果 type 是 DEVICE
            case DEVICE:
                // 使用 tenantId 和 name 从 deviceService 中查找设备信息
                Device deviceByTenantIdAndName = deviceService.findDeviceByTenantIdAndName(tenantId, fromName);
                if (deviceByTenantIdAndName == null) {
                    throw new ThingsboardException("Device not found: [" + fromName + "]", ThingsboardErrorCode.ITEM_NOT_FOUND);
                }
                // 返回找到的设备的 ID
                return deviceByTenantIdAndName.getId();

            // 如果 type 是 ASSET
            case ASSET:
                // 使用 tenantId 和 name 从 assetService 中查找资产信息
                Asset assetByTenantIdAndName = assetService.findAssetByTenantIdAndName(tenantId, fromName);
                if (assetByTenantIdAndName == null) {
                    throw new ThingsboardException("Asset not found: [" + fromName + "]", ThingsboardErrorCode.ITEM_NOT_FOUND);
                }
                // 返回找到的资产的 ID
                return assetByTenantIdAndName.getId();

            // 如果 type 既不是 DEVICE 也不是 ASSET
            default:
                // 返回 null
                throw new ThingsboardException("Unsupported entity type: " + fromType, ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

    /**
     * 自动检测 CSV 分隔符方法
     * @param file csv文件
     * @return 分隔符字符
     * @throws IOException 异常
     */
    private char detectDelimiter(MultipartFile file) throws IOException {
        // 使用 BOMInputStream 自动跳过 UTF-8 BOM
        try (InputStream is = new BOMInputStream(file.getInputStream());
             BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is))) {

            String sampleLine = bufferedReader.readLine();
            if (sampleLine == null) {
                return ','; // 默认分隔符
            }

            // 候选分隔符
            char[] candidates = {',', ';', '\t', '|'};
            char bestDelimiter = ',';
            int maxColumns = 0;

            for (char delimiter : candidates) {
                int count = sampleLine.split(Pattern.quote(String.valueOf(delimiter)), -1).length;
                if (count > maxColumns) {
                    maxColumns = count;
                    bestDelimiter = delimiter;
                }
            }

            return bestDelimiter;
        }
    }

}
