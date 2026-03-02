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
package org.thingsboard.server.dao.sql.topology;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.TopologyTemplateEntity;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.topology.TopologyTemplateDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.UUID;

@SqlDao
@Component
public class JpaTopologyTemplateDao extends JpaAbstractDao<TopologyTemplateEntity, TopologyTemplate> implements TopologyTemplateDao {

    @Autowired
    private TopologyTemplateRepository topologyTemplateRepository;

    @Override
    protected Class<TopologyTemplateEntity> getEntityClass() {
        return TopologyTemplateEntity.class;
    }

    @Override
    protected JpaRepository<TopologyTemplateEntity, UUID> getRepository() {
        return topologyTemplateRepository;
    }

    @Override
    public PageData<TopologyTemplate> findTopologyTemplatesByTenantId(UUID tenantId, PageLink pageLink, boolean includeSystem) {
        if (!includeSystem || tenantId == null || tenantId.equals(org.thingsboard.server.common.data.id.TenantId.SYS_TENANT_ID.getId())) {
             return DaoUtil.toPageData(topologyTemplateRepository.findByTenantId(tenantId, pageLink.getTextSearch(), DaoUtil.toPageable(pageLink)));
        } else {
             return DaoUtil.toPageData(topologyTemplateRepository.findByTenantIdOrSysTenantId(tenantId, 
                     org.thingsboard.server.common.data.id.TenantId.SYS_TENANT_ID.getId(), 
                     pageLink.getTextSearch(), DaoUtil.toPageable(pageLink)));
        }
    }

    @Override
    public TopologyTemplate findByName(org.thingsboard.server.common.data.id.TenantId tenantId, String name) {
        if (tenantId == null || tenantId.isSysTenantId()) {
            return DaoUtil.getData(topologyTemplateRepository.findByTenantIdAndName(org.thingsboard.server.common.data.id.TenantId.SYS_TENANT_ID.getId(), name));
        } else {
            return DaoUtil.getData(topologyTemplateRepository.findByTenantIdAndName(tenantId.getId(), name));
        }
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.TOPOLOGY_TEMPLATE;
    }
}
