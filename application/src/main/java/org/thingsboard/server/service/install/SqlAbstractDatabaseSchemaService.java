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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Slf4j
public abstract class SqlAbstractDatabaseSchemaService implements DatabaseSchemaService {

    protected static final String SQL_DIR = "sql";

    @Value("${spring.datasource.url}")
    protected String dbUrl;

    @Value("${spring.datasource.username}")
    protected String dbUserName;

    @Value("${spring.datasource.password}")
    protected String dbPassword;

    @Autowired
    protected InstallScripts installScripts;

    private final String schemaSql;
    private final String schemaIdxSql;

    protected SqlAbstractDatabaseSchemaService(String schemaSql, String schemaIdxSql) {
        this.schemaSql = schemaSql;
        this.schemaIdxSql = schemaIdxSql;
    }

    @Override
    public void createDatabaseSchema() throws Exception {
        this.createDatabaseSchema(true);
    }

    @Override
    public void createDatabaseSchema(boolean createIndexes) throws Exception {
        log.info("Installing SQL DataBase schema part: " + schemaSql);
        executeQueryFromFile(schemaSql);

        if (createIndexes) {
            this.createDatabaseIndexes();
        }
    }

    @Override
    public void createDatabaseIndexes() throws Exception {
        if (schemaIdxSql != null) {
            log.info("Installing SQL DataBase schema indexes part: " + schemaIdxSql);
            executeQueryFromFile(schemaIdxSql);
        }
    }

    void executeQueryFromFile(String schemaIdxSql) throws SQLException, IOException {
        Path schemaIdxFile = installScripts.resolveDataFile(SQL_DIR, schemaIdxSql);
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new FileSystemResource(schemaIdxFile), StandardCharsets.UTF_8),
                    false, false, ScriptUtils.DEFAULT_COMMENT_PREFIXES, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
    }

    protected void executeQuery(String query) {
        executeQuery(query, null);
    }

    protected void executeQuery(String query, String logQuery) {
        logQuery = logQuery != null ? logQuery : query;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
            conn.createStatement().execute(query); //NOSONAR, ignoring because method used to execute thingsboard database upgrade script
            log.info("Successfully executed query: {}", logQuery);
            Thread.sleep(5000);
        } catch (InterruptedException | SQLException e) {
            throw new RuntimeException("Failed to execute query: " + logQuery, e);
        }
    }

    protected void executeQuery(String query, long parameter, String logQuery) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword);
             PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setLong(1, parameter);
            statement.execute();
            log.info("Successfully executed query: {}", logQuery);
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while executing query: " + logQuery, e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute query: " + logQuery, e);
        }
    }

}
