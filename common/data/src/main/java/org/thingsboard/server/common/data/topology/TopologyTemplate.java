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
package org.thingsboard.server.common.data.topology;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.*;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TopologyTemplateId;
import org.thingsboard.server.common.data.validation.NoXss;

@Data
@EqualsAndHashCode(callSuper = true)
public class TopologyTemplate extends BaseDataWithAdditionalInfo<TopologyTemplateId> implements HasName, HasTenantId, HasVersion, ExportableEntity<TopologyTemplateId> {

    private TenantId tenantId;
    @NoXss
    private String name;
    @NoXss
    private String type;
    
    // We store the topology tree as a JSON node for flexibility, or we could define a POJO structure.
    // Using JsonNode allows us to evolve the structure without DB schema changes for now,
    // which aligns with how 'additionalInfo' is often used, but explicit configuration field is better.
    private JsonNode configuration; 

    private TopologyTemplateId externalId;
    @NoXss
    private String description;
    @NoXss
    private String modelVersion;
    private Long version;

    public TopologyTemplate() {
        super();
    }

    public TopologyTemplate(TopologyTemplateId id) {
        super(id);
    }

    public TopologyTemplate(TopologyTemplate topologyTemplate) {
        super(topologyTemplate);
        this.tenantId = topologyTemplate.getTenantId();
        this.name = topologyTemplate.getName();
        this.type = topologyTemplate.getType();
        this.configuration = topologyTemplate.getConfiguration();
        this.externalId = topologyTemplate.getExternalId();
        this.description = topologyTemplate.getDescription();
        this.modelVersion = topologyTemplate.getModelVersion();
        this.version = topologyTemplate.getVersion();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public TenantId getTenantId() {
        return tenantId;
    }
}
