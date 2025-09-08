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
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLJsonPGObjectJsonbType;
import org.thingsboard.server.common.data.id.MenuSettingId;
import org.thingsboard.server.common.data.menu.MenuSetting;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.model.BaseSqlEntity;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = ModelConstants.MENU_SETTINGS_TABLE_NAME)
public class MenuSettingEntity extends BaseSqlEntity<MenuSetting> {

    @Enumerated(EnumType.STRING)
    @Column(name = ModelConstants.MENU_SETTINGS_AUTHORITY_PROPERTY)
    private Authority authority;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = ModelConstants.MENU_SETTINGS_MENU_CONFIG_PROPERTY, columnDefinition = "jsonb")
    private JsonNode menuConfig;

    public MenuSettingEntity() {
    }

    public MenuSettingEntity(MenuSetting menuSetting) {
        if (menuSetting.getId() != null) {
            this.setId(menuSetting.getId().getId());
        }
        this.createdTime = menuSetting.getCreatedTime();
        this.authority = menuSetting.getAuthority();
        this.menuConfig = menuSetting.getMenuConfig();
    }

    @Override
    public MenuSetting toData() {
        MenuSetting menuSetting = new MenuSetting();
        menuSetting.setId(new MenuSettingId(this.getUuid()));
        menuSetting.setCreatedTime(createdTime);
        menuSetting.setAuthority(authority);
        menuSetting.setMenuConfig(menuConfig);
        return menuSetting;
    }
}
