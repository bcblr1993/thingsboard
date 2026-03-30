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
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.hierarchy.HierarchyNode;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.topology.EntityTopologyService;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
public class EntityTopologyController extends BaseController {

    private final EntityTopologyService entityTopologyService;

    @Hidden
    @ApiOperation(value = "Get Entity Topology",
            notes = "Returns the entire entity topology tree for the current tenant.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/topology", method = RequestMethod.GET)
    @ResponseBody
    public HierarchyNode getEntityTopology() throws ThingsboardException {
        return entityTopologyService.getEntityTopology(getTenantId());
    }

}
