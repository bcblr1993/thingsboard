package org.thingsboard.server.common.data.menu;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.BaseData;
import org.thingsboard.server.common.data.id.MenuSettingId;
import org.thingsboard.server.common.data.security.Authority;

@Data
@EqualsAndHashCode(callSuper = true)
public class MenuSetting extends BaseData<MenuSettingId> {
    private Authority authority;
    private JsonNode menuConfig;
}
