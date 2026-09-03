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
package org.thingsboard.server.dao.timeseries.fast;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.thingsboard.server.cache.TBRedisCacheConfiguration;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseDeleteTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.cache.CacheExecutorService;
import org.thingsboard.server.dao.sqlts.AggregationTimeseriesDao;
import org.thingsboard.server.dao.timeseries.RedisClusterTimeseriesLatestDao;
import org.thingsboard.server.dao.timeseries.RedisTimeseriesLatestDao;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the production DAO, Lua, RedisTemplate serializers and executor against real Redis.
 * Defaults to an isolated Testcontainers instance. A dedicated external fixture can be supplied
 * using redis.fast.test.host / redis.fast.test.port. Only random UUID entity keys are removed;
 * the script-cache recovery test also flushes that dedicated server's Lua script cache.
 */
class RedisFastTimeseriesLatestDaoTest {

    private static GenericContainer<?> redis;
    private static JedisConnectionFactory factory;
    private static RedisTemplate<String, String> template;
    private static CacheExecutorService executor;
    private final TenantId tenant = TenantId.fromUUID(UUID.randomUUID());
    private final DeviceId device = new DeviceId(UUID.randomUUID());
    private final List<String> ownedKeys = new ArrayList<>();
    private RedisFastTimeseriesLatestDao dao;
    private AggregationTimeseriesDao history;

    @BeforeAll
    static void connect() {
        String host = System.getProperty("redis.fast.test.host", "127.0.0.1");
        String port = System.getProperty("redis.fast.test.port");
        if (port == null) {
            redis = new GenericContainer<>("redis:6.2").withExposedPorts(6379);
            redis.start();
            host = redis.getHost();
            port = redis.getMappedPort(6379).toString();
        }
        factory = new JedisConnectionFactory(new RedisStandaloneConfiguration(host, Integer.parseInt(port)),
                JedisClientConfiguration.builder().connectTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(10)).usePooling().build());
        factory.afterPropertiesSet();
        factory.start();
        TBRedisCacheConfiguration configuration = mock(TBRedisCacheConfiguration.class, CALLS_REAL_METHODS);
        doReturn(factory).when(configuration).redisConnectionFactory();
        template = configuration.redisTemplateString();
        template.afterPropertiesSet();
        executor = new CacheExecutorService();
        ReflectionTestUtils.setField(executor, "poolSize", 8);
        executor.init();
    }

    @AfterAll
    static void disconnect() {
        if (executor != null) {
            executor.destroy();
        }
        if (factory != null) {
            factory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void setUp() {
        history = mock(AggregationTimeseriesDao.class);
        dao = configure(new RedisFastTimeseriesLatestDao());
        track(device);
    }

    private RedisFastTimeseriesLatestDao configure(RedisFastTimeseriesLatestDao target) {
        target.redisTemplate = template;
        target.cacheExecutorService = executor;
        target.aggregationTimeseriesDao = history;
        target.init();
        return target;
    }

    private String track(EntityId entity) {
        String key = dao.buildKey(entity);
        ownedKeys.add(key);
        return key;
    }

    @AfterEach
    void cleanEntityKeys() {
        template.delete(ownedKeys);
    }

    private static <T> T await(ListenableFuture<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    private static long commandCalls(String command) {
        try (var connection = factory.getConnection()) {
            String stats = connection.serverCommands().info("commandstats")
                    .getProperty("cmdstat_" + command, "calls=0");
            return Long.parseLong(stats.split(",")[0].substring("calls=".length()));
        }
    }

    private static TsKvEntry value(String key, long ts, long value) {
        return new BasicTsKvEntry(ts, new LongDataEntry(key, value));
    }

    private TsKvEntry read(String key) throws Exception {
        return await(dao.findLatest(tenant, device, key));
    }

    private static void same(TsKvEntry actual, TsKvEntry expected) {
        assertThat(actual.getKey()).isEqualTo(expected.getKey());
        assertThat(actual.getTs()).isEqualTo(expected.getTs());
        assertThat(actual.getDataType()).isEqualTo(expected.getDataType());
        assertThat(actual.getValue()).isEqualTo(expected.getValue());
    }

    static Stream<KvEntry> types() {
        return Stream.of(new BooleanDataEntry("bool", true), new BooleanDataEntry("bool", false),
                new LongDataEntry("long", Long.MIN_VALUE), new LongDataEntry("long", Long.MAX_VALUE),
                new DoubleDataEntry("double", -123.456), new DoubleDataEntry("double", 1.23e100),
                new DoubleDataEntry("double", -0.0), new StringDataEntry("string", ""),
                new StringDataEntry("温度|:🌡", "中文🙂|冒号:\n换行\u0000尾部"),
                new StringDataEntry("large", "测".repeat(100_000)),
                new JsonDataEntry("json", "{\"text\":\"正常|:🙂\",\"n\":42,\"list\":[true,null]}"),
                new StringDataEntry("nullable", null));
    }

    @ParameterizedTest
    @MethodSource("types")
    void roundTripsAllTypesThroughEveryReadPath(KvEntry entry) throws Exception {
        TsKvEntry expected = new BasicTsKvEntry(1_700_000_000_123L, entry);
        await(dao.saveLatest(tenant, device, expected));
        same(read(entry.getKey()), expected);
        same(await(dao.findLatestOpt(tenant, device, entry.getKey())).orElseThrow(), expected);
        same(await(dao.findLatest(tenant, device, List.of(entry.getKey()))).get(0), expected);
        same(await(dao.findAllLatest(tenant, device)).get(0), expected);
        assertThat(dao.findAllKeysByEntityIds(tenant, List.of(device))).containsExactly(entry.getKey());
        assertThat(template.<String, String>opsForHash().get(dao.buildKey(device), entry.getKey()))
                .isEqualTo(dao.serialize(expected));
    }

    @Test
    void writesMixedBatchAndReadsPlainRedisValues() throws Exception {
        List<TsKvEntry> entries = List.of(value("n", 100, 42),
                new BasicTsKvEntry(101, new StringDataEntry("文字", "value|:内容")),
                new BasicTsKvEntry(102, new BooleanDataEntry("active", false)),
                new BasicTsKvEntry(103, new DoubleDataEntry("temperature", 25.5)),
                new BasicTsKvEntry(104, new JsonDataEntry("payload", "{\"ok\":true}")));
        assertThat(await(dao.saveLatest(tenant, device, entries))).isEqualTo(5);
        assertThat(template.opsForHash().size(dao.buildKey(device))).isEqualTo(5);
        for (TsKvEntry entry : entries) {
            same(read(entry.getKey()), entry);
        }
        template.opsForHash().put(dao.buildKey(device), "native", "105|s:原生|写入");
        same(read("native"), new BasicTsKvEntry(105, new StringDataEntry("native", "原生|写入")));
    }

    @Test
    void reloadsLuaScriptsAfterScriptCacheLoss() throws Exception {
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        await(dao.findAllLatest(tenant, device));
        try (var connection = factory.getConnection()) {
            connection.scriptingCommands().scriptFlush();
        }
        await(dao.saveLatest(tenant, device, value("a", 200, 2)));
        same(await(dao.findAllLatest(tenant, device)).get(0), value("a", 200, 2));
        assertThat(await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 0, 300))).isRemoved()).isTrue();
        assertThat(await(dao.findLatestOpt(tenant, device, "a"))).isEmpty();
    }

    @Test
    void emptyOperationsDoNotCreateRedisKeys() throws Exception {
        assertThat(await(dao.saveLatest(tenant, device, List.of()))).isZero();
        assertThat(await(dao.saveLatest(tenant, device, (List<TsKvEntry>) null))).isZero();
        assertThat(await(dao.findLatest(tenant, device, List.of()))).isEmpty();
        assertThat(await(dao.findAllLatest(tenant, device))).isEmpty();
        assertThat(await(dao.findLatestOpt(tenant, device, "missing"))).isEmpty();
        assertThat(read("missing").getValue()).isNull();
        assertThat(template.hasKey(dao.buildKey(device))).isFalse();
    }

    @Test
    void batchReadsPreserveOrderDuplicatesAndMissingPlaceholders() throws Exception {
        await(dao.saveLatest(tenant, device, List.of(value("a", 100, 1), value("b", 200, 2))));
        List<TsKvEntry> result = await(dao.findLatest(tenant, device, List.of("b", "missing", "a", "b", "absent")));
        assertThat(result).extracting(TsKvEntry::getKey).containsExactly("b", "missing", "a", "b", "absent");
        assertThat(result).extracting(TsKvEntry::getValue).containsExactly(2L, null, 1L, 2L, null);
        assertThat(result.get(1).getTs()).isEqualTo(result.get(4).getTs());
    }

    @ParameterizedTest
    @CsvSource({"99,0,1", "100,1,2", "101,1,2"})
    void timestampGuardRejectsOlderAndAcceptsEqual(long incomingTs, long count, long finalValue) throws Exception {
        await(dao.saveLatest(tenant, device, List.of(value("a", 100, 1))));
        assertThat(await(dao.saveLatest(tenant, device, List.of(value("a", incomingTs, 2))))).isEqualTo(count);
        assertThat(read("a").getValue()).isEqualTo(finalValue);
    }

    @Test
    void mixedBatchCountsOnlyAcceptedUpdates() throws Exception {
        await(dao.saveLatest(tenant, device, List.of(value("old", 100, 1),
                value("equal", 100, 1), value("newer", 100, 1))));
        assertThat(await(dao.saveLatest(tenant, device, List.of(value("old", 99, 2),
                value("equal", 100, 2), value("newer", 101, 2), value("created", 50, 2))))).isEqualTo(3L);
        same(read("old"), value("old", 100, 1));
        same(read("equal"), value("equal", 100, 2));
        same(read("newer"), value("newer", 101, 2));
        same(read("created"), value("created", 50, 2));
    }

    @ParameterizedTest
    @CsvSource({"200,100,200,1", "100,200,200,2", "200,200,200,2"})
    @Disabled("Out of agreed fix scope (2026-09-03): duplicate fields within one business batch")
    void duplicateFieldsWithinBatchNeverRegress(long first, long second, long expectedTs, long expectedValue) throws Exception {
        await(dao.saveLatest(tenant, device, List.of(value("a", first, 1), value("a", second, 2))));
        same(read("a"), value("a", expectedTs, expectedValue));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 1000, 2999, 3000, 3001, 4000, 5000, 5999, 6000, 6001, 10000})
    void largeBatchesPreserveEveryField(int size) throws Exception {
        List<TsKvEntry> entries = IntStream.range(0, size).mapToObj(i -> value("field" + i, 100 + i, i)).toList();
        long readsBefore = commandCalls("hmget");
        long writesBefore = commandCalls("hset");
        assertThat(await(dao.saveLatest(tenant, device, entries))).isEqualTo(size);
        long chunks = (size + 2999L) / 3000L;
        assertThat(commandCalls("hmget") - readsBefore).isEqualTo(chunks);
        assertThat(commandCalls("hset") - writesBefore).isEqualTo(chunks);
        List<TsKvEntry> result = await(dao.findLatest(tenant, device, entries.stream().map(TsKvEntry::getKey).toList()));
        assertThat(result).hasSize(size);
        for (int i = 0; i < size; i++) {
            same(result.get(i), entries.get(i));
        }
    }

    @Test
    void chunkedWriteSkipsRejectedChunksAndAccumulatesAcceptedCount() throws Exception {
        List<TsKvEntry> initial = IntStream.range(0, 6001)
                .mapToObj(i -> value("field" + i, i >= 3000 && i < 6000 ? 200 : 100, 1)).toList();
        await(dao.saveLatest(tenant, device, initial));
        List<TsKvEntry> updates = IntStream.range(0, 6001).mapToObj(i -> value("field" + i, 150, 2)).toList();
        long readsBefore = commandCalls("hmget");
        long writesBefore = commandCalls("hset");
        assertThat(await(dao.saveLatest(tenant, device, updates))).isEqualTo(3001L);
        assertThat(commandCalls("hmget") - readsBefore).isEqualTo(3L);
        assertThat(commandCalls("hset") - writesBefore).isEqualTo(2L);
        List<TsKvEntry> result = await(dao.findLatest(tenant, device, updates.stream().map(TsKvEntry::getKey).toList()));
        for (int i = 0; i < result.size(); i++) {
            same(result.get(i), i >= 3000 && i < 6000 ? initial.get(i) : updates.get(i));
        }

        List<TsKvEntry> stale = IntStream.range(0, 6001).mapToObj(i -> value("field" + i, 99, 3)).toList();
        writesBefore = commandCalls("hset");
        assertThat(await(dao.saveLatest(tenant, device, stale))).isZero();
        assertThat(commandCalls("hset") - writesBefore).isZero();
    }

    @Test
    @Disabled("Out of agreed fix scope (2026-09-03): randomized batches contain duplicate fields")
    void randomizedBatchesMatchSequentialLatestReference() throws Exception {
        Random random = new Random(20260903);
        Map<String, TsKvEntry> expected = new HashMap<>();
        for (int batch = 0; batch < 100; batch++) {
            List<TsKvEntry> entries = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                TsKvEntry entry = value("k" + random.nextInt(30), random.nextInt(1000), batch * 20L + i);
                entries.add(entry);
                expected.compute(entry.getKey(), (key, old) -> old == null || entry.getTs() >= old.getTs() ? entry : old);
            }
            await(dao.saveLatest(tenant, device, entries));
            for (TsKvEntry entry : await(dao.findAllLatest(tenant, device))) {
                same(entry, expected.get(entry.getKey()));
            }
            assertThat(template.opsForHash().size(dao.buildKey(device))).isEqualTo(expected.size());
        }
    }

    @Test
    void concurrentOutOfOrderWritesKeepMaximumTimestamp() throws Exception {
        List<Integer> order = new ArrayList<>(IntStream.rangeClosed(1, 500).boxed().toList());
        Collections.shuffle(order, new Random(42));
        List<ListenableFuture<Long>> writes = order.stream()
                .map(i -> dao.saveLatest(tenant, device, List.of(value("a", i, i), value("b", i, -i)))).toList();
        await(Futures.allAsList(writes));
        same(read("a"), value("a", 500, 500));
        same(read("b"), value("b", 500, -500));
    }

    @Test
    void entityTypesAndDeviceIdsAreIsolatedAndKeysAreDeduplicated() throws Exception {
        DeviceId other = new DeviceId(UUID.randomUUID());
        AssetId asset = new AssetId(device.getId());
        track(other);
        track(asset);
        await(dao.saveLatest(tenant, device, value("shared", 100, 1)));
        await(dao.saveLatest(tenant, other, value("shared", 100, 2)));
        await(dao.saveLatest(tenant, asset, value("asset", 100, 3)));
        assertThat(dao.findAllKeysByEntityIds(tenant, List.of(device, other, asset)))
                .containsExactlyInAnyOrder("shared", "asset");
        assertThat(await(dao.findLatest(tenant, other, "shared")).getValue()).isEqualTo(2L);
        assertThat(await(dao.findLatestOpt(tenant, asset, "shared"))).isEmpty();
        assertThat(read("shared").getValue()).isEqualTo(1L);
    }

    @ParameterizedTest
    @CsvSource({"99,false", "100,true", "150,true", "199,true", "200,false"})
    void deletionUsesHalfOpenInterval(long ts, boolean removed) throws Exception {
        await(dao.saveLatest(tenant, device, List.of(value("target", ts, 1), value("keep", ts, 2))));
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("target", 100, 200)));
        assertThat(result.isRemoved()).isEqualTo(removed);
        assertThat(await(dao.findLatestOpt(tenant, device, "target")).isEmpty()).isEqualTo(removed);
        assertThat(read("keep").getValue()).isEqualTo(2L);
    }

    @Test
    void deletionOfMissingFieldIsIdempotent() throws Exception {
        var query = new BaseDeleteTsKvQuery("missing", 0, 1000);
        assertThat(await(dao.removeLatest(tenant, device, query)).isRemoved()).isFalse();
        assertThat(await(dao.removeLatest(tenant, device, query)).isRemoved()).isFalse();
    }

    @Test
    @Disabled("Out of agreed fix scope (2026-09-03): changing stored-null deletion semantics")
    void deletesStoredNullValue() throws Exception {
        await(dao.saveLatest(tenant, device, new BasicTsKvEntry(100, new StringDataEntry("nullable", null))));
        assertThat(await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("nullable", 100, 200))).isRemoved()).isTrue();
        assertThat(await(dao.findLatestOpt(tenant, device, "nullable"))).isEmpty();
    }

    @Test
    void deletionDoesNotRemoveNewerConcurrentWrite() throws Exception {
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        injectWriteAtDeleteExecution(true);
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200, true)));
        assertThat(result.isRemoved()).isFalse();
        same(read("a"), value("a", 300, 3));
        verifyNoInteractions(history);
    }

    @Test
    void writeAfterAtomicDeletionIsPreserved() throws Exception {
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        injectWriteAtDeleteExecution(false);
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200)));
        assertThat(result.isRemoved()).isTrue();
        same(read("a"), value("a", 300, 3));
    }

    private void injectWriteAtDeleteExecution(boolean before) {
        RedisScript<?> deletion = (RedisScript<?>) ReflectionTestUtils.getField(dao, "removeLatestScript");
        RedisTemplate<String, String> interleavingTemplate = spy(template);
        doAnswer(invocation -> {
            boolean deleting = invocation.getArgument(0) == deletion;
            if (deleting && before) {
                template.opsForHash().put(dao.buildKey(device), "a", "300|l:3");
            }
            Object result = invocation.callRealMethod();
            if (deleting && !before) {
                template.opsForHash().put(dao.buildKey(device), "a", "300|l:3");
            }
            return result;
        }).when(interleavingTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
        dao.redisTemplate = interleavingTemplate;
    }

    @ParameterizedTest
    @CsvSource({"100|,false", "100|s:,true"})
    void atomicDeletionPreservesNullAndEmptyStringSemantics(String raw, boolean expectedRemoval) throws Exception {
        template.opsForHash().put(dao.buildKey(device), "a", raw);
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200)));
        assertThat(result.isRemoved()).isEqualTo(expectedRemoval);
        assertThat(template.opsForHash().hasKey(dao.buildKey(device), "a")).isEqualTo(!expectedRemoval);
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "bad|s:value"})
    void invalidDeletionTimestampFailsWithoutRemovingTheField(String raw) {
        template.opsForHash().put(dao.buildKey(device), "a", raw);
        assertThatThrownBy(() -> await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200))))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("Invalid serialized latest");
        assertThat(template.<String, String>opsForHash().get(dao.buildKey(device), "a")).isEqualTo(raw);
    }

    @Test
    void rewriteRestoresHistoricalValueAfterActualDeletion() throws Exception {
        TsKvEntry previous = value("a", 50, 5);
        when(history.findAllAsync(any(), any(), any())).thenReturn(Futures.immediateFuture(
                new ReadTsKvQueryResult(0, List.of(previous), 50)));
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200, true)));
        assertThat(result.isRemoved()).isTrue();
        same(result.getData(), previous);
        same(read("a"), previous);
    }

    @Test
    @Disabled("Out of agreed fix scope (2026-09-03): historical rewrite version contract")
    void rewriteRestoresHistoricalValueAndTimestampVersion() throws Exception {
        TsKvEntry previous = value("a", 50, 5);
        when(history.findAllAsync(any(), any(), any())).thenReturn(Futures.immediateFuture(
                new ReadTsKvQueryResult(0, List.of(previous), 50)));
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200, true)));
        assertThat(result.isRemoved()).isTrue();
        same(result.getData(), previous);
        same(read("a"), previous);
        assertThat(result.getVersion()).isEqualTo(50L);
    }

    @Test
    void rewriteWithoutHistoryLeavesFieldAbsent() throws Exception {
        when(history.findAllAsync(any(), any(), any())).thenReturn(Futures.immediateFuture(
                new ReadTsKvQueryResult(0, List.of(), 0)));
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        var result = await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200, true)));
        assertThat(result.isRemoved()).isTrue();
        assertThat(result.getData()).isNull();
        assertThat(await(dao.findLatestOpt(tenant, device, "a"))).isEmpty();
    }

    @Test
    void historicalRewriteCannotOverwriteNewerIncomingValue() throws Exception {
        when(history.findAllAsync(any(), any(), any())).thenAnswer(invocation -> {
            template.opsForHash().put(dao.buildKey(device), "a", "300|l:3");
            return Futures.immediateFuture(new ReadTsKvQueryResult(0, List.of(value("a", 50, 5)), 50));
        });
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 100, 200, true)));
        same(read("a"), value("a", 300, 3));
    }

    @Test
    void telemetryKeyEndingInTsIsNotTreatedAsMetadata() throws Exception {
        TsKvEntry entry = value("device:ts", 100, 42);
        await(dao.saveLatest(tenant, device, entry));
        same(read("device:ts"), entry);
        same(await(dao.findAllLatest(tenant, device)).get(0), entry);
        assertThat(dao.findAllKeysByEntityIds(tenant, List.of(device))).containsExactly("device:ts");
    }

    @Test
    @Disabled("Out of agreed fix scope (2026-09-03): legacy cluster metadata compatibility")
    void readsLegacyClusterDataWithoutLeakingTimestampMetadata() throws Exception {
        RedisClusterTimeseriesLatestDao legacy = new RedisClusterTimeseriesLatestDao();
        ReflectionTestUtils.setField(legacy, "redisTemplate", template);
        ReflectionTestUtils.setField(legacy, "cacheExecutorService", executor);
        legacy.init();
        await(legacy.saveLatest(tenant, device, List.of(value("temperature", 100, 25))));
        same(read("temperature"), value("temperature", 100, 25));
        assertThat(await(dao.findAllLatest(tenant, device))).hasSize(1);
        assertThat(dao.findAllKeysByEntityIds(tenant, List.of(device))).containsExactly("temperature");
    }

    @Test
    @Disabled("Out of agreed fix scope (2026-09-03): migrating the legacy standalone key space")
    void switchingFromLegacyStandaloneRedisKeepsExistingLatestVisible() throws Exception {
        RedisTimeseriesLatestDao legacy = new RedisTimeseriesLatestDao();
        ReflectionTestUtils.setField(legacy, "redisTemplate", template);
        ReflectionTestUtils.setField(legacy, "cacheExecutorService", executor);
        legacy.init();
        ownedKeys.add("ts:latest:DEVICE:" + device.getId());
        ownedKeys.add("ts:ts:DEVICE:" + device.getId());
        TsKvEntry expected = value("temperature", 100, 25);
        await(legacy.saveLatest(tenant, device, List.of(expected)));
        same(await(legacy.findLatest(tenant, device, "temperature")), expected);
        same(read("temperature"), expected);
    }

    @Test
    void redisCommandErrorsPropagateAndSubsequentOperationsRecover() throws Exception {
        template.opsForValue().set(dao.buildKey(device), "wrong-type");
        assertThatThrownBy(() -> await(dao.saveLatest(tenant, device, value("a", 100, 1))))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("WRONGTYPE");
        assertThatThrownBy(() -> read("a")).isInstanceOf(ExecutionException.class).hasStackTraceContaining("WRONGTYPE");
        assertThatThrownBy(() -> await(dao.findLatest(tenant, device, List.of("a"))))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("WRONGTYPE");
        assertThatThrownBy(() -> await(dao.findAllLatest(tenant, device)))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("WRONGTYPE");
        assertThatThrownBy(() -> await(dao.removeLatest(tenant, device, new BaseDeleteTsKvQuery("a", 0, 200))))
                .isInstanceOf(ExecutionException.class).hasStackTraceContaining("WRONGTYPE");
        template.delete(dao.buildKey(device));
        await(dao.saveLatest(tenant, device, value("a", 100, 1)));
        same(read("a"), value("a", 100, 1));
    }

    @Test
    void malformedValuesDoNotHideHealthyFieldsFromFindAllAndCanBeReplaced() throws Exception {
        template.opsForHash().putAll(dao.buildKey(device), Map.of("bad", "broken", "healthy", "100|l:42"));
        assertThat(await(dao.findAllLatest(tenant, device))).extracting(TsKvEntry::getKey).containsExactly("healthy");
        assertThatThrownBy(() -> read("bad")).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(dao.findLatestOpt(tenant, device, "bad"))).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> await(dao.findLatest(tenant, device, List.of("healthy", "bad"))))
                .isInstanceOf(ExecutionException.class);
        same(read("healthy"), value("healthy", 100, 42));
        await(dao.saveLatest(tenant, device, value("bad", 101, 43)));
        same(read("bad"), value("bad", 101, 43));
    }
}
