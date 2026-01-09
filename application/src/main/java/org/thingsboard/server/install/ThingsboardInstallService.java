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
package org.thingsboard.server.install;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.service.component.ComponentDiscoveryService;
import org.thingsboard.server.service.install.*;
import org.thingsboard.server.service.install.migrate.TsLatestMigrateService;
import org.thingsboard.server.service.install.update.CacheCleanupService;
import org.thingsboard.server.service.install.update.DataUpdateService;

@Service
@Profile("install")  // 仅在 "install" profile 下实例化（用于首次安装或升级模式，通常通过启动参数激活）
@Slf4j
public class ThingsboardInstallService {

    // 是否为升级模式（true: 从旧版本升级；false: 全新安装）
    @Value("${install.upgrade:false}")
    private Boolean isUpgrade;

    // 升级来源版本（特殊值如 "cassandra-latest-to-postgres" 用于 Cassandra → PostgreSQL 迁移）
    @Value("${install.upgrade.from_version:}")
    private String upgradeFromVersion;

    // 是否加载 demo 数据（仅新安装有效）
    @Value("${install.load_demo:false}")
    private Boolean loadDemo;

    // 是否将设备信息持久化到遥测表（影响视图创建）
    @Value("${state.persistToTelemetry:false}")
    private boolean persistToTelemetry;

    @Autowired
    private EntityDatabaseSchemaService entityDatabaseSchemaService;  // 实体数据库 schema 服务（表、视图、索引等）

    @Autowired(required = false)
    private NoSqlKeyspaceService noSqlKeyspaceService;  // NoSQL（Cassandra）schema 服务（可选，hybrid 模式）

    @Autowired
    private TsDatabaseSchemaService tsDatabaseSchemaService;  // 时序数据库 schema 服务

    @Autowired(required = false)
    private TsLatestDatabaseSchemaService tsLatestDatabaseSchemaService;  // Latest 时序 schema 服务（可选）

    @Autowired
    private DatabaseEntitiesUpgradeService databaseEntitiesUpgradeService;  // 官方实体数据库升级服务（执行 schema_update.sql）

    @Autowired
    private ComponentDiscoveryService componentDiscoveryService;  // 组件发现服务（扫描 Rule Node 等）

    @Autowired
    private ApplicationContext context;  // Spring 上下文（用于退出应用）

    @Autowired
    private SystemDataLoaderService systemDataLoaderService;  // 系统数据加载服务（管理员、设置、widgets 等）

    @Autowired
    private DataUpdateService dataUpdateService;  // 数据更新服务（复杂升级逻辑，如 Rule Nodes）

    @Autowired
    private CacheCleanupService cacheCleanupService;  // 缓存清理服务

    @Autowired(required = false)
    private TsLatestMigrateService latestMigrateService;  // Cassandra latest 数据迁移服务（特殊路径）

    @Autowired
    private InstallScripts installScripts;  // 安装脚本服务（LwM2M 资源、图片等）

    @Autowired
    private CustomSqlDatabaseUpgradeService customSqlDatabaseUpgradeService;  // 自定义 SQL 升级服务（扩展点）


    @Autowired
    private DatabaseSchemaSettingsService databaseSchemaVersionService;  // 数据库 schema 版本管理服务

    /**
     * 核心入口方法：执行 ThingsBoard 的安装或升级
     * - 新安装：创建所有 schema、加载系统数据、可选加载 demo。
     * - 升级：验证版本、执行官方升级脚本、自定义升级、更新 schema。
     * 执行完成后强制退出应用（install 模式特性）。
     */
    public void performInstall() {
        try {
            if (isUpgrade) {  // ------------------ 升级模式 ------------------
                // 特殊迁移路径：Cassandra latest → PostgreSQL
                if ("cassandra-latest-to-postgres".equals(upgradeFromVersion)) {
                    log.info("Migrating ThingsBoard latest timeseries data from cassandra to SQL database ...");
                    latestMigrateService.migrate();
                } else {
                    // 标准升级流程
                    // TODO: 每次发布后记得更新 DefaultDatabaseSchemaSettingsService 中的 SUPPORTED_VERSIONS_FOR_UPGRADE
                    databaseSchemaVersionService.validateSchemaSettings();  // 验证版本兼容性

                    String fromVersion = databaseSchemaVersionService.getDbSchemaVersion();
                    String toVersion = databaseSchemaVersionService.getPackageSchemaVersion();
                    log.info("Upgrading ThingsBoard from version {} to {} ...", fromVersion, toVersion);

                    cacheCleanupService.clearCache();  // 清理缓存，避免旧数据干扰

                    // 执行官方 schema_update.sql（包含 DDL/DML 变更现有表）
                    databaseEntitiesUpgradeService.upgradeDatabase();

                    // ------------------ 自定义升级逻辑 START ------------------
                    // 新安装和升级均支持自定义 SQL 扩展
                    if (customSqlDatabaseUpgradeService != null) {
                        customSqlDatabaseUpgradeService.upgradeDatabase();
                    }
                    // ------------------ 自定义升级逻辑 END ------------------

                    // 创建新表（无数据表自动创建）
                    entityDatabaseSchemaService.createDatabaseSchema(false);

                    // 重建视图、函数
                    entityDatabaseSchemaService.createOrUpdateViewsAndFunctions();
                    entityDatabaseSchemaService.createOrUpdateDeviceInfoView(persistToTelemetry);

                    // 创建缺失索引
                    entityDatabaseSchemaService.createDatabaseIndexes();

                    // TODO: 每次发布后清理旧更新代码
                    systemDataLoaderService.updateDefaultNotificationConfigs(false);

                    // 执行 Java 实现的复杂升级逻辑
                    dataUpdateService.updateData();
                    log.info("Updating system data...");
                    dataUpdateService.upgradeRuleNodes();

                    // 加载系统资源
                    systemDataLoaderService.loadSystemWidgets();
                    installScripts.loadSystemLwm2mResources();
                    installScripts.loadSystemImagesAndResources();

                    // 更新数据库中的 schema_version 为当前包版本
                    databaseSchemaVersionService.updateSchemaVersion();
                }
                log.info("Upgrade finished successfully!");

            } else {  // ------------------ 新安装模式 ------------------
                log.info("Starting ThingsBoard Installation...");

                log.info("Installing DataBase schema for entities...");
                // 创建实体数据库 schema
                entityDatabaseSchemaService.createDatabaseSchema();

                // 初始化 schema_settings 表记录
                databaseSchemaVersionService.createSchemaSettings();

                // 创建/更新视图、函数、设备信息视图
                entityDatabaseSchemaService.createOrUpdateViewsAndFunctions();
                entityDatabaseSchemaService.createOrUpdateDeviceInfoView(persistToTelemetry);

                log.info("Installing DataBase schema for timeseries...");
                // 创建时序数据库 schema（SQL + 可选 NoSQL + Latest）
                if (noSqlKeyspaceService != null) {
                    noSqlKeyspaceService.createDatabaseSchema();
                }
                tsDatabaseSchemaService.createDatabaseSchema();
                if (tsLatestDatabaseSchemaService != null) {
                    tsLatestDatabaseSchemaService.createDatabaseSchema();
                }

                log.info("Loading system data...");
                // 发现组件（Rule Nodes 等）
                componentDiscoveryService.discoverComponents();

                // 加载核心系统数据
                systemDataLoaderService.createSysAdmin();
                systemDataLoaderService.createDefaultTenantProfiles();
                systemDataLoaderService.createAdminSettings();
                systemDataLoaderService.createRandomJwtSettings();
                systemDataLoaderService.loadSystemWidgets();
                systemDataLoaderService.createOAuth2Templates();
                systemDataLoaderService.createQueues();
                systemDataLoaderService.createDefaultNotificationConfigs();

//                systemDataLoaderService.loadSystemPlugins();
//                systemDataLoaderService.loadSystemRules();

                // 加载资源
                installScripts.loadSystemLwm2mResources();
                installScripts.loadSystemImagesAndResources();

                // ------------------ 自定义升级逻辑 START（新安装专用） ------------------
                // 新安装时回放所有历史自定义脚本（关键扩展点）
                if (customSqlDatabaseUpgradeService != null) {
                    customSqlDatabaseUpgradeService.upgradeDatabase();
                }
                // ------------------ 自定义升级逻辑 END ------------------

                // 创建默认菜单设置
                installScripts.createDefaultMenuSettings();

                // 可选加载 demo 数据
                if (loadDemo) {
                    log.info("Loading demo data...");
                    systemDataLoaderService.loadDemoData();
                }
                log.info("Installation finished successfully!");
            }
        } catch (Exception e) {
            // 统一异常处理：记录错误并抛出自定义异常
            log.error("Unexpected error during ThingsBoard installation!", e);
            throw new ThingsboardInstallException("Unexpected error during ThingsBoard installation!", e);
        } finally {
            // 安装/升级完成后强制退出应用（install 模式特性，避免服务持续运行）
            SpringApplication.exit(context);
        }
    }
}
