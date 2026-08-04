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
package org.thingsboard.server.dao.timeseries.iotdb;

import org.apache.iotdb.session.pool.SessionPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Startup robustness for {@code ensureDatabase()}: only "database already exists" (IoTDB
 * error 501) may be swallowed silently. Any real fault — auth failure (801), network,
 * permission, {@code dn_rpc_address} not reachable — MUST fail-fast so ThingsBoard does not
 * boot "healthy" and only start failing writes (and piling up Kafka lag) after telemetry
 * begins. Regression for the original {@code catch(Exception) → debug log} that treated
 * every error as "already exists".
 */
public class IotdbSessionPoolConfigTest {

    private SessionPool pool;
    private IotdbSessionPoolConfig config;

    @BeforeEach
    public void setUp() throws Exception {
        pool = mock(SessionPool.class);
        config = new IotdbSessionPoolConfig();
        set("sessionPool", pool);
        set("database", "root.tb");
        set("ttlMs", 0L);
        set("initRetries", 2);        // 2 attempts so retry loop is exercised
        set("initRetryIntervalMs", 0L); // no sleep in tests
    }

    private void set(String field, Object value) throws Exception {
        java.lang.reflect.Field f = IotdbSessionPoolConfig.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(config, value);
    }

    private void invokeEnsureDatabase() throws Exception {
        Method m = IotdbSessionPoolConfig.class.getDeclaredMethod("ensureDatabase");
        m.setAccessible(true);
        try {
            m.invoke(config);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private void invokeValidateFeatureCompatibility() throws Exception {
        Method m = IotdbSessionPoolConfig.class.getDeclaredMethod("validateFeatureCompatibility");
        m.setAccessible(true);
        try {
            m.invoke(config);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    public void iotdbLatestWithEdqsSyncMustFailFast() throws Exception {
        set("tsLatestType", "iotdb");
        set("edqsSyncEnabled", true);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, this::invokeValidateFeatureCompatibility);
        assertTrue(ex.getMessage().contains("TB_EDQS_SYNC_ENABLED=false"));
    }

    @Test
    public void iotdbLatestWithEdqsApiMustFailFast() throws Exception {
        set("tsLatestType", "iotdb");
        set("edqsApiSupported", true);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, this::invokeValidateFeatureCompatibility);
        assertTrue(ex.getMessage().contains("TB_EDQS_API_SUPPORTED=false"));
    }

    @Test
    public void nonIotdbLatestBackendsAreUnaffectedByEdqsGuard() throws Exception {
        set("edqsSyncEnabled", true);
        set("edqsApiSupported", true);
        for (String backend : new String[]{"sql", "cassandra", "redis", "redis-cluster", "timescale"}) {
            set("tsLatestType", backend);
            assertDoesNotThrow(this::invokeValidateFeatureCompatibility, backend);
        }
    }

    @Test
    public void databaseAlreadyExistsIsAcceptedSilently() throws Exception {
        doThrow(new RuntimeException("501: root.tb has already been created as database"))
                .when(pool).executeNonQueryStatement("create database root.tb");
        invokeEnsureDatabase(); // must NOT throw — restart is normal
    }

    @Test
    public void freshCreateSucceeds() throws Exception {
        doNothing().when(pool).executeNonQueryStatement("create database root.tb");
        invokeEnsureDatabase(); // must NOT throw
    }

    @Test
    public void authFailureMustFailFast() throws Exception {
        doThrow(new RuntimeException("801: Authentication failed."))
                .when(pool).executeNonQueryStatement("create database root.tb");
        RuntimeException ex = assertThrows(RuntimeException.class, this::invokeEnsureDatabase);
        assertTrue(ex.getMessage().contains("初始化失败"),
                "auth failure must surface as a startup failure, not be swallowed");
    }

    @Test
    public void globalTtlIsSetWhenPositive() throws Exception {
        set("ttlMs", 86400000L);
        doNothing().when(pool).executeNonQueryStatement("create database root.tb");
        invokeEnsureDatabase();
        // 正数 TTL → 设置全局 TTL
        org.mockito.Mockito.verify(pool).executeNonQueryStatement("set ttl to root.tb.** 86400000");
    }

    @Test
    public void staleGlobalTtlIsUnsetWhenZero() throws Exception {
        // setUp 中 ttlMs=0: "永不过期"语义要求主动清除可能残留的旧 TTL
        doNothing().when(pool).executeNonQueryStatement("create database root.tb");
        invokeEnsureDatabase();
        org.mockito.Mockito.verify(pool).executeNonQueryStatement("unset ttl to root.tb.**");
    }

    @Test
    public void ttlSetFailureDoesNotFailFastByDefault() throws Exception {
        set("ttlMs", 86400000L);
        // ttlFailFast 默认 false(未设置): 设置失败仅 ERROR, 不阻断启动
        doNothing().when(pool).executeNonQueryStatement("create database root.tb");
        doThrow(new RuntimeException("no privilege"))
                .when(pool).executeNonQueryStatement("set ttl to root.tb.** 86400000");
        invokeEnsureDatabase(); // 必须不抛
    }

    @Test
    public void ttlSetFailureFailsFastWhenConfigured() throws Exception {
        set("ttlMs", 86400000L);
        set("ttlFailFast", true);
        doNothing().when(pool).executeNonQueryStatement("create database root.tb");
        doThrow(new RuntimeException("no privilege"))
                .when(pool).executeNonQueryStatement("set ttl to root.tb.** 86400000");
        RuntimeException ex = assertThrows(RuntimeException.class, this::invokeEnsureDatabase);
        assertTrue(ex.getMessage().contains("ttl_fail_fast=true"),
                "fail-fast 场景应把 TTL 设置失败上抛为启动失败");
    }

    @Test
    public void connectionFailureMustFailFast() throws Exception {
        doThrow(new RuntimeException("Fail to reconnect to server. Please check server status"))
                .when(pool).executeNonQueryStatement("create database root.tb");
        Exception ex = assertThrows(RuntimeException.class, this::invokeEnsureDatabase);
        assertInstanceOf(RuntimeException.class, ex);
        assertTrue(ex.getMessage().contains("dn_rpc_address"),
                "message must guide the operator to the common root causes");
    }
}
