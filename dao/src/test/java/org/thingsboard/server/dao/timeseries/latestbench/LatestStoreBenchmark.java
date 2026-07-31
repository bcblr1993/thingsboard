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
    }

    private enum Variant {
        A_CURRENT("A 现网 Lua(双哈希3op)", true, 1),
        B_MERGED_TS("B 合并ts 单哈希(2op)", true, 1),
        C_MULTIFIELD("C 整设备批读批写", true, 200),
        D_NOGUARD("D 原生HSET多field(无守卫)", false, 200);

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
        System.out.println("解读要点:");
        System.out.println("  • B/A 比值 = 「合并 ts 到单哈希」的收益(操作数 3→2, 内存省一半)");
        System.out.println("  • C/B 比值 = 「整设备一次批读批写」的收益(命令数摊薄)");
        System.out.println("  • D/C 比值 = 「ts 守卫的成本」——D 去掉了服务端守卫, 差值即为保证的代价");
        System.out.println("  • 若 D/C 接近 1, 说明守卫几乎免费 → 直接选 C, 保证与性能兼得");
    }

    private static final LongAdder UNUSED = new LongAdder();
}
