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
package org.thingsboard.server.service.install;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Service
@Profile("install")
@Slf4j
public class SqlEntityDatabaseSchemaService extends SqlAbstractDatabaseSchemaService
        implements EntityDatabaseSchemaService {
    public static final String SCHEMA_ENTITIES_SQL = "schema-entities.sql";
    public static final String SCHEMA_ENTITIES_IDX_SQL = "schema-entities-idx.sql";
    public static final String SCHEMA_ENTITIES_IDX_PSQL_ADDON_SQL = "schema-entities-idx-psql-addon.sql";
    public static final String SCHEMA_VIEWS_AND_FUNCTIONS_SQL = "schema-views-and-functions.sql";
    public static final String SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL = "schema-entities-kingbase-oracle-addon.sql";

    static final String KINGBASE_ORACLE_MODE = "oracle";

    public SqlEntityDatabaseSchemaService() {
        super(SCHEMA_ENTITIES_SQL, SCHEMA_ENTITIES_IDX_SQL);
    }

    @Override
    public void createDatabaseSchema(boolean createIndexes) throws Exception {
        super.createDatabaseSchema(createIndexes);
        applyKingbaseOracleAddonIfNeeded();
    }

    /**
     * KingbaseES(人大金仓) 的 oracle 兼容模式下补一个 VARIADIC concat 重载，
     * 目标库不是金仓、或不是 oracle 兼容模式时直接跳过，不做任何事。
     *
     * 该补丁不可省略：缺失时 TB 原生查询中的 concat(a, b, c) 会被解析到只有二元重载的
     * sys.concat 而报错，且失败形态是查询报错/返回空，不是启动失败，很难在部署后及时发现。
     * 因此这里在安装期一次性完成「探测 - 权限预检 - 执行 - 结果复验」，任何一步不通过都直接
     * 中断安装并给出可操作的修复指引。
     */
    void applyKingbaseOracleAddonIfNeeded() throws Exception {
        String databaseMode = detectKingbaseMode();
        if (!KINGBASE_ORACLE_MODE.equalsIgnoreCase(databaseMode)) {
            return;
        }
        log.info("Detected KingbaseES 'oracle' compatibility mode, applying addon: {}", SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);

        if (isMultiArgConcatAvailable()) {
            log.info("Multi-argument concat is already available, skipping the addon");
            return;
        }
        if (!hasSysSchemaCreatePrivilege()) {
            throw new RuntimeException(kingbasePrivilegeErrorMessage());
        }

        executeQueryFromFile(SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);

        if (!isMultiArgConcatAvailable()) {
            throw new RuntimeException("Failed to install the KingbaseES oracle mode addon: multi-argument concat is " +
                    "still unavailable after executing " + SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL + ". " +
                    kingbasePrivilegeErrorMessage());
        }
        log.info("KingbaseES oracle mode addon applied successfully");
    }

    /**
     * 探测金仓兼容模式：pg / oracle / mysql；目标库不是金仓时返回 null。
     *
     * 判断依据是金仓独有的 database_mode 参数，而不是 DatabaseMetaData 的产品名 ——
     * 用 PostgreSQL 官方驱动接入金仓时产品名返回的是 "PostgreSQL"（只有金仓自有驱动才返回
     * "KingbaseES"），按产品名判断会漏判。current_setting 的第二个参数为 missing_ok，
     * 使该查询在 PostgreSQL 上返回 NULL 而不是抛异常。
     */
    String detectKingbaseMode() {
        return queryString("SELECT current_setting('database_mode', true)");
    }

    /**
     * 以「实际能否调用」为准复验补丁效果，而不是查 pg_proc 是否存在该函数 ——
     * 函数存在不等于能被名字解析命中。
     */
    boolean isMultiArgConcatAvailable() {
        return "abc".equals(queryString("SELECT concat('a', 'b', 'c')"));
    }

    boolean hasSysSchemaCreatePrivilege() {
        return Boolean.parseBoolean(queryString("SELECT has_schema_privilege(current_user, 'sys', 'CREATE')"));
    }

    private String queryString(String sql) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            log.debug("Query [{}] failed, treating the result as absent", sql, e);
            return null;
        }
    }

    private String kingbasePrivilegeErrorMessage() {
        return "KingbaseES is running in 'oracle' compatibility mode, which requires the " +
                SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL + " addon, but the current user '" + dbUserName +
                "' has no CREATE privilege on schema 'sys'. Without the addon every native query using " +
                "concat(a, b, c) fails at runtime. Fix it in one of two ways and restart the installation: " +
                "(1) run the installation with a KingbaseES DBA account (e.g. system), or " +
                "(2) ask a DBA to grant the privilege: GRANT CREATE ON SCHEMA sys TO " + dbUserName + ";";
    }

    @Override
    public void createDatabaseIndexes() throws Exception {
        super.createDatabaseIndexes();
        log.info("Installing SQL DataBase schema PostgreSQL specific indexes part: " + SCHEMA_ENTITIES_IDX_PSQL_ADDON_SQL);
        executeQueryFromFile(SCHEMA_ENTITIES_IDX_PSQL_ADDON_SQL);
    }

    @Override
    public void createOrUpdateDeviceInfoView(boolean activityStateInTelemetry) {
        String sourceViewName = activityStateInTelemetry ? "device_info_active_ts_view" : "device_info_active_attribute_view";
        executeQuery("DROP VIEW IF EXISTS device_info_view CASCADE;");
        executeQuery("CREATE OR REPLACE VIEW device_info_view AS SELECT * FROM " + sourceViewName + ";");
    }

    @Override
    public void createOrUpdateViewsAndFunctions() throws Exception {
        log.info("Installing SQL DataBase schema views and functions: " + SCHEMA_VIEWS_AND_FUNCTIONS_SQL);
        executeQueryFromFile(SCHEMA_VIEWS_AND_FUNCTIONS_SQL);
    }

}
