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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.TopologyTemplateEntity;

import java.util.UUID;

public interface TopologyTemplateRepository extends JpaRepository<TopologyTemplateEntity, UUID> {

    @Query("SELECT t FROM TopologyTemplateEntity t WHERE t.tenantId = :tenantId " +
            "AND (:searchText IS NULL OR ilike(t.name, CONCAT('%', :searchText, '%')) = true)")
    Page<TopologyTemplateEntity> findByTenantId(@Param("tenantId") UUID tenantId,
                                               @Param("searchText") String searchText,
                                               Pageable pageable);

    @Query("SELECT t FROM TopologyTemplateEntity t WHERE (t.tenantId = :tenantId OR t.tenantId = :sysTenantId) " +
            "AND (:searchText IS NULL OR ilike(t.name, CONCAT('%', :searchText, '%')) = true)")
    Page<TopologyTemplateEntity> findByTenantIdOrSysTenantId(@Param("tenantId") UUID tenantId,
                                                             @Param("sysTenantId") UUID sysTenantId,
                                                             @Param("searchText") String searchText,
                                                             Pageable pageable);

    @Query("SELECT t FROM TopologyTemplateEntity t WHERE t.tenantId = :tenantId AND t.name = :name")
    TopologyTemplateEntity findByTenantIdAndName(@Param("tenantId") UUID tenantId, @Param("name") String name);

    @Query("SELECT t FROM TopologyTemplateEntity t WHERE (t.tenantId = :tenantId OR t.tenantId = :sysTenantId) AND t.name = :name")
    TopologyTemplateEntity findByTenantIdOrSysTenantIdAndName(@Param("tenantId") UUID tenantId,
                                                               @Param("sysTenantId") UUID sysTenantId,
                                                               @Param("name") String name);
}
