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
package org.thingsboard.server.dao.menu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.menu.MenuSetting;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.DataValidator;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaseMenuSettingService implements MenuSettingService {

    private final MenuSettingDao menuSettingDao;

    @Override
    public JsonNode getMenuForAuthority(Authority authority) {
        log.trace("Executing getMenuForAuthority [{}]", authority);
        MenuSetting menuSetting = menuSettingDao.findByAuthority(authority);
        if (menuSetting != null) {
            return menuSetting.getMenuConfig();
        } else {
            throw new RuntimeException("Menu setting for authority '" + authority + "' not found!");
        }
    }

    @Override
    public MenuSetting findMenuSettingByAuthority(Authority authority) {
        log.trace("Executing findMenuSettingByAuthority [{}]", authority);
        return menuSettingDao.findByAuthority(authority);
    }

    @Override
    public MenuSetting saveMenuSetting(MenuSetting menuSetting) {
        log.trace("Executing saveMenuSetting [{}]", menuSetting);
        if (menuSetting.getAuthority() == null) {
            throw new DataValidationException("Authority should be specified!");
        }

        MenuSetting existingSetting = menuSettingDao.findByAuthority(menuSetting.getAuthority());

        if (existingSetting != null) {
            existingSetting.setMenuConfig(menuSetting.getMenuConfig());
            return menuSettingDao.save(TenantId.SYS_TENANT_ID, existingSetting);
        } else {
            return menuSettingDao.save(TenantId.SYS_TENANT_ID, menuSetting);
        }
    }
}
