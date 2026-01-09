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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.thingsboard.server.service.install.update.DefaultDataUpdateService;

import java.util.List;

@Service
@Profile("install")  // 仅在 "install" profile 下实例化（安装/升级模式）
@Slf4j
@RequiredArgsConstructor
public class DefaultDatabaseSchemaSettingsService implements DatabaseSchemaSettingsService {

    // 支持升级的白名单版本（官方兼容性限制）
    // 如果非自定义模式且当前 DB 版本不在此列表，会直接报错中断升级
    private static final List<String> SUPPORTED_VERSIONS_FOR_UPGRADE = List.of("4.0.0", "4.0.1", "4.0.2");

    private final ProjectInfo projectInfo;      // 项目信息（包含当前包版本、产品类型等）
    private final JdbcTemplate jdbcTemplate;    // 用于直接执行 SQL 操作

    // 是否启用自定义升级策略（通过配置控制）
    @Value("${install.upgrade.custom_strategy_enabled:false}")
    private boolean customStrategyEnabled;

    // 自定义目标版本（例如 4.1-20251230），优先级高于包内版本
    @Value("${install.upgrade.custom_version:}")
    private String customVersion;

    // 缓存：当前包的目标版本字符串
    private String packageSchemaVersion;
    // 缓存：从 DB 读取的版本字符串（用于日志和显示）
    private String schemaVersionFromDb;

    /**
     * 验证数据库版本兼容性（安装/升级前必调用）
     */
    @Override
    public void validateSchemaSettings() {
        // 支持强制跳过版本检查（用于强制重跑升级）
        if (DefaultDataUpdateService.getEnv("SKIP_SCHEMA_VERSION_CHECK", false)) {
            log.info("Skipped DB schema version check due to SKIP_SCHEMA_VERSION_CHECK set to 'true'.");
            return;
        }

        // 检查产品类型是否匹配（防止用错误的产品包升级数据库）
        String product = getProductFromDb();
        if (!projectInfo.getProductType().equals(product)) {
            onSchemaSettingsError(String.format("Upgrade failed: can't upgrade ThingsBoard %s database using ThingsBoard %s.", product, projectInfo.getProductType()));
        }

        String dbSchemaVersion = getDbSchemaVersion();
        // 已升级到当前版本，拒绝重复升级（避免无意义操作）
        if (dbSchemaVersion.equals(getPackageSchemaVersion())) {
            onSchemaSettingsError("Upgrade failed: database already upgraded to current version. You can set SKIP_SCHEMA_VERSION_CHECK to 'true' if force re-upgrade needed.");
        }

        // 非自定义模式下，检查是否在官方支持的升级版本白名单中
        if (!customStrategyEnabled && !SUPPORTED_VERSIONS_FOR_UPGRADE.contains(dbSchemaVersion)) {
            onSchemaSettingsError(String.format("Upgrade failed: database version '%s' is not supported for upgrade. Supported versions are: %s.",
                    dbSchemaVersion, SUPPORTED_VERSIONS_FOR_UPGRADE
            ));
        } else if (customStrategyEnabled) {
            // 自定义模式下放宽限制，仅记录日志
            log.info("Custom upgrade strategy enabled. Current DB version: {}, Target version: {}", dbSchemaVersion, getPackageSchemaVersion());
        }
    }

    /**
     * 新安装时创建 schema_settings 表记录
     */
    @Override
    public void createSchemaSettings() {
        Long schemaVersion = getSchemaVersionFromDb();
        if (schemaVersion == null) {
            long initialVersion = getPackageSchemaVersionForDb();

            // 自定义模式下，新安装时故意写入“基础版本”（如 4.1.0 的 long 值）
            // 目的是让 CustomSqlDatabaseUpgradeService 检测到版本差异，从而回放所有自定义脚本
            if (customStrategyEnabled) {
                initialVersion = getBasePackageSchemaVersionForDb();
            }

            jdbcTemplate.execute("INSERT INTO tb_schema_settings (schema_version, product) VALUES (" + initialVersion + ", '" + projectInfo.getProductType() + "')");
        }
    }

    /**
     * 获取基础版本的 long 值（去掉日期部分）
     * 例如 "4.1-20251230" → 4.1.0 的 long 值
     */
    private long getBasePackageSchemaVersionForDb() {
        String version = getPackageSchemaVersion();
        String baseVersionStr = version;

        // 优先处理 - 分隔
        if (version.contains("-")) {
            baseVersionStr = version.split("-")[0];
        } else {
            // 兼容 . 分隔的日期
            String[] parts = version.split("\\.");
            if (parts.length > 2 && parts[parts.length - 1].length() == 8) {
                baseVersionStr = version.substring(0, version.lastIndexOf('.'));
            }
        }

        // 标准 long 计算（major * 1000000 + minor * 1000 + patch）
        String[] parts = baseVersionStr.split("\\.");
        long major = Integer.parseInt(parts[0]);
        long minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        long patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

        return major * 1000000 + minor * 1000 + patch;
    }

    /**
     * 升级完成后更新数据库中的 schema_version
     * 核心防护：防止版本回退
     */
    @Override
    public void updateSchemaVersion() {
        Long currentLongVersion = getSchemaVersionFromDb();
        if (currentLongVersion == null) {
            log.warn("Current schema_version is missing in database, skip version update check.");
            // 缺失时直接更新（安全）
            jdbcTemplate.execute("UPDATE tb_schema_settings SET schema_version = " + getPackageSchemaVersionForDb());
            return;
        }

        long newLongVersion = getPackageSchemaVersionForDb();

        // 提取日期部分用于回退判断
        long currentDate = extractDateFromLongVersion(currentLongVersion);
        long newDate = extractDateFromLongVersion(newLongVersion);

        // 防护规则：
        // 1. 新版本无日期（官方版本） → 允许更新
        // 2. 新日期 >= 当前日期 → 允许更新
        // 3. 新日期 < 当前日期 → 拒绝更新 + error 日志（防止回退）
        if (newDate == 0 || newDate >= currentDate) {
            jdbcTemplate.execute("UPDATE tb_schema_settings SET schema_version = " + newLongVersion);
            log.info("Schema version updated from {} (date: {}) to {} (date: {})", currentLongVersion, currentDate, newLongVersion, newDate);
        } else {
            log.error("版本回退检测！目标日期 {} 小于当前数据库日期 {}，拒绝更新 schema_version 以防止数据不一致。当前版本保持 {}。",
                    newDate, currentDate, currentLongVersion);
            // 故意不执行 UPDATE，保持原有版本
        }
    }

    /**
     * 从 long 版本号提取日期部分（仅自定义模式有效）
     */
    private long extractDateFromLongVersion(long version) {
        if (customStrategyEnabled && version > 1000000000L) {
            return version % 100000000L;  // 取低8位日期
        }
        return 0L;  // 官方版本视为无日期（00000000）
    }

    /**
     * 获取目标版本字符串（优先使用配置的 custom_version）
     * 同时提前触发日期有效性校验（防止无效日期进入后续流程）
     */
    @Override
    public String getPackageSchemaVersion() {
        if (packageSchemaVersion == null) {
            if (customStrategyEnabled && StringUtils.isNotEmpty(customVersion)) {
                // 手动解析并校验日期（避免递归调用 ForDb 方法）
                if (customVersion.contains("-")) {
                    String[] hyphenParts = customVersion.split("-");
                    if (hyphenParts.length == 2) {
                        String datePart = hyphenParts[1];
                        if (datePart.length() == 8 && StringUtils.isNumeric(datePart)) {
                            long date = Long.parseLong(datePart);
                            if (!isValidDate(date)) {
                                throw new RuntimeException(String.format("无效自定义版本日期: %s (格式 YYYYMMDD，必须真实有效，如月份01-12、日01-31)", datePart));
                            }
                        }
                    }
                }
                packageSchemaVersion = customVersion;
            } else {
                packageSchemaVersion = projectInfo.getProjectVersion();
            }
        }
        return packageSchemaVersion;
    }

    /**
     * 获取 DB 中的版本字符串（用于显示和日志）
     */
    @Override
    public String getDbSchemaVersion() {
        if (schemaVersionFromDb == null) {
            Long version = getSchemaVersionFromDb();
            if (version == null) {
                onSchemaSettingsError("Upgrade failed: the database schema version is missing.");
            }

            if (customStrategyEnabled && version > 1000000000L) {
                long major = version / 100000000000L;
                long remaining = version % 100000000000L;
                long minor = remaining / 100000000L;
                long date = remaining % 100000000L;
                schemaVersionFromDb = major + "." + minor + "-" + date; // 统一显示格式
            } else {
                long major = version / 1000000;
                long minor = (version % 1000000) / 1000;
                long patch = version % 1000;
                schemaVersionFromDb = major + "." + minor + "." + patch;
            }
        }
        return schemaVersionFromDb;
    }

    private Long getSchemaVersionFromDb() {
        return jdbcTemplate.queryForList("SELECT schema_version FROM tb_schema_settings", Long.class).stream().findFirst().orElse(null);
    }

    private String getProductFromDb() {
        return jdbcTemplate.queryForList("SELECT product FROM tb_schema_settings", String.class).stream().findFirst().orElse(null);
    }

    /**
     * 将版本字符串转换为 long 值（用于 DB 存储）
     * 包含日期有效性校验（无效日期直接中断安装）
     */
    private long getPackageSchemaVersionForDb() {
        String version = getPackageSchemaVersion();

        if (customStrategyEnabled && version.contains("-")) {
            String[] hyphenParts = version.split("-");
            if (hyphenParts.length == 2) {
                String baseVersion = hyphenParts[0];
                String datePart = hyphenParts[1];

                if (datePart.length() == 8 && StringUtils.isNumeric(datePart)) {
                    long date = Long.parseLong(datePart);

                    // 关键防护：日期必须真实有效
                    if (!isValidDate(date)) {
                        throw new RuntimeException(String.format("无效自定义版本日期: %s (格式 YYYYMMDD，必须真实有效，如月份01-12、日01-31)", datePart));
                    }

                    String[] baseParts = baseVersion.split("\\.");
                    long major = Integer.parseInt(baseParts[0]);
                    long minor = baseParts.length > 1 ? Integer.parseInt(baseParts[1]) : 0;

                    // 自定义 long 公式：major * 10^11 + minor * 10^8 + date
                    return major * 100000000000L + minor * 100000000L + date;
                }
            }
        }

        // 官方标准版本 fallback
        String[] versionParts = version.split("\\.");
        long major = Integer.parseInt(versionParts[0]);
        long minor = Integer.parseInt(versionParts[1]);
        long patch = versionParts.length > 2 ? Integer.parseInt(versionParts[2]) : 0;
        return major * 1000000 + minor * 1000 + patch;
    }

    /**
     * 校验 YYYYMMDD 是否为真实有效日期
     */
    private boolean isValidDate(long yyyymmdd) {
        if (yyyymmdd < 19000101 || yyyymmdd > 21001231) return false;

        int year = (int) (yyyymmdd / 10000);
        int month = (int) ((yyyymmdd % 10000) / 100);
        int day = (int) (yyyymmdd % 100);

        if (month < 1 || month > 12) return false;
        if (day < 1 || day > 31) return false;

        int[] daysInMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (month == 2 && (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0))) {
            daysInMonth[2] = 29;  // 闰年2月29天
        }

        return day <= daysInMonth[month];
    }

    /**
     * 统一错误处理：记录日志并抛异常中断安装
     */
    private void onSchemaSettingsError(String message) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> log.error(message)));
        throw new RuntimeException(message);
    }
}
