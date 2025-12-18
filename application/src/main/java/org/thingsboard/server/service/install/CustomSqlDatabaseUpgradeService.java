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
@Profile("install")
@Slf4j
@RequiredArgsConstructor
public class CustomSqlDatabaseUpgradeService implements DatabaseEntitiesUpgradeService {

    private static final String CUSTOM_UPGRADE_DIR = "custom";
    private static final String UPGRADE_FILE_PREFIX = "upgrade_";
    private static final String UPGRADE_FILE_SUFFIX = ".sql";

    private final InstallScripts installScripts;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSchemaSettingsService schemaSettingsService;

    @Value("${install.upgrade.custom_strategy_enabled:false}")
    private boolean customStrategyEnabled;

    @Override
    public void upgradeDatabase() {
        if (!customStrategyEnabled) {
            log.debug("Custom upgrade strategy is disabled. Skipping custom scripts.");
            return;
        }

        String currentVersion = schemaSettingsService.getDbSchemaVersion();
        String targetVersion = schemaSettingsService.getPackageSchemaVersion();

        log.info("Starting custom upgrade from version {} to {} ...", currentVersion, targetVersion);

        long currentVersionDate = extractDateFromVersion(currentVersion);
        long targetVersionDate = extractDateFromVersion(targetVersion);

        Path customUpgradeDir = Paths.get(installScripts.getDataDir(), "upgrade", CUSTOM_UPGRADE_DIR);
        if (!Files.exists(customUpgradeDir)) {
            log.info("No custom upgrade directory found at {}. Skipping custom scripts.", customUpgradeDir);
            return;
        }

        try (Stream<Path> files = Files.list(customUpgradeDir)) {
            List<Path> upgradeScripts = files
                    .filter(path -> path.getFileName().toString().startsWith(UPGRADE_FILE_PREFIX) 
                                 && path.getFileName().toString().endsWith(UPGRADE_FILE_SUFFIX))
                    .sorted(Comparator.comparing(this::extractDateFromFileName))
                    .collect(Collectors.toList());

            boolean scriptsExecuted = false;
            for (Path script : upgradeScripts) {
                long scriptDate = extractDateFromFileName(script);
                if (scriptDate > currentVersionDate && scriptDate <= targetVersionDate) {
                    log.info("Executing custom upgrade script: {}", script.getFileName());
                    loadSql(script);
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

    private long extractDateFromVersion(String version) {
        try {
            // Priority: Check for hyphen separated date (e.g. 4.1-20250930)
            if (version.contains("-")) {
                String[] parts = version.split("-");
                if (parts.length == 2 && parts[1].length() == 8) {
                    return Long.parseLong(parts[1]);
                }
            }
            
            // Fallback: Check for dot separated date (e.g. 4.1.20250930)
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
        return 0;
    }

    private long extractDateFromFileName(Path path) {
        String filename = path.getFileName().toString();
        // Expected Format: upgrade_<VERSION>.sql
        // e.g. upgrade_4.1-20251230.sql OR upgrade_4.1.20251230.sql
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

    private void loadSql(Path sqlFile) {
        String sql;
        try {
            sql = Files.readString(sqlFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Basic naive splitter, similar to standard upgrade service or safer execution
        // Standard SqlDatabaseUpgradeService loads the whole file. We will do the same.
        if (StringUtils.isBlank(sql)) {
            return;
        }
        
        jdbcTemplate.execute((StatementCallback<Object>) stmt -> {
            stmt.execute(sql);
            printWarnings(stmt.getWarnings());
            return null;
        });
    }

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
