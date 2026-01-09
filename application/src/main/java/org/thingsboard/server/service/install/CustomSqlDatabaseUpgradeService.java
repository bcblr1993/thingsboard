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
import org.springframework.jdbc.core.StatementCallback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLWarning;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Profile("install")  // 仅在 "install" profile 下实例化（安装/升级专用模式）
@Slf4j
@RequiredArgsConstructor
public class CustomSqlDatabaseUpgradeService {

    // 自定义升级脚本存放目录（相对于 dataDir）
    private static final String CUSTOM_UPGRADE_DIR = "custom";
    // 自定义脚本文件名前缀
    private static final String UPGRADE_FILE_PREFIX = "upgrade_";
    // 自定义脚本文件后缀
    private static final String UPGRADE_FILE_SUFFIX = ".sql";

    // 用于获取数据目录路径
    private final InstallScripts installScripts;
    // 执行 SQL 脚本
    private final JdbcTemplate jdbcTemplate;
    // 获取当前/目标版本
    private final DatabaseSchemaSettingsService schemaSettingsService;

    // 是否启用自定义升级策略（通过配置控制）
    @Value("${install.upgrade.custom_strategy_enabled:false}")
    private boolean customStrategyEnabled;

    /**
     * 核心方法：执行自定义升级脚本
     * 在新安装和旧版升级两种场景下都会被 ThingsboardInstallService 调用
     */
    public void upgradeDatabase() {
        // 如果未启用自定义策略，直接跳过
        if (!customStrategyEnabled) {
            log.debug("Custom upgrade strategy is disabled. Skipping custom scripts.");
            return;
        }

        // 获取当前数据库版本和目标版本（字符串形式）
        String currentVersion = schemaSettingsService.getDbSchemaVersion();
        String targetVersion = schemaSettingsService.getPackageSchemaVersion();

        log.info("Starting custom upgrade from version {} to {} ...", currentVersion, targetVersion);

        // 从版本字符串中提取日期部分（YYYYMMDD），用于增量判断
        long currentVersionDate = extractDateFromVersion(currentVersion);
        long targetVersionDate = extractDateFromVersion(targetVersion);

        // 构建自定义脚本目录路径：data/upgrade/custom/
        Path customUpgradeDir = Paths.get(installScripts.getDataDir(), "upgrade", CUSTOM_UPGRADE_DIR);
        if (!Files.exists(customUpgradeDir)) {
            log.info("No custom upgrade directory found at {}. Skipping custom scripts.", customUpgradeDir);
            return;
        }

        try (Stream<Path> files = Files.list(customUpgradeDir)) {
            // 筛选出符合命名规范的脚本文件，并按文件名中的日期排序
            List<Path> upgradeScripts = files
                    .filter(path -> path.getFileName().toString().startsWith(UPGRADE_FILE_PREFIX)
                            && path.getFileName().toString().endsWith(UPGRADE_FILE_SUFFIX))
                    .sorted(Comparator.comparing(this::extractDateFromFileName))
                    .toList();

            boolean scriptsExecuted = false;
            // 遍历所有脚本，只执行日期满足条件的（> 当前日期 且 <= 目标日期）
            for (Path script : upgradeScripts) {
                long scriptDate = extractDateFromFileName(script);
                if (scriptDate > currentVersionDate && scriptDate <= targetVersionDate) {
                    log.info("Executing custom upgrade script: {}", script.getFileName());
                    loadSql(script);  // 执行单个脚本文件
                    scriptsExecuted = true;
                }
            }

            if (!scriptsExecuted) {
                log.info("No custom upgrade scripts needed for this version range.");
            } else {
                log.info("Custom upgrade scripts executed successfully.");
            }

        } catch (IOException e) {
            log.error("Failed to list custom upgrade scripts", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 从版本字符串中提取日期部分（优先 - 分隔，其次 . 分隔）
     * 示例：
     *   "4.1-20251230" → 20251230
     *   "4.1.20251230" → 20251230
     *   "4.1" → 0
     */
    private long extractDateFromVersion(String version) {
        try {
            // 优先处理 - 分隔（推荐格式）
            if (version.contains("-")) {
                String[] parts = version.split("-");
                if (parts.length == 2 && parts[1].length() == 8) {
                    return Long.parseLong(parts[1]);
                }
            }

            // 兼容 . 分隔的日期
            String[] parts = version.split("\\.");
            if (parts.length >= 3) {
                String lastPart = parts[parts.length - 1];
                if (lastPart.length() == 8) {
                    return Long.parseLong(lastPart);
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse date from version string: {}", version);
        }
        return 0;  // 解析失败或官方版本 fallback 为 0
    }

    /**
     * 从脚本文件名中提取日期部分
     * 支持格式：upgrade_4.1-20251230.sql 或 upgrade_4.1.20251230.sql
     */
    private long extractDateFromFileName(Path path) {
        String filename = path.getFileName().toString();
        try {
            if (!filename.startsWith(UPGRADE_FILE_PREFIX) || !filename.endsWith(UPGRADE_FILE_SUFFIX)) {
                return 0;
            }
            String versionPart = filename.substring(UPGRADE_FILE_PREFIX.length(), filename.length() - UPGRADE_FILE_SUFFIX.length());
            return extractDateFromVersion(versionPart);
        } catch (Exception e) {
            log.warn("Failed to extract date from script filename: {}", filename);
            return 0;
        }
    }

    /**
     * 读取并执行单个 SQL 脚本文件
     * 当前实现：整文件执行（与官方升级服务一致）
     * 注意：脚本应为幂等设计（推荐使用 ON CONFLICT DO NOTHING 等）
     */
    private void loadSql(Path sqlFile) {
        String sql;
        try {
            sql = Files.readString(sqlFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (StringUtils.isBlank(sql)) {
            return;
        }

        // 直接执行整个文件内容
        jdbcTemplate.execute((StatementCallback<Object>) stmt -> {
            stmt.execute(sql);
            printWarnings(stmt.getWarnings());
            return null;
        });
    }

    /**
     * 打印 SQL 执行过程中的警告信息（PostgreSQL 特有）
     */
    private void printWarnings(SQLWarning warnings) {
        if (warnings != null) {
            log.info("{}", warnings.getMessage());
            SQLWarning nextWarning = warnings.getNextWarning();
            while (nextWarning != null) {
                log.info("{}", nextWarning.getMessage());
                nextWarning = nextWarning.getNextWarning();
            }
        }
    }
}
