/**
 * Copyright © 2016-2025 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.menu.MenuSetting;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.menu.MenuSettingService;
import org.thingsboard.server.queue.util.TbCoreComponent;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
public class MenuSettingController extends BaseController {

    private final MenuSettingService menuSettingService;

    @ApiOperation(value = "Get Menu Setting by Authority",
            notes = "Fetch the Menu Setting object based on the provided Authority. " + ControllerConstants.SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/menu/{authority}", method = RequestMethod.GET)
    @ResponseBody
    public MenuSetting getMenuSettingByAuthority(@PathVariable("authority") String authorityStr) throws ThingsboardException {
        checkParameter("authority", authorityStr);
        try {
            Authority authority = Authority.valueOf(authorityStr.toUpperCase());
            return menuSettingService.findMenuSettingByAuthority(authority);
        } catch (IllegalArgumentException e) {
            throw new ThingsboardException("Invalid authority: " + authorityStr, ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
    }

    @ApiOperation(value = "Create Or Update Menu Setting",
            notes = "Create or update the Menu Setting. " + ControllerConstants.SYSTEM_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('SYS_ADMIN')")
    @RequestMapping(value = "/menu", method = RequestMethod.POST)
    @ResponseBody
    public MenuSetting saveMenuSetting(@RequestBody MenuSetting menuSetting) throws ThingsboardException {
        if (menuSetting.getMenuConfig() == null || menuSetting.getMenuConfig().isEmpty()) {
            throw new ThingsboardException("Menu config cannot be null or empty!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        if (menuSetting.getAuthority() == null) {
            throw new ThingsboardException("Authority cannot be null!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        return menuSettingService.saveMenuSetting(menuSetting);
    }
}
