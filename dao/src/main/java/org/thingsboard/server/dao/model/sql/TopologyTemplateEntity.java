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
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TopologyTemplateId;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import org.thingsboard.server.dao.model.BaseSqlEntity;
import org.thingsboard.server.dao.model.ModelConstants;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = ModelConstants.TOPOLOGY_TEMPLATE_COLUMN_FAMILY_NAME)
public class TopologyTemplateEntity extends BaseSqlEntity<TopologyTemplate> {

    @Column(name = ModelConstants.TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ModelConstants.NAME_PROPERTY)
    private String name;

    @Column(name = ModelConstants.TOPOLOGY_TEMPLATE_TYPE_PROPERTY)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = ModelConstants.TOPOLOGY_TEMPLATE_CONFIGURATION_PROPERTY)
    private JsonNode configuration;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = ModelConstants.ADDITIONAL_INFO_PROPERTY)
    private JsonNode additionalInfo;

    @Column(name = ModelConstants.EXTERNAL_ID_PROPERTY)
    private UUID externalId;

    @Column(name = ModelConstants.TOPOLOGY_TEMPLATE_DESCRIPTION_PROPERTY)
    private String description;

    @Column(name = ModelConstants.TOPOLOGY_TEMPLATE_MODEL_VERSION_PROPERTY)
    private String modelVersion;

    public TopologyTemplateEntity() {
        super();
    }

    public TopologyTemplateEntity(TopologyTemplate topologyTemplate) {
        if (topologyTemplate.getId() != null) {
            this.setUuid(topologyTemplate.getId().getId());
        }
        this.setCreatedTime(topologyTemplate.getCreatedTime());
        this.tenantId = topologyTemplate.getTenantId().getId();
        this.name = topologyTemplate.getName();
        this.type = topologyTemplate.getType();
        this.configuration = topologyTemplate.getConfiguration();
        this.additionalInfo = topologyTemplate.getAdditionalInfo();
        this.description = topologyTemplate.getDescription();
        this.modelVersion = topologyTemplate.getModelVersion();
        this.externalId = topologyTemplate.getExternalId() != null ? topologyTemplate.getExternalId().getId() : null;
    }

    @Override
    public TopologyTemplate toData() {
        TopologyTemplate topologyTemplate = new TopologyTemplate(new TopologyTemplateId(getUuid()));
        topologyTemplate.setCreatedTime(getCreatedTime());
        topologyTemplate.setTenantId(TenantId.fromUUID(tenantId));
        topologyTemplate.setName(name);
        topologyTemplate.setType(type);
        topologyTemplate.setConfiguration(configuration);
        topologyTemplate.setAdditionalInfo(additionalInfo);
        topologyTemplate.setDescription(description);
        topologyTemplate.setModelVersion(modelVersion);
        if (externalId != null) {
            topologyTemplate.setExternalId(new TopologyTemplateId(externalId));
        }
        return topologyTemplate;
    }
}
