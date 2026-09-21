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

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class SqlEntityDatabaseSchemaServiceTest {

    @Test
    public void givenPsqlDbSchemaService_whenCreateDatabaseSchema_thenVerifyPsqlIndexSpecificCall() throws Exception {
        SqlEntityDatabaseSchemaService service = spy(new SqlEntityDatabaseSchemaService());
        willDoNothing().given(service).executeQueryFromFile(anyString());

        service.createDatabaseSchema();

        verify(service, times(1)).createDatabaseIndexes();
        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_SQL);
        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_IDX_SQL);
        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_IDX_PSQL_ADDON_SQL);
        verify(service, times(3)).executeQueryFromFile(anyString());
    }

    @Test
    public void givenPsqlDbSchemaService_whenCreateDatabaseIndexes_thenVerifyPsqlIndexSpecificCall() throws Exception {
        SqlEntityDatabaseSchemaService service = spy(new SqlEntityDatabaseSchemaService());
        willDoNothing().given(service).executeQueryFromFile(anyString());

        service.createDatabaseIndexes();

        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_IDX_SQL);
        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_IDX_PSQL_ADDON_SQL);
        verify(service, times(2)).executeQueryFromFile(anyString());
    }

    // ---- KingbaseES oracle 兼容模式 addon ----

    private SqlEntityDatabaseSchemaService givenServiceInMode(String databaseMode) throws Exception {
        SqlEntityDatabaseSchemaService service = spy(new SqlEntityDatabaseSchemaService());
        ReflectionTestUtils.setField(service, "dbUserName", "tb_app");
        willDoNothing().given(service).executeQueryFromFile(anyString());
        given(service.detectKingbaseMode()).willReturn(databaseMode);
        return service;
    }

    @Test
    public void givenPostgreSql_whenApplyAddon_thenSkipped() throws Exception {
        // 目标库不是金仓时 database_mode 查不到，整条金仓分支都不应进入
        SqlEntityDatabaseSchemaService service = givenServiceInMode(null);

        service.applyKingbaseOracleAddonIfNeeded();

        verify(service, never()).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
        verify(service, never()).hasSysSchemaCreatePrivilege();
    }

    @Test
    public void givenKingbasePgMode_whenApplyAddon_thenSkipped() throws Exception {
        // 金仓 pg 模式没有 sys.concat 遮蔽问题，不需要补丁
        SqlEntityDatabaseSchemaService service = givenServiceInMode("pg");

        service.applyKingbaseOracleAddonIfNeeded();

        verify(service, never()).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
    }

    @Test
    public void givenKingbaseMysqlMode_whenApplyAddon_thenSkipped() throws Exception {
        SqlEntityDatabaseSchemaService service = givenServiceInMode("mysql");

        service.applyKingbaseOracleAddonIfNeeded();

        verify(service, never()).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
    }

    @Test
    public void givenKingbaseOracleModeAndAddonAlreadyApplied_whenApplyAddon_thenSkippedAsIdempotent() throws Exception {
        // 重复安装时补丁已在，不应再执行，也不应因此要求 sys 权限
        SqlEntityDatabaseSchemaService service = givenServiceInMode("oracle");
        given(service.isMultiArgConcatAvailable()).willReturn(true);

        service.applyKingbaseOracleAddonIfNeeded();

        verify(service, never()).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
        verify(service, never()).hasSysSchemaCreatePrivilege();
    }

    @Test
    public void givenKingbaseOracleModeWithoutSysPrivilege_whenApplyAddon_thenFailsWithActionableMessage() throws Exception {
        SqlEntityDatabaseSchemaService service = givenServiceInMode("oracle");
        given(service.isMultiArgConcatAvailable()).willReturn(false);
        given(service.hasSysSchemaCreatePrivilege()).willReturn(false);

        assertThatThrownBy(service::applyKingbaseOracleAddonIfNeeded)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no CREATE privilege on schema 'sys'")
                .hasMessageContaining("GRANT CREATE ON SCHEMA sys TO tb_app");

        verify(service, never()).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
    }

    @Test
    public void givenKingbaseOracleModeWithSysPrivilege_whenApplyAddon_thenAddonExecuted() throws Exception {
        SqlEntityDatabaseSchemaService service = givenServiceInMode("oracle");
        // 执行前不可用、执行后可用
        given(service.isMultiArgConcatAvailable()).willReturn(false, true);
        given(service.hasSysSchemaCreatePrivilege()).willReturn(true);

        assertThatCode(service::applyKingbaseOracleAddonIfNeeded).doesNotThrowAnyException();

        verify(service, times(1)).executeQueryFromFile(SqlEntityDatabaseSchemaService.SCHEMA_ENTITIES_KINGBASE_ORACLE_ADDON_SQL);
    }

    @Test
    public void givenAddonExecutedButStillIneffective_whenApplyAddon_thenFails() throws Exception {
        // 补丁执行了却没生效（例如被同名对象遮蔽），必须中断安装而不是放行
        SqlEntityDatabaseSchemaService service = givenServiceInMode("oracle");
        given(service.isMultiArgConcatAvailable()).willReturn(false, false);
        given(service.hasSysSchemaCreatePrivilege()).willReturn(true);

        assertThatThrownBy(service::applyKingbaseOracleAddonIfNeeded)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("still unavailable");
    }

}
