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
package org.thingsboard.server.dao.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDbTypeInfoComponentTest {

    private DefaultDbTypeInfoComponent component;

    @BeforeEach
    void setUp() {
        component = new DefaultDbTypeInfoComponent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sql", "SQL", "Sql"})
    void isLatestTsDaoStoredToSql_shouldReturnTrue_forSql(String type) {
        ReflectionTestUtils.setField(component, "latestTsDbType", type);
        assertThat(component.isLatestTsDaoStoredToSql()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timescale", "TIMESCALE", "Timescale"})
    void isLatestTsDaoStoredToSql_shouldReturnTrue_forTimescale(String type) {
        ReflectionTestUtils.setField(component, "latestTsDbType", type);
        assertThat(component.isLatestTsDaoStoredToSql()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "redis-cluster", "cassandra"})
    void isLatestTsDaoStoredToSql_shouldReturnFalse_forNonSqlBackends(String type) {
        ReflectionTestUtils.setField(component, "latestTsDbType", type);
        assertThat(component.isLatestTsDaoStoredToSql()).isFalse();
    }

    @Test
    void isLatestTsDaoStoredToSql_shouldReturnFalse_forUnknownType() {
        ReflectionTestUtils.setField(component, "latestTsDbType", "unknown");
        assertThat(component.isLatestTsDaoStoredToSql()).isFalse();
    }

}
