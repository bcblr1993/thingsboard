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

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TopologyTemplateId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.topology.DeploymentRequest;
import org.thingsboard.server.common.data.topology.TopologyTemplate;
import org.thingsboard.server.dao.topology.TopologyTemplateService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.common.data.security.Authority;

import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TOPOLOGY_TEMPLATE_ID_PARAM_DESCRIPTION;

@RestController
@TbCoreComponent
@RequestMapping("/api")
public class TopologyTemplateController extends BaseController {

    @Autowired
    private TopologyTemplateService topologyTemplateService;

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/topology-template/{topologyTemplateId}", method = RequestMethod.GET)
    @ResponseBody
    public TopologyTemplate getTopologyTemplateById(
            @Parameter(description = TOPOLOGY_TEMPLATE_ID_PARAM_DESCRIPTION)
            @PathVariable("topologyTemplateId") String strTopologyTemplateId) throws ThingsboardException {
        checkParameter("topologyTemplateId", strTopologyTemplateId);
        TopologyTemplateId topologyTemplateId = new TopologyTemplateId(toUUID(strTopologyTemplateId));
        return checkEntityId(topologyTemplateId, topologyTemplateService::findTopologyTemplateById, Operation.READ);
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @RequestMapping(value = "/topology-template", method = RequestMethod.POST)
    @ResponseBody
    public TopologyTemplate saveTopologyTemplate(@RequestBody TopologyTemplate topologyTemplate) throws ThingsboardException {
        topologyTemplate.setTenantId(getTenantId());
        checkEntity(topologyTemplate.getId(), topologyTemplate, Resource.TOPOLOGY_TEMPLATE);
        return topologyTemplateService.saveTopologyTemplate(topologyTemplate);
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/topology-template/deploy", method = RequestMethod.POST)
    @ResponseBody
    public org.thingsboard.server.common.data.topology.PreviewNode deployTopology(@RequestBody DeploymentRequest request) throws ThingsboardException {
        return topologyTemplateService.deployTopology(getTenantId(), request, getCurrentUser());
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/topology-template/preview", method = RequestMethod.POST)
    @ResponseBody
    public org.thingsboard.server.common.data.topology.PreviewNode previewTopology(@RequestBody DeploymentRequest request) throws ThingsboardException {
        return topologyTemplateService.previewTopology(getTenantId(), request);
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @RequestMapping(value = "/topology-template/{topologyTemplateId}", method = RequestMethod.DELETE)
    @ResponseBody
    public void deleteTopologyTemplate(
            @Parameter(description = TOPOLOGY_TEMPLATE_ID_PARAM_DESCRIPTION)
            @PathVariable("topologyTemplateId") String strTopologyTemplateId) throws ThingsboardException {
        checkParameter("topologyTemplateId", strTopologyTemplateId);
        TopologyTemplateId topologyTemplateId = new TopologyTemplateId(toUUID(strTopologyTemplateId));
        TopologyTemplate template = checkEntityId(topologyTemplateId, topologyTemplateService::findTopologyTemplateById, Operation.DELETE);
        if (template.getTenantId().isSysTenantId() && !getCurrentUser().getAuthority().equals(Authority.SYS_ADMIN)) {
            throw new ThingsboardException("Only System Administrator can delete system models!", ThingsboardErrorCode.PERMISSION_DENIED);
        }
        topologyTemplateService.deleteTopologyTemplate(getTenantId(), topologyTemplateId);
    }

    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/topology-templates", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<TopologyTemplate> getTopologyTemplates(
            @Parameter(description = PAGE_SIZE_DESCRIPTION)
            @RequestParam(value = "pageSize") int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION)
            @RequestParam(value = "page") int page,
            @Parameter(description = "The case-insensitive 'substring' filter based on search text property.")
            @RequestParam(value = "textSearch", required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION, schema = @Schema(allowableValues = {"createdTime", "name"}))
            @RequestParam(value = "sortProperty", required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION, schema = @Schema(allowableValues = {"ASC", "DESC"}))
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            @Parameter(description = "Whether to include system templates.")
            @RequestParam(value = "includeSystem", required = false, defaultValue = "true") boolean includeSystem) throws ThingsboardException {
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        return topologyTemplateService.findTopologyTemplatesByTenantId(getTenantId(), pageLink, includeSystem);
    }
}
