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
package org.thingsboard.server.dao.timeseries.latestbench;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Latest-store 写入吞吐压测：对比 Redis 与 Valkey，并量化"ts 守卫（防乱序覆盖）"的成本。
 * <p>
 * <b>被测的四个变体</b>（关键：是否保住现网 Lua 的三条保证）：
 * <pre>
 *  变体          服务端操作/点            原子性  ts守卫  data-ts一致  说明
 *  ─────────────────────────────────────────────────────────────────────────────
 *  A  CURRENT    HGET+HSET+HSET (Lua)      ✓      ✓        ✓        现网实现(双哈希)
 *  B  MERGED_TS  HGET+HSET      (Lua)      ✓      ✓        ✓(天然)  ts 编码进 value，单哈希
 *  C  MULTIFIELD HSET(多field)  (Lua)      ✓      ✓        ✓(天然)  B + 一次 EVAL 处理整设备
 *  D  NOGUARD    HSET(多field)  (原生)     ✓      ✗        ✓(天然)  无服务端守卫(上界参考)
 * </pre>
 * D 不保证 ts 单调（需上层 JVM 兜底），仅用于测出"守卫成本"的上界参考，<b>不是可直接采用的方案</b>。
 * <p>
 * <b>负载模型</b>取自现场表格（300MWh / 78 万点，秒级点占比约 31%）：
 * 每类设备每秒上报固定数量的测点（pcs/bms 各 200，groupbms 80，cl 2500，box 130；cell 不参与秒级）。
 * <p>
 * <b>运行方式</b>（需本机 docker 或已有实例）：
 * <pre>
 *   # 1. 起两个实例
 *   docker run -d --name bench-redis  -p 6390:6379 redis:7.2-alpine   --save "" --appendonly no
 *   docker run -d --name bench-valkey -p 6391:6379 valkey/valkey:8-alpine --save "" --appendonly no
 *   # 2. 跑压测（dao 模块 test classpath）
 *   mvn -pl dao test-compile
 *   mvn -pl dao exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=org.thingsboard.server.dao.timeseries.latestbench.LatestStoreBenchmark \
 *       -Dexec.args="127.0.0.1:6390 127.0.0.1:6391"
 * </pre>
 */
public class LatestStoreBenchmark {

    // ── 负载模型：现场表格的设备形态（设备类型 → 每设备每秒上报点数）──────────────
    private static final DeviceType[] DEVICE_TYPES = {
            new DeviceType("pcs", 200),
            new DeviceType("bms", 200),
            new DeviceType("groupbms", 80),
            new DeviceType("cl", 2500),
            new DeviceType("box", 130),
    };

    private record DeviceType(String name, int pointsPerSecond) {
    }

    /** 压测参数（可用系统属性覆盖）。 */
    private static final int DEVICES_PER_TYPE = Integer.getInteger("bench.devicesPerType", 100);
    private static final int WRITER_THREADS = Integer.getInteger("bench.threads", 8);
    private static final int ROUNDS = Integer.getInteger("bench.rounds", 5);
    private static final int PIPELINE_DEPTH = Integer.getInteger("bench.pipeline", 20);
    private static final int VALUE_BYTES = Integer.getInteger("bench.valueBytes", 32);

    // ── Lua：变体 A（现网实现，双哈希 + 逐 field 守卫）─────────────────────────────
    private static final String LUA_A_CURRENT =
            "for i = 1, #ARGV, 3 do " +
            "    local field = ARGV[i] local value = ARGV[i+1] local ts = tonumber(ARGV[i+2]) " +
            "    local existingTs = redis.call('hget', KEYS[2], field) " +
            "    if existingTs then " +
            "        local oldTs = tonumber(existingTs) " +
            "        if oldTs and oldTs <= ts then " +
            "            redis.call('hset', KEYS[1], field, value) " +
            "            redis.call('hset', KEYS[2], field, tostring(ts)) " +
            "        end " +
            "    else " +
            "        redis.call('hset', KEYS[1], field, value) " +
            "        redis.call('hset', KEYS[2], field, tostring(ts)) " +
            "    end " +
            "end return #ARGV / 3";

    // ── Lua：变体 B（ts 编码进 value，单哈希；守卫等价保留）───────────────────────
    // value 格式: "<ts>|<payload>"，比较时取分隔符前的数字
    private static final String LUA_B_MERGED =
            "for i = 1, #ARGV, 2 do " +
            "    local field = ARGV[i] local newVal = ARGV[i+1] " +
            "    local newTs = tonumber(string.sub(newVal, 1, string.find(newVal, '|', 1, true) - 1)) " +
            "    local cur = redis.call('hget', KEYS[1], field) " +
            "    if cur then " +
            "        local oldTs = tonumber(string.sub(cur, 1, string.find(cur, '|', 1, true) - 1)) " +
            "        if oldTs and oldTs <= newTs then redis.call('hset', KEYS[1], field, newVal) end " +
            "    else redis.call('hset', KEYS[1], field, newVal) end " +
            "end return #ARGV / 2";

    // ── Lua：变体 C —— 直接取自生产实现 AbstractFastTimeseriesLatestDao.SAVE_LATEST_BATCH_SCRIPT
    //    (逐字一致, 保证压测的就是将要上线的代码)
    private static final String LUA_C_MULTIFIELD =
            "local n = #ARGV / 2 " +
            "local fields = {} " +
            "for i = 1, n do fields[i] = ARGV[i*2-1] end " +
            "local cur = redis.call('hmget', KEYS[1], unpack(fields)) " +
            "local out = {} local cnt = 0 " +
            "for i = 1, n do " +
            "    local newVal = ARGV[i*2] " +
            "    local sep = string.find(newVal, '|', 1, true) " +
            "    local newTs = tonumber(string.sub(newVal, 1, sep - 1)) " +
            "    local ok = true " +
            "    if cur[i] then " +
            "        local oSep = string.find(cur[i], '|', 1, true) " +
            "        if oSep then " +
            "            local oldTs = tonumber(string.sub(cur[i], 1, oSep - 1)) " +
            "            if oldTs and newTs and oldTs > newTs then ok = false end " +
            "        end " +
            "    end " +
            "    if ok then out[#out+1] = fields[i] out[#out+1] = newVal cnt = cnt + 1 end " +
            "end " +
            "if #out > 0 then redis.call('hset', KEYS[1], unpack(out)) end " +
            "return cnt";

    // ── Lua：变体 E —— 只写不读的 Lua(无守卫)。用于隔离「Lua 本身的开销」：
    //    E vs D 的差 = 纯 Lua 解释/调用开销；C vs E 的差 = ts 守卫(那次 HMGET 批读)的成本。
    private static final String LUA_E_WRITE_ONLY =
            "redis.call('hset', KEYS[1], unpack(ARGV)) return #ARGV / 2";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: LatestStoreBenchmark <host:port> [host:port ...]   (第一个建议为 redis, 第二个 valkey)");
            return;
        }
        List<Device> devices = buildDevices();
        long pointsPerRound = devices.stream().mapToLong(d -> d.fields.length).sum();

        System.out.println("═".repeat(96));
        System.out.printf("负载模型: %d 设备, 每轮 %,d 个点(= 1 秒的秒级上报量), 线程 %d, 管道 %d, 值 %dB%n",
                devices.size(), pointsPerRound, WRITER_THREADS, PIPELINE_DEPTH, VALUE_BYTES);
        System.out.println("变体说明: A=现网(3op) B=合并ts(2op) C=整设备批读批写 D=无守卫(上界参考, 不可直接用)");
        System.out.println("═".repeat(96));

        // 先验证生产 Lua 的 ts 守卫确实生效, 再谈性能
        verifyTsGuard(args[0]);

        Map<String, Map<Variant, Double>> all = new java.util.LinkedHashMap<>();
        for (String target : args) {
            String[] hp = target.split(":");
            String host = hp[0];
            int port = Integer.parseInt(hp[1]);
            String server = probeServer(host, port);
            System.out.printf("%n▶ 目标 %s:%d  —  %s%n", host, port, server);
            System.out.println("─".repeat(96));
            System.out.printf("%-28s %14s %14s %14s   %s%n", "变体", "点/秒", "命令/秒", "相对A", "ts守卫");
            System.out.println("─".repeat(96));

            Map<Variant, Double> perVariant = new java.util.LinkedHashMap<>();
            double base = 0;
            for (Variant v : Variant.values()) {
                double tps = runVariant(host, port, v, devices, pointsPerRound);
                if (v == Variant.A_CURRENT) {
                    base = tps;
                }
                perVariant.put(v, tps);
                System.out.printf("%-28s %,14.0f %,14.0f %13.2fx   %s%n",
                        v.label, tps, tps / v.pointsPerCommand, base > 0 ? tps / base : 1.0,
                        v.tsGuarded ? "✓ 保留" : "✗ 无(需JVM兜底)");
            }
            all.put(server + " @" + host + ":" + port, perVariant);
        }
        printSummary(all);

        // ── 读性能与读写混合(高频查询场景)──────────────────────────────────────
        System.out.println();
        System.out.println("═".repeat(96));
        System.out.println("读性能（对标生产三种读路径）");
        System.out.println("═".repeat(96));
        System.out.printf("%-30s %14s %14s %14s%n", "读模式", "点/秒", "命令/秒", "说明");
        System.out.println("─".repeat(96));
        for (String target : args) {
            String[] hp = target.split(":");
            String host = hp[0];
            int port = Integer.parseInt(hp[1]);
            System.out.printf("▶ %s%n", probeServer(host, port));
            runReadBench(host, port, devices);
        }

        // ── 高频查询延迟（不使用管道，反映单次查询的真实响应时间）────────────────
        System.out.println();
        System.out.println("═".repeat(96));
        System.out.println("高频查询延迟（单次请求-响应，无管道）");
        System.out.println("═".repeat(96));
        for (String target : args) {
            String[] hp = target.split(":");
            System.out.printf("▶ %s%n", probeServer(hp[0], Integer.parseInt(hp[1])));
            runLatencyBench(hp[0], Integer.parseInt(hp[1]), devices);
        }
    }

    private enum ReadMode {
        HGET_SINGLE("HGET 逐点读(朴素)", "findLatest(key) 单点"),
        HMGET_BATCH("HMGET 批量读(新实现)", "findLatest(keys) 多点一次"),
        HGETALL_DEVICE("HGETALL 整设备", "findAllLatest(entityId)");

        final String label;
        final String note;

        ReadMode(String label, String note) {
            this.label = label;
            this.note = note;
        }
    }

    private static void runReadBench(String host, int port, List<Device> devices) throws Exception {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(WRITER_THREADS * 2);
        try (JedisPool pool = new JedisPool(cfg, host, port, 30000)) {
            // 先用生产写路径灌满数据
            try (Jedis j = pool.getResource()) {
                j.flushAll();
            }
            execRound(pool, Variant.C_MULTIFIELD, devices, 1000L);

            long pointsPerRound = devices.stream().mapToLong(d -> d.fields.length).sum();
            for (ReadMode mode : ReadMode.values()) {
                double best = 0;
                for (int r = 0; r < ROUNDS; r++) {
                    long t0 = System.nanoTime();
                    readRound(pool, mode, devices);
                    double sec = (System.nanoTime() - t0) / 1e9;
                    best = Math.max(best, pointsPerRound / sec);
                }
                double cmds = switch (mode) {
                    case HGET_SINGLE -> best;                       // 每点 1 条命令
                    case HMGET_BATCH, HGETALL_DEVICE -> best / avgFields(devices); // 每设备 1 条
                };
                System.out.printf("  %-28s %,14.0f %,14.0f   %s%n", mode.label, best, cmds, mode.note);
            }

            // 读写混合: 一半线程写、一半线程读, 反映真实并发下的相互影响
            double mixed = runMixed(pool, devices);
            System.out.printf("  %-28s %,14.0f %14s   %s%n", "读写混合(写50%/读50%)", mixed, "-",
                    "总吞吐(读+写点数)，反映单线程争用");
        }
    }

    /**
     * 高频查询延迟测试：单次请求-响应（<b>不使用管道</b>，管道会掩盖真实延迟）。
     * 分两种条件：① 空载；② <b>并发写入压力下</b>——后者才是生产真实场景。
     */
    private static void runLatencyBench(String host, int port, List<Device> devices) throws Exception {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(WRITER_THREADS * 3);
        try (JedisPool pool = new JedisPool(cfg, host, port, 30000)) {
            try (Jedis j = pool.getResource()) {
                j.flushAll();
            }
            execRound(pool, Variant.C_MULTIFIELD, devices, 1000L);

            for (boolean underWriteLoad : new boolean[]{false, true}) {
                java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                ExecutorService loadEs = null;
                if (underWriteLoad) {
                    // 后台持续写入, 模拟生产中"边写边查"
                    loadEs = Executors.newFixedThreadPool(Math.max(1, WRITER_THREADS / 2));
                    int half = Math.max(1, WRITER_THREADS / 2);
                    int chunk = (devices.size() + half - 1) / half;
                    for (int t = 0; t < half; t++) {
                        int from = t * chunk;
                        int to = Math.min(devices.size(), from + chunk);
                        if (from >= to) break;
                        List<Device> slice = devices.subList(from, to);
                        loadEs.submit(() -> {
                            long ts = 9000L;
                            try (Jedis j = pool.getResource()) {
                                while (!stop.get()) {
                                    writeSlice(j, Variant.C_MULTIFIELD, slice, ts++);
                                }
                            } catch (Exception ignore) {
                                // 压测背景负载, 忽略
                            }
                        });
                    }
                    Thread.sleep(300); // 让写入压力建立起来
                }
                System.out.printf("  %s%n", underWriteLoad ? "── 并发写入压力下（生产真实场景）──" : "── 空载 ──");
                for (ReadMode mode : ReadMode.values()) {
                    long[] us = measureLatency(pool, mode, devices);
                    System.out.printf("    %-24s p50 %6.2fms  p95 %6.2fms  p99 %6.2fms  max %7.2fms%n",
                            mode.label, us[0] / 1000.0, us[1] / 1000.0, us[2] / 1000.0, us[3] / 1000.0);
                }
                if (loadEs != null) {
                    stop.set(true);
                    loadEs.shutdown();
                    loadEs.awaitTermination(30, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** 单连接串行发起 N 次读, 记录每次耗时, 返回 [p50,p95,p99,max]（微秒）。 */
    private static long[] measureLatency(JedisPool pool, ReadMode mode, List<Device> devices) {
        int samples = Integer.getInteger("bench.latencySamples", 3000);
        long[] lat = new long[samples];
        try (Jedis j = pool.getResource()) {
            for (int i = 0; i < samples; i++) {
                Device d = devices.get(i % devices.size());
                long t0 = System.nanoTime();
                switch (mode) {
                    case HGET_SINGLE -> j.hget(d.dataKey, d.fields[i % d.fields.length]);
                    case HMGET_BATCH -> j.hmget(d.dataKey, d.fields);
                    case HGETALL_DEVICE -> j.hgetAll(d.dataKey);
                }
                lat[i] = (System.nanoTime() - t0) / 1000;
            }
        }
        java.util.Arrays.sort(lat);
        return new long[]{
                lat[(int) (samples * 0.50)],
                lat[(int) (samples * 0.95)],
                lat[(int) (samples * 0.99)],
                lat[samples - 1]
        };
    }

    private static double avgFields(List<Device> devices) {
        return devices.stream().mapToLong(d -> d.fields.length).sum() / (double) devices.size();
    }

    private static void readRound(JedisPool pool, ReadMode mode, List<Device> devices) throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(WRITER_THREADS);
        try {
            int chunk = (devices.size() + WRITER_THREADS - 1) / WRITER_THREADS;
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < WRITER_THREADS; t++) {
                int from = t * chunk;
                int to = Math.min(devices.size(), from + chunk);
                if (from >= to) break;
                List<Device> slice = devices.subList(from, to);
                fs.add(es.submit((Callable<Void>) () -> {
                    try (Jedis j = pool.getResource()) {
                        readSlice(j, mode, slice);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        } finally {
            es.shutdown();
            es.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private static void readSlice(Jedis j, ReadMode mode, List<Device> devices) {
        int inFlight = 0;
        Pipeline p = j.pipelined();
        for (Device d : devices) {
            switch (mode) {
                case HGET_SINGLE -> {
                    for (String f : d.fields) {
                        p.hget(d.dataKey, f);
                    }
                    inFlight += d.fields.length;
                }
                case HMGET_BATCH -> {
                    p.hmget(d.dataKey, d.fields);
                    inFlight++;
                }
                case HGETALL_DEVICE -> {
                    p.hgetAll(d.dataKey);
                    inFlight++;
                }
            }
            if (inFlight >= PIPELINE_DEPTH) {
                p.sync();
                p = j.pipelined();
                inFlight = 0;
            }
        }
        if (inFlight > 0) {
            p.sync();
        }
    }

    /** 读写混合：一半线程执行生产写路径(C)，一半执行批量读(HMGET)，测总吞吐。 */
    private static double runMixed(JedisPool pool, List<Device> devices) throws Exception {
        long pointsPerRound = devices.stream().mapToLong(d -> d.fields.length).sum();
        double best = 0;
        for (int r = 0; r < ROUNDS; r++) {
            ExecutorService es = Executors.newFixedThreadPool(WRITER_THREADS);
            long ts = 5000L + r;
            long t0 = System.nanoTime();
            try {
                int half = Math.max(1, WRITER_THREADS / 2);
                int chunk = (devices.size() + half - 1) / half;
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < half; t++) {
                    int from = t * chunk;
                    int to = Math.min(devices.size(), from + chunk);
                    if (from >= to) break;
                    List<Device> slice = devices.subList(from, to);
                    fs.add(es.submit((Callable<Void>) () -> {
                        try (Jedis j = pool.getResource()) {
                            writeSlice(j, Variant.C_MULTIFIELD, slice, ts);
                        }
                        return null;
                    }));
                    fs.add(es.submit((Callable<Void>) () -> {
                        try (Jedis j = pool.getResource()) {
                            readSlice(j, ReadMode.HMGET_BATCH, slice);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) {
                    f.get();
                }
            } finally {
                es.shutdown();
                es.awaitTermination(60, TimeUnit.SECONDS);
            }
            double sec = (System.nanoTime() - t0) / 1e9;
            best = Math.max(best, (pointsPerRound * 2) / sec); // 读+写各一轮
        }
        return best;
    }

    private enum Variant {
        A_CURRENT("A 现网 Lua(双哈希3op)", true, 1),
        B_MERGED_TS("B 合并ts 单哈希(2op)", true, 1),
        C_MULTIFIELD("C 新实现 Lua(读+比较+写)", true, 200),
        E_LUA_WRITE_ONLY("E Lua仅写(无守卫,隔离Lua开销)", false, 200),
        D_NOGUARD("D 原生HSET(无Lua无守卫)", false, 200);

        final String label;
        final boolean tsGuarded;
        final int pointsPerCommand;

        Variant(String label, boolean tsGuarded, int pointsPerCommand) {
            this.label = label;
            this.tsGuarded = tsGuarded;
            this.pointsPerCommand = pointsPerCommand;
        }
    }

    private static final class Device {
        final String dataKey;
        final String tsKey;
        final String[] fields;

        Device(String type, int idx, int points) {
            this.dataKey = "ts:latest:DEVICE:" + type + "-" + idx;
            this.tsKey = "ts:ts:DEVICE:" + type + "-" + idx;
            this.fields = new String[points];
            for (int i = 0; i < points; i++) {
                this.fields[i] = type + "_p" + i;
            }
        }
    }

    private static List<Device> buildDevices() {
        List<Device> list = new ArrayList<>();
        for (DeviceType dt : DEVICE_TYPES) {
            int count = "cl".equals(dt.name) ? Math.max(1, DEVICES_PER_TYPE / 100) : DEVICES_PER_TYPE;
            for (int i = 0; i < count; i++) {
                list.add(new Device(dt.name, i, dt.pointsPerSecond));
            }
        }
        return list;
    }

    /**
     * 正确性前置校验：生产 Lua（变体 C）必须严格保持"仅当新 ts >= 旧 ts 才覆盖"。
     * 这是 Kafka 重放/断网重传场景下 latest 不被旧值污染的根本保证，性能再高也不能破坏它。
     */
    private static void verifyTsGuard(String target) {
        String[] hp = target.split(":");
        try (Jedis j = new Jedis(hp[0], Integer.parseInt(hp[1]))) {
            String key = "verify:{guard}:data";
            j.del(key);
            // ① 先写 ts=2000
            j.eval(LUA_C_MULTIFIELD, List.of(key), List.of("k1", "2000|d:99.9"));
            String after1 = j.hget(key, "k1");
            // ② 再写更旧的 ts=1000 —— 必须被拒绝
            j.eval(LUA_C_MULTIFIELD, List.of(key), List.of("k1", "1000|d:11.1"));
            String after2 = j.hget(key, "k1");
            // ③ 写更新的 ts=3000 —— 必须成功覆盖
            j.eval(LUA_C_MULTIFIELD, List.of(key), List.of("k1", "3000|d:33.3"));
            String after3 = j.hget(key, "k1");
            // ④ 同 ts 覆盖 —— 现网语义为 oldTs > newTs 才拒绝, 故同 ts 允许覆盖
            j.eval(LUA_C_MULTIFIELD, List.of(key), List.of("k1", "3000|d:44.4"));
            String after4 = j.hget(key, "k1");
            j.del(key);

            boolean ok = "2000|d:99.9".equals(after1)
                    && "2000|d:99.9".equals(after2)   // 旧数据被拒绝, 值未变
                    && "3000|d:33.3".equals(after3)
                    && "3000|d:44.4".equals(after4);
            System.out.println("【正确性校验】ts 单调守卫(防乱序覆盖): " + (ok ? "✅ 通过" : "❌ 失败"));
            System.out.printf("   写ts2000→%s | 写更旧ts1000→%s(应不变) | 写ts3000→%s | 同ts覆盖→%s%n",
                    after1, after2, after3, after4);
            if (!ok) {
                throw new IllegalStateException("ts 守卫校验失败, 拒绝继续压测");
            }
        }
    }

    private static String probeServer(String host, int port) {
        try (Jedis j = new Jedis(host, port)) {
            String info = j.info("server");
            String ver = "?";
            String name = "redis";
            for (String line : info.split("\r?\n")) {
                if (line.startsWith("redis_version:")) ver = line.substring(14).trim();
                if (line.startsWith("valkey_version:")) { ver = line.substring(15).trim(); name = "valkey"; }
                if (line.startsWith("server_name:")) name = line.substring(12).trim();
            }
            return name + " " + ver;
        } catch (Exception e) {
            return "unknown(" + e.getMessage() + ")";
        }
    }

    private static double runVariant(String host, int port, Variant v, List<Device> devices, long pointsPerRound)
            throws Exception {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(WRITER_THREADS * 2);
        try (JedisPool pool = new JedisPool(cfg, host, port, 30000)) {
            try (Jedis j = pool.getResource()) {
                j.flushAll();
            }
            // 预热：先灌一轮，让哈希建好（避免把建表成本算进稳态吞吐）
            execRound(pool, v, devices, 1L);
            double best = 0;
            for (int r = 0; r < ROUNDS; r++) {
                long ts = 1000L + r;
                long t0 = System.nanoTime();
                execRound(pool, v, devices, ts);
                double sec = (System.nanoTime() - t0) / 1e9;
                best = Math.max(best, pointsPerRound / sec);
            }
            return best;
        }
    }

    private static void execRound(JedisPool pool, Variant v, List<Device> devices, long ts) throws Exception {
        ExecutorService pool2 = Executors.newFixedThreadPool(WRITER_THREADS);
        try {
            int chunk = (devices.size() + WRITER_THREADS - 1) / WRITER_THREADS;
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < WRITER_THREADS; t++) {
                int from = t * chunk;
                int to = Math.min(devices.size(), from + chunk);
                if (from >= to) break;
                List<Device> slice = devices.subList(from, to);
                futures.add(pool2.submit((Callable<Void>) () -> {
                    try (Jedis j = pool.getResource()) {
                        writeSlice(j, v, slice, ts);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool2.shutdown();
            pool2.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private static void writeSlice(Jedis j, Variant v, List<Device> devices, long ts) {
        String payload = "x".repeat(VALUE_BYTES);
        int inFlight = 0;
        Pipeline p = j.pipelined();
        for (Device d : devices) {
            switch (v) {
                case A_CURRENT -> {
                    List<String> argv = new ArrayList<>(d.fields.length * 3);
                    for (String f : d.fields) {
                        argv.add(f);
                        argv.add(payload);
                        argv.add(Long.toString(ts));
                    }
                    p.eval(LUA_A_CURRENT, List.of(d.dataKey, d.tsKey), argv);
                }
                case B_MERGED_TS -> {
                    List<String> argv = new ArrayList<>(d.fields.length * 2);
                    String val = ts + "|" + payload;
                    for (String f : d.fields) {
                        argv.add(f);
                        argv.add(val);
                    }
                    p.eval(LUA_B_MERGED, List.of(d.dataKey), argv);
                }
                case C_MULTIFIELD -> {
                    List<String> argv = new ArrayList<>(d.fields.length * 2);
                    String val = ts + "|" + payload;
                    for (String f : d.fields) {
                        argv.add(f);
                        argv.add(val);
                    }
                    p.eval(LUA_C_MULTIFIELD, List.of(d.dataKey), argv);
                }
                case E_LUA_WRITE_ONLY -> {
                    List<String> argv = new ArrayList<>(d.fields.length * 2);
                    String val = ts + "|" + payload;
                    for (String f : d.fields) {
                        argv.add(f);
                        argv.add(val);
                    }
                    p.eval(LUA_E_WRITE_ONLY, List.of(d.dataKey), argv);
                }
                case D_NOGUARD -> {
                    Map<String, String> m = new HashMap<>(d.fields.length * 2);
                    String val = ts + "|" + payload;
                    for (String f : d.fields) {
                        m.put(f, val);
                    }
                    p.hset(d.dataKey, m);
                }
            }
            if (++inFlight >= PIPELINE_DEPTH) {
                p.sync();
                p = j.pipelined();
                inFlight = 0;
            }
        }
        if (inFlight > 0) {
            p.sync();
        }
    }

    private static void printSummary(Map<String, Map<Variant, Double>> all) {
        System.out.println();
        System.out.println("═".repeat(96));
        System.out.println("汇总（点/秒，越高越好）");
        System.out.println("═".repeat(96));
        System.out.printf("%-28s", "变体");
        for (String s : all.keySet()) {
            System.out.printf("%22s", s);
        }
        System.out.println();
        System.out.println("─".repeat(96));
        for (Variant v : Variant.values()) {
            System.out.printf("%-28s", v.label);
            for (Map<Variant, Double> m : all.values()) {
                System.out.printf("%,22.0f", m.getOrDefault(v, 0.0));
            }
            System.out.println();
        }
        System.out.println("─".repeat(96));
        // Valkey vs Redis 提升比
        if (all.size() >= 2) {
            List<Map<Variant, Double>> vals = new ArrayList<>(all.values());
            System.out.printf("%-28s", "第2个 / 第1个");
            System.out.println();
            for (Variant v : Variant.values()) {
                double a = vals.get(0).getOrDefault(v, 0.0);
                double b = vals.get(1).getOrDefault(v, 0.0);
                System.out.printf("%-28s %,20.2fx%n", v.label, a > 0 ? b / a : 0);
            }
        }
        System.out.println();
        System.out.println("归因分析(关键):");
        System.out.println("  • D vs E  = 「Lua 本身的开销」  —— 两者都无守卫, 只差一层 Lua");
        System.out.println("      若 E/D 接近 1.0 → Lua 几乎免费, 「Lua 慢」是误解");
        System.out.println("  • E vs C  = 「ts 守卫的成本」    —— 两者都走 Lua, 只差那次 HMGET 批读+比较");
        System.out.println("  • B/A     = 「合并 ts 到单哈希」的收益(操作数 3→2, 内存省一半)");
        System.out.println("  • C/B     = 「整设备批读批写」的收益(命令数摊薄约 200 倍)");
    }

    private static final LongAdder UNUSED = new LongAdder();
}
