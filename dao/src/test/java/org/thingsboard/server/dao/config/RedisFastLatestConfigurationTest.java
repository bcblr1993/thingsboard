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
package org.thingsboard.server.dao.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.cache.RedisSslCredentials;
import org.thingsboard.server.cache.TBRedisCacheConfiguration;
import org.thingsboard.server.cache.TBRedisStandaloneConfiguration;
import org.thingsboard.server.dao.cache.CacheExecutorService;
import org.thingsboard.server.dao.sqlts.AggregationTimeseriesDao;
import org.thingsboard.server.dao.timeseries.RedisClusterTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.RedisTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.fast.RedisFastTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.fast.ValkeyFastTimeseriesLatestDao;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisFastLatestConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("cache.type=redis", "cache.maximumPoolSize=8",
                    "test.redis-fast-latest.mock-enabled=true")
            .withUserConfiguration(MockRedisConfiguration.class, RedisTimeseriesLatestDao.class,
                    RedisClusterTimeseriesLatestDao.class, RedisFastTimeseriesLatestDao.class,
                    ValkeyFastTimeseriesLatestDao.class);

    @ParameterizedTest
    @ValueSource(strings = {"", "false"})
    void shouldNotLoadMockRedisConfigurationFromComponentScanUnlessExplicitlyEnabled(String mockEnabled) {
        ApplicationContextRunner scanningContextRunner = new ApplicationContextRunner()
                .withPropertyValues("cache.type=redis", "cache.maximumPoolSize=8", "redis.connection.type=standalone")
                .withBean("testRedisSslCredentials", RedisSslCredentials.class, RedisSslCredentials::new,
                        definition -> definition.setPrimary(true))
                .withUserConfiguration(TBRedisStandaloneConfiguration.class)
                // Use ordinary Spring scanning, without Spring Boot's test-component exclusion filters.
                .withInitializer(context -> {
                    ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(
                            (BeanDefinitionRegistry) context.getBeanFactory(), true, context.getEnvironment());
                    // Limit this regression to the test fixture, avoiding unrelated database configurations.
                    scanner.setResourcePattern("RedisFastLatestConfigurationTest$*.class");
                    scanner.scan("org.thingsboard.server.dao.config");
                });
        if (!mockEnabled.isEmpty()) {
            scanningContextRunner = scanningContextRunner.withPropertyValues(
                    "test.redis-fast-latest.mock-enabled=" + mockEnabled);
        }
        scanningContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TBRedisCacheConfiguration.class))
                    .isInstanceOf(TBRedisStandaloneConfiguration.class);
            assertThat(context).doesNotHaveBean(MockRedisConfiguration.class);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "redis-fast, org.thingsboard.server.dao.timeseries.fast.RedisFastTimeseriesLatestDao",
            "redis, org.thingsboard.server.dao.timeseries.RedisTimeseriesLatestDao",
            "redis-cluster, org.thingsboard.server.dao.timeseries.RedisClusterTimeseriesLatestDao",
            "valkey-fast, org.thingsboard.server.dao.timeseries.fast.ValkeyFastTimeseriesLatestDao"
    })
    void shouldSelectExactlyOneLatestDao(String backend, Class<? extends TimeseriesLatestDao> expectedType) {
        contextRunner.withPropertyValues("database.ts_latest.type=" + backend).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(TimeseriesLatestDao.class)).hasSize(1);
            assertThat(context.getBean(TimeseriesLatestDao.class)).isExactlyInstanceOf(expectedType);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sql", "timescale", "cassandra", "iotdb", "unsupported"})
    void shouldNotSelectRedisDaoForOtherBackends(String backend) {
        contextRunner.withPropertyValues("database.ts_latest.type=" + backend).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TimeseriesLatestDao.class);
        });
    }

    @Test
    void shouldNotSelectRedisDaoWhenLatestBackendIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TimeseriesLatestDao.class);
        });
    }

    @Test
    void shouldResolveDatabaseTsLatestTypeEnvironmentVariable() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("redis-fast-test-environment",
                        Map.of("DATABASE_TS_LATEST_TYPE", "redis-fast"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TimeseriesLatestDao.class)).hasSize(1);
                    assertThat(context.getBean(TimeseriesLatestDao.class))
                            .isExactlyInstanceOf(RedisFastTimeseriesLatestDao.class);
                });
    }

    @Test
    void shouldInjectStringTemplateEvenWhenGenericTemplateMatchesFieldName() {
        contextRunner.withPropertyValues("database.ts_latest.type=redis-fast").run(context -> {
            assertThat(context).hasNotFailed();
            RedisFastTimeseriesLatestDao dao = context.getBean(RedisFastTimeseriesLatestDao.class);
            RedisTemplate<?, ?> stringTemplate = context.getBean("redisTemplateString", RedisTemplate.class);
            RedisTemplate<?, ?> genericTemplate = context.getBean("redisTemplate", RedisTemplate.class);

            assertThat(context.getBeansOfType(RedisTemplate.class)).hasSize(2);
            assertThat(ReflectionTestUtils.getField(dao, "redisTemplate"))
                    .isSameAs(stringTemplate)
                    .isNotSameAs(genericTemplate);
            assertThat(stringTemplate.getConnectionFactory()).isSameAs(genericTemplate.getConnectionFactory());
        });
    }

    @Test
    void shouldUseTheSameUtf8EncodingForLuaAndHashOperations() {
        contextRunner.withPropertyValues("database.ts_latest.type=redis-fast").run(context -> {
            assertThat(context).hasNotFailed();
            RedisTemplate<?, ?> template = context.getBean("redisTemplateString", RedisTemplate.class);
            assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getHashValueSerializer()).isInstanceOf(StringRedisSerializer.class);

            String key = "ts:{DEVICE00000000-0000-0000-0000-000000000001}:data";
            String field = "温度:测点|一";
            String value = "1750000000000|s:正常|25.5°C";
            StringRedisSerializer keySerializer = (StringRedisSerializer) template.getKeySerializer();
            StringRedisSerializer valueSerializer = (StringRedisSerializer) template.getValueSerializer();
            StringRedisSerializer hashKeySerializer = (StringRedisSerializer) template.getHashKeySerializer();
            StringRedisSerializer hashValueSerializer = (StringRedisSerializer) template.getHashValueSerializer();

            assertThat(keySerializer.serialize(key)).isEqualTo(key.getBytes(StandardCharsets.UTF_8));
            // Lua arguments use the value serializer, while HGET/HMGET use the hash serializers.
            assertThat(valueSerializer.serialize(field)).isEqualTo(hashKeySerializer.serialize(field));
            assertThat(hashKeySerializer.serialize(field)).isEqualTo(field.getBytes(StandardCharsets.UTF_8));
            assertThat(valueSerializer.serialize(value)).isEqualTo(hashValueSerializer.serialize(value));
            assertThat(hashValueSerializer.serialize(value)).isEqualTo(value.getBytes(StandardCharsets.UTF_8));
            assertThat(hashValueSerializer.deserialize(valueSerializer.serialize(value))).isEqualTo(value);
        });
    }

    @TestConfiguration
    @ConditionalOnProperty(name = "test.redis-fast-latest.mock-enabled", havingValue = "true", matchIfMissing = false)
    static class MockRedisConfiguration extends TBRedisCacheConfiguration {

        @Override
        protected JedisConnectionFactory loadFactory() {
            return mock(JedisConnectionFactory.class);
        }

        @Bean
        static RedisSslCredentials redisSslCredentials() {
            return new RedisSslCredentials();
        }

        @Bean
        CacheExecutorService cacheExecutorService() {
            return mock(CacheExecutorService.class);
        }

        @Bean
        AggregationTimeseriesDao aggregationTimeseriesDao() {
            return mock(AggregationTimeseriesDao.class);
        }
    }
}
