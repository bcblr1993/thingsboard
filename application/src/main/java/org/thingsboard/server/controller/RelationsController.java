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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.relations.ApiResponse;
import org.thingsboard.server.service.relations.RelationsBulkImportService;
import org.thingsboard.server.service.relations.TreeViewVO;
import org.thingsboard.server.service.security.model.SecurityUser;

import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

/**
 * 设备 资产 关联关系导入controller
 *
 * @author junhao.feng
 */
@Slf4j
@TbCoreComponent
@RequestMapping("/api/relations")
@RestController
public class RelationsController {

    @Autowired
    private RelationsBulkImportService relationsBulkImportService;


    /**
     * 导入CSV文件并转换为Relation对象列表。
     *
     * @param file CSV文件
     */
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/bulk_import", method = RequestMethod.POST, consumes = MULTIPART_FORM_DATA_VALUE)
    public void bulkImport(@Parameter(description = "CSV")
                           @RequestPart MultipartFile file) throws Exception {

        relationsBulkImportService.processBulkImport(file, getTenantId(), getCurrentUser().getCustomerId(), getCurrentUser());

    }


    /**
     * 导入CSV文件并转换为Relation关系列表。
     *
     * @param device   CSV文件
     * @param asset    CSV文件
     * @param relation CSV文件
     */
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tree_view", method = RequestMethod.POST, consumes = MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TreeViewVO> treeView(@Parameter(description = "device") @RequestPart MultipartFile device,
                                @Parameter(description = "asset") @RequestPart MultipartFile asset,
                                @Parameter(description = "relation") @RequestPart MultipartFile relation) throws Exception {

        TreeViewVO treeViewVO = relationsBulkImportService.processTreeView(device, asset, relation);

        return ApiResponse.success(treeViewVO);

    }


    /**
     * 获取当前用户所属的租户ID
     *
     * @return TenantId 当前用户的租户ID
     * @throws ThingsboardException 如果无法获取当前用户的信息，将抛出授权异常
     */
    protected TenantId getTenantId() throws ThingsboardException {
        // 调用getCurrentUser()方法获取当前用户对象，并从中获取租户ID
        return getCurrentUser().getTenantId();
    }

    /**
     * 获取当前登录的用户信息
     *
     * @return SecurityUser 当前登录的用户对象
     * @throws ThingsboardException 如果未登录或无法获取用户信息，将抛出授权异常
     */
    protected SecurityUser getCurrentUser() throws ThingsboardException {
        // 从Spring Security的SecurityContext中获取当前认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 判断authentication不为null，并且其主体对象是SecurityUser实例
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
            // 如果认证通过并且主体是SecurityUser类型，则返回当前用户
            return (SecurityUser) authentication.getPrincipal();
        } else {
            // 如果未通过认证或者主体不是SecurityUser类型，抛出授权异常
            throw new ThingsboardException("You aren't authorized to perform this operation!", ThingsboardErrorCode.AUTHENTICATION);
        }
    }

}
