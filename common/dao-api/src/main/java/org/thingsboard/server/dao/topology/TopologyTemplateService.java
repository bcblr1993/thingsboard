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

import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.topology.DeploymentRequest;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TopologyTemplateId;

public interface TopologyTemplateService {

    TopologyTemplate findTopologyTemplateById(TenantId tenantId, TopologyTemplateId topologyTemplateId);

    TopologyTemplate saveTopologyTemplate(TopologyTemplate topologyTemplate);

    org.thingsboard.server.common.data.topology.PreviewNode deployTopology(TenantId tenantId, DeploymentRequest request, org.thingsboard.server.common.data.User user) throws org.thingsboard.server.common.data.exception.ThingsboardException;
    
    org.thingsboard.server.common.data.topology.PreviewNode previewTopology(TenantId tenantId, DeploymentRequest request);

    void deleteTopologyTemplate(TenantId tenantId, TopologyTemplateId topologyTemplateId);

    PageData<TopologyTemplate> findTopologyTemplatesByTenantId(TenantId tenantId, PageLink pageLink, boolean includeSystem);
}
