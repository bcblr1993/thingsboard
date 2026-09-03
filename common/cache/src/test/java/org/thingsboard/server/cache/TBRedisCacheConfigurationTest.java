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
package org.thingsboard.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TBRedisCacheConfigurationTest {

    private static final String KEY = "ts:{DEVICE-test}:data";
    private static final String FIELD = "温度";
    private static final String VALUE = "100|s:正常|25.5";
    private static final byte[] RAW_KEY = KEY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] RAW_FIELD = FIELD.getBytes(StandardCharsets.UTF_8);
    private static final byte[] RAW_VALUE = VALUE.getBytes(StandardCharsets.UTF_8);

    private RedisTemplate<String, String> template;
    private RedisHashCommands hashCommands;
    private RedisScriptingCommands scriptingCommands;

    @BeforeEach
    void setUp() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class, CALLS_REAL_METHODS);
        hashCommands = mock(RedisHashCommands.class);
        scriptingCommands = mock(RedisScriptingCommands.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.hashCommands()).thenReturn(hashCommands);
        when(connection.scriptingCommands()).thenReturn(scriptingCommands);

        TBRedisCacheConfiguration configuration = mock(TBRedisCacheConfiguration.class, CALLS_REAL_METHODS);
        doReturn(factory).when(configuration).redisConnectionFactory();
        template = configuration.redisTemplateString();
        template.afterPropertiesSet();
    }

    @Test
    void shouldReadPlainTextHashValueWrittenByLua() {
        // Mock the Redis wire response, leaving template serialization and deserialization real.
        when(hashCommands.hGet(RAW_KEY, RAW_FIELD)).thenReturn(RAW_VALUE);

        assertThat(template.<String, String>opsForHash().get(KEY, FIELD)).isEqualTo(VALUE);
        verify(hashCommands).hGet(RAW_KEY, RAW_FIELD);
    }

    @Test
    void shouldReadMultipleHashFieldsAndPreserveMissingValues() {
        byte[] missingField = "missing".getBytes(StandardCharsets.UTF_8);
        when(hashCommands.hMGet(RAW_KEY, RAW_FIELD, missingField))
                .thenReturn(Arrays.asList(RAW_VALUE, null));

        assertThat(template.<String, String>opsForHash().multiGet(KEY, List.of(FIELD, "missing")))
                .containsExactly(VALUE, null);
        verify(hashCommands).hMGet(RAW_KEY, RAW_FIELD, missingField);
    }

    @Test
    void shouldEnumeratePlainTextHashFieldsAndValues() {
        when(hashCommands.hKeys(RAW_KEY)).thenReturn(Set.of(RAW_FIELD));
        when(hashCommands.hGetAll(RAW_KEY)).thenReturn(Map.of(RAW_FIELD, RAW_VALUE));

        assertThat(template.<String, String>opsForHash().keys(KEY)).containsExactly(FIELD);
        assertThat(template.<String, String>opsForHash().entries(KEY)).containsExactlyEntriesOf(Map.of(FIELD, VALUE));
    }

    @Test
    void shouldKeepLuaDeletionArgumentsCompatibleWithHashReads() {
        when(hashCommands.hGet(RAW_KEY, RAW_FIELD)).thenReturn(RAW_VALUE);
        DefaultRedisScript<Long> deleteScript = new DefaultRedisScript<>(
                "return redis.call('hdel', KEYS[1], unpack(ARGV))", Long.class);
        when(scriptingCommands.evalSha(deleteScript.getSha1(), ReturnType.INTEGER, 1, RAW_KEY, RAW_FIELD))
                .thenReturn(1L);

        assertThat(template.<String, String>opsForHash().get(KEY, FIELD)).isEqualTo(VALUE);
        assertThat(template.execute(deleteScript, List.of(KEY), FIELD)).isEqualTo(1L);
        verify(scriptingCommands).evalSha(deleteScript.getSha1(), ReturnType.INTEGER, 1, RAW_KEY, RAW_FIELD);
    }

}
