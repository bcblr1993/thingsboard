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
package org.thingsboard.server.dao.timeseries.iotdb;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.session.pool.SessionPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.dao.util.IotdbAnyDao;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and owns the shared {@link SessionPool} used by the IoTDB time-series DAO.
 * A single {@code node_urls} entry means standalone, multiple entries means cluster —
 * the client code path is identical, only the URL list length differs.
 */
@Component
@IotdbAnyDao
@Slf4j
public class IotdbSessionPoolConfig {

    @Value("${iotdb.node_urls:127.0.0.1:6667}")
    private String nodeUrls;

    @Value("${iotdb.username:root}")
    private String username;

    @Value("${iotdb.password:root}")
    private String password;

    @Value("${iotdb.database:root.tb}")
    @Getter
    private String database;

    @Value("${iotdb.pool_size:0}")
    private int poolSize;

    // 独立读连接池: 读查询(历史/latest, 含实体查询的 N×K SELECT LAST)与写入物理隔离,
    // 查询洪峰再大也占不到写连接 —— 杜绝"读挤占写→flush 变慢→背压→Kafka lag"。0=自动。
    @Value("${iotdb.read_pool_size:0}")
    private int readPoolSize;

    @Value("${iotdb.enable_redirection:true}")
    private boolean enableRedirection;

    @Value("${iotdb.enable_auto_fetch:true}")
    private boolean enableAutoFetch;

    @Value("${iotdb.enable_compression:false}")
    private boolean enableCompression;

    @Value("${iotdb.ttl_ms:0}")
    private long ttlMs;

    // 全局 TTL 设置失败时是否 fail-fast(阻断启动)。默认 false=仅打 ERROR 后继续启动;
    // true=启动失败(适合把 TTL 当磁盘硬约束的生产环境, 宁可不启动也不让磁盘无界增长)。
    @Value("${iotdb.ttl_fail_fast:false}")
    private boolean ttlFailFast;

    // 启动期建库/连通性探测的重试(容忍 IoTDB 容器比 TB 稍晚就绪); 重试耗尽仍失败则 fail-fast
    @Value("${iotdb.init_retries:5}")
    private int initRetries;
    @Value("${iotdb.init_retry_interval_ms:3000}")
    private long initRetryIntervalMs;

    // SessionPool 超时/重试 —— 不显式配置则用客户端默认(部分为 0=无限), IoTDB 卡住时
    // RPC 会无限等待、pending 永不释放、背压永久阻塞上游规则引擎线程。必须给出有限上限。
    // connection_timeout 是 thrift socket 往返上限, 同时约束写入 flush 的单次 RPC 时长。
    @Value("${iotdb.connection_timeout_ms:15000}")
    private int connectionTimeoutMs;
    @Value("${iotdb.session_wait_timeout_ms:10000}")
    private long sessionWaitTimeoutMs;
    @Value("${iotdb.max_retry_count:3}")
    private int maxRetryCount;
    @Value("${iotdb.retry_interval_ms:1000}")
    private long retryIntervalMs;
    @Value("${iotdb.query_timeout_ms:60000}")
    private long queryTimeoutMs;

    // IoTDB latest 依赖原生 LastCache，不维护 SQL ts_kv_latest 版本表；EDQS 的全量初始化目前
    // 只从该 SQL 表装载。若允许此组合启动，EDQS 会静默得到空/旧 latest。仅在 latest=iotdb
    // 时 fail-fast；SQL/Cassandra/Redis latest 路径完全不受影响。
    @Value("${database.ts_latest.type:sql}")
    private String tsLatestType;
    @Value("${queue.edqs.sync.enabled:false}")
    private boolean edqsSyncEnabled;
    @Value("${queue.edqs.api.supported:false}")
    private boolean edqsApiSupported;

    // 写连接池(getSessionPool 保持不变: 写入/建库/基准均用它, 向后兼容)
    @Getter
    private SessionPool sessionPool;
    // 读连接池(历史/latest 查询专用, 与写入物理隔离)
    @Getter
    private SessionPool readSessionPool;

    @PostConstruct
    public void init() {
        validateFeatureCompatibility();
        List<String> urls = new ArrayList<>();
        for (String u : nodeUrls.split(",")) {
            String trimmed = u.trim();
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        int autoSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        int writeSize = poolSize > 0 ? poolSize : autoSize;
        int readSize = readPoolSize > 0 ? readPoolSize : autoSize;
        sessionPool = buildPool(urls, writeSize);
        readSessionPool = buildPool(urls, readSize);
        log.info("[IoTDB] SessionPools initialized. nodeUrls={}, writePoolSize={}, readPoolSize={}, database={}, "
                        + "redirection={}, autoFetch={}, connTimeout={}ms, sessionWait={}ms, maxRetry={}, queryTimeout={}ms",
                urls, writeSize, readSize, database, enableRedirection, enableAutoFetch,
                connectionTimeoutMs, sessionWaitTimeoutMs, maxRetryCount, queryTimeoutMs);
        ensureDatabase();
    }

    private void validateFeatureCompatibility() {
        if ("iotdb".equalsIgnoreCase(tsLatestType) && (edqsSyncEnabled || edqsApiSupported)) {
            throw new IllegalStateException("[IoTDB] Unsupported production configuration: "
                    + "database.ts_latest.type=iotdb cannot be combined with EDQS sync/API. "
                    + "IoTDB latest uses native LastCache and does not populate the SQL ts_kv_latest table "
                    + "required by EDQS initial synchronization. Set TB_EDQS_SYNC_ENABLED=false and "
                    + "TB_EDQS_API_SUPPORTED=false, or use sql/redis/cassandra for latest storage.");
        }
    }

    private SessionPool buildPool(List<String> urls, int size) {
        return new SessionPool.Builder()
                .nodeUrls(urls)
                .user(username)
                .password(password)
                .maxSize(size)
                .enableRedirection(enableRedirection)
                .enableAutoFetch(enableAutoFetch)
                .enableCompression(enableCompression)
                .connectionTimeoutInMs(connectionTimeoutMs)
                .waitToGetSessionTimeoutInMs(sessionWaitTimeoutMs)
                .maxRetryCount(maxRetryCount)
                .retryIntervalInMs(retryIntervalMs)
                .queryTimeoutInMs(queryTimeoutMs)
                .build();
    }

    private void ensureDatabase() {
        Exception lastError = null;
        int attempts = Math.max(1, initRetries);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                sessionPool.executeNonQueryStatement("create database " + database);
                log.info("[IoTDB] created database {}", database);
                lastError = null;
                break;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                // 501: "<db> has already been created as database" —— 重启后的正常情况, 静默确认
                if (msg.contains("already been created") || msg.contains("already exist")) {
                    log.info("[IoTDB] database {} already present", database);
                    lastError = null;
                    break;
                }
                // 网络不通 / 认证失败(801) / 权限不足 / dn_rpc_address 未开放等真实故障:
                // 绝不能吞掉 —— 否则 TB 带病启动, 上送后才写失败并堆积 Kafka lag。
                lastError = e;
                log.warn("[IoTDB] 连接/建库失败(尝试 {}/{}): {}", attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep(initRetryIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (lastError != null) {
            // fail-fast: 让 Spring 启动失败(配合容器 restart 策略重试), 而不是带病启动
            throw new RuntimeException("[IoTDB] 初始化失败: 无法连接或创建数据库 '" + database
                    + "'。请检查 iotdb.node_urls、账号密码、服务端 dn_rpc_address=0.0.0.0 与网络可达性。原因: "
                    + lastError.getMessage(), lastError);
        }
        applyGlobalTtl();
    }

    /**
     * 全局(数据库级)TTL: {@code iotdb.ttl_ms}。IoTDB 后端只支持这一种 TTL, 不支持 TB 的
     * 数据级(per-tenant/per-entity)TTL —— 后者在写路径会打明确 WARN(见 IotdbBaseTimeseriesDao)。
     * <ul>
     *   <li>ttl_ms &gt; 0: 设置 TTL; 失败时默认打 <b>ERROR</b> 后继续(TTL 非硬启动依赖);
     *       若 {@code iotdb.ttl_fail_fast=true} 则 <b>fail-fast 阻断启动</b>(把 TTL 当磁盘硬约束)。</li>
     *   <li>ttl_ms &lt;= 0: 语义为"永不过期"——主动 <b>unset</b> 可能残留的旧 TTL(如上次配非零、
     *       这次改回 0), 否则旧 TTL 会继续删数据, 与配置意图相悖。</li>
     * </ul>
     */
    private void applyGlobalTtl() {
        if (ttlMs > 0) {
            try {
                sessionPool.executeNonQueryStatement("set ttl to " + database + ".** " + ttlMs);
                log.info("[IoTDB] 已设置全局 TTL {} ms on {}.**", ttlMs, database);
            } catch (Exception e) {
                String msg = "[IoTDB] 全局 TTL 设置失败! TTL(" + ttlMs + " ms) 未生效, 数据将永不过期、"
                        + "磁盘会持续增长。请检查 IoTDB 权限/语法/版本。database=" + database + ", 原因=" + e.getMessage();
                if (ttlFailFast) {
                    // 生产可选: 把 TTL 当磁盘硬约束, 设置失败宁可 fail-fast 也不带病启动
                    throw new RuntimeException(msg + " (iotdb.ttl_fail_fast=true → 阻断启动)", e);
                }
                log.error("{} 如需设置失败即阻断启动, 置 iotdb.ttl_fail_fast=true。", msg, e);
            }
        } else {
            // "永不过期": 清除可能残留的旧全局 TTL(本就无 TTL 时 unset 无害)。静默处理, 不刷屏。
            try {
                sessionPool.executeNonQueryStatement("unset ttl to " + database + ".**");
                log.debug("[IoTDB] ttl_ms<=0: 已确保 {}.** 无全局 TTL(数据永不过期)", database);
            } catch (Exception e) {
                log.debug("[IoTDB] unset ttl on {}.** (通常表示本就无 TTL): {}", database, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (sessionPool != null) {
            sessionPool.close();
        }
        if (readSessionPool != null) {
            readSessionPool.close();
        }
        log.info("[IoTDB] SessionPools closed");
    }
}
