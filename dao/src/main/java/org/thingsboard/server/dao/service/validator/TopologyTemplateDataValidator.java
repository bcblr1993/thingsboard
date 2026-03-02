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
package org.thingsboard.server.dao.service.validator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.topology.TopologyTemplateDao;
import org.thingsboard.server.dao.tenant.TenantService;

@Component
public class TopologyTemplateDataValidator extends DataValidator<TopologyTemplate> {

    @Autowired
    private TopologyTemplateDao topologyTemplateDao;

    @Autowired
    @Lazy
    private TenantService tenantService;

    @Override
    protected void validateCreate(TenantId tenantId, TopologyTemplate topologyTemplate) {
        validateNameUniqueness(tenantId, topologyTemplate);
    }

    private void validateNameUniqueness(TenantId tenantId, TopologyTemplate topologyTemplate) {
        TopologyTemplate found = topologyTemplateDao.findByName(tenantId, topologyTemplate.getName());
        if (found != null && (topologyTemplate.getId() == null || !found.getId().equals(topologyTemplate.getId()))) {
            throw new DataValidationException("Topology template with such name already exists!");
        }
    }

    @Override
    protected TopologyTemplate validateUpdate(TenantId tenantId, TopologyTemplate topologyTemplate) {
        TopologyTemplate old = topologyTemplateDao.findById(tenantId, topologyTemplate.getId().getId());
        if (old == null) {
            throw new DataValidationException("Can't update non existing topology template!");
        }
        validateNameUniqueness(tenantId, topologyTemplate);
        return old;
    }

    @Override
    protected void validateDataImpl(TenantId tenantId, TopologyTemplate topologyTemplate) {
        if (StringUtils.isEmpty(topologyTemplate.getName())) {
            throw new DataValidationException("Topology template name should be specified!");
        }
        if (topologyTemplate.getTenantId() == null) {
            throw new DataValidationException("Topology template should be assigned to tenant!");
        } else {
            if (!topologyTemplate.getTenantId().isSysTenantId() && !tenantService.tenantExists(topologyTemplate.getTenantId())) {
                throw new DataValidationException("Topology template is referenced to non-existent tenant!");
            }
        }
    }
}
