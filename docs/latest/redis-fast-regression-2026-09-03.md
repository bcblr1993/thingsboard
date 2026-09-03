# redis-fast 回归测试记录（2026-09-03）

以下为修复前的初轮记录。后续用户确认只修复每段 3000 点的 Lua 批写和并发条件删除；修复与复测结果见 [两项修复复测记录](/D:/project/thingsboard-4.1/docs/latest/redis-fast-fix-regression-2026-09-03.md)。

结论：回归未通过，不能确认 `DATABASE_TS_LATEST_TYPE=redis-fast` 的全部读写场景正常。常规读写与序列化正常，但已复现批内乱序覆盖、大批次 Lua 执行失败和并发删除丢失新值等问题。

测试对象为云项目当前源码，Git 基线 `c717e4e5`。当前工作区 `application/src/main/resources/thingsboard.yml` 默认值为 `database.ts_latest.type=redis-fast`、`cache.type=redis`、`redis.connection.type=standalone`。本次只新增测试和报告，生产代码及原有工作区修改未改动。

测试环境：Windows、JDK 17.0.12、Maven 3.9.11、Docker 中的真实 Redis 6.2.17（镜像 `redis:6.2`），通过专用端口 `127.0.0.1:16379` 连接。测试使用独立容器 `codex-redis-fast-regression-20260903`；没有操作原有 6379 Redis 的数据。

实际链路使用生产 `RedisFastTimeseriesLatestDao`、生产 Lua、`TBRedisCacheConfiguration.redisTemplateString()`、Jedis 和真实 `CacheExecutorService`。历史数据库通过 mock 返回数据；装配、服务路由及实体查询分别由 Spring 轻量容器和单元测试覆盖。

最终 reactor 于 **2026-09-03 13:44:34 +08:00** 完成，耗时 4 分 15 秒。共运行 **115 项：105 通过、7 断言失败、3 Lua 执行错误、0 跳过**。其中 DAO 模块 111 项，common/cache 模块 4 项；10 个未通过用例归为下述 7 类问题。新建三份测试文件共增加 77 项用例。

| 测试类 | 总数 | 通过 | 失败 / 错误 | 覆盖内容 |
| --- | ---: | ---: | ---: | --- |
| `RedisFastTimeseriesLatestDaoTest` | 47 | 37 | 7 / 3 | 真实 Redis 的单写、批写、HGET、HMGET、HGETALL、HKEYS、删除、历史回填、Lua 缓存恢复 |
| `RedisFastLatestConfigurationTest` | 13 | 13 | 0 / 0 | backend 互斥装配、环境变量解析、实际注入字符串模板及四类序列化器 |
| `BaseTimeseriesServiceRedisFastTest` | 17 | 17 | 0 / 0 | 历史与 latest 混合写、批读写路由、版本位置、异步确认、错误传播、空输入 |
| `BaseTimeseriesServiceBatchTest` | 4 | 4 | 0 / 0 | 既有批写回归 |
| `BaseTimeseriesServiceEdqsTest` | 7 | 7 | 0 / 0 | 既有 EDQS 通知回归（mock DAO） |
| `TsLatestAwareEntityQueryDaoTest` | 23 | 23 | 0 / 0 | latest 查询、筛选、排序与实体查询适配 |
| `TBRedisCacheConfigurationTest` | 4 | 4 | 0 / 0 | 已有 Lua/hash 字符串序列化修复回归 |

已通过的真实 Redis 场景包括：

- boolean 真/假、long 两端极值、double 小数/指数/负零、字符串、JSON、null 的写入与四条读路径；中文、emoji、`|`、`:`、换行、NUL、空字符串及约 300 KB UTF-8 字符串。
- 混合类型批量写入；原生 Redis HSET 与 DAO 互读；单字段拒绝旧时间戳、接受相同时间戳及新时间戳；混合 stale/equal/new/新增字段的计数与最终值。
- 批读保留请求顺序和重复 key，为缺失字段返回 null 占位；空操作不创建键；不同实体 ID / 实体类型隔离，跨实体测点去重。
- 200、1,000 字段批量完整读回；500 个异步批次随机顺序写入两个字段，最终保持最大时间戳。
- 正常删除使用 `[startTs, endTs)`，不影响其他字段；不存在字段删除幂等；历史为空不回填；历史回填不能覆盖期间到达的新值。
- `SCRIPT FLUSH` 后写入、全量读取和删除可自动重新加载 Lua；WRONGTYPE 错误由 Future 传播，纠正键类型后可重新写读。
- 损坏字段在单读、Optional 读及批读中失败，全量读取跳过损坏字段并保留正常字段；合法的 `device:ts` 测点可正常读写枚举。

已复现的问题如下。计数中的断言失败与 Lua 执行错误均表示回归未通过；不把执行错误算作跳过。

| 优先级 | 场景与实际结果 | 影响与定位 |
| --- | --- | --- |
| P1 | 同批同 key 按 `[ts=200,value=1]`、`[ts=100,value=2]` 写入，最终保存 `ts=100`；固定随机种子基准也出现预期 488、实际 383 | 优化脚本先 HMGET 整批旧快照，批内重复 key 没有与前一条已接受值比较。`AbstractFastTimeseriesLatestDao.java:106–128` |
| P1 | 4,000、5,000、10,000 个不同字段的批写均抛出 `too many results to unpack` | `unpack(fields)` / `unpack(out)` 超过 Redis Lua 参数栈限制，整次写入未能正常完成。上述数字是实测样本，不声称已测得精确阈值。`AbstractFastTimeseriesLatestDao.java:110,127` |
| P1 | 删除先读取 `ts=100`，其后写入 `ts=300`，执行 `[100,200)` 删除仍把 300 删除，读回 null 占位 | HGET 和无条件 HDEL 分两次执行，存在检查与使用之间的竞态。旧 Redis 实现也存在此风险。`AbstractFastTimeseriesLatestDao.java:270–293` |
| P2 | 已保存 `100|` 的 null 值，在 `[100,200)` 范围内删除返回 false，字段仍存在 | 删除错误地用 `getValue()!=null` 判断是否应删除真实记录。`AbstractFastTimeseriesLatestDao.java:272` |
| P2 | 删除后回填历史 `ts=50`，Redis 中数据正确，但结果 `version=1` | 单条 save 返回接受计数 0/1，回填把计数作为版本返回，与 Redis 批写采用时间戳版本的契约不同。`AbstractFastTimeseriesLatestDao.java:169–170,308–309` |
| P2 | 使用真实旧 `RedisClusterTimeseriesLatestDao` 写入后，fast 可读值，但 key 枚举返回 `temperature` 和 `temperature:ts` | 旧格式元数据作为遥测字段暴露。全量读取会跳过该裸时间戳并记录警告。`AbstractFastTimeseriesLatestDao.java:325–334` |
| 切换限制 | 使用真实旧 `RedisTimeseriesLatestDao` 写入后，直接切换 fast 读不到已有最新值 | 旧键为 `ts:latest:DEVICE:<UUID>`，fast 为 `ts:{DEVICE<UUID>}:data`。这是键空间迁移限制，不能仅以值编码相同就认定原地兼容；本次未执行数据迁移。 |

对应失败用例为：

- `duplicateFieldsWithinBatchNeverRegress` 的降序输入，以及 `randomizedBatchesMatchSequentialLatestReference`。
- `largeBatchesPreserveEveryField` 的 4,000、5,000、10,000 参数。
- `deletionDoesNotRemoveNewerConcurrentWrite`、`deletesStoredNullValue`。
- `rewriteRestoresHistoricalValueAndTimestampVersion`。
- `readsLegacyClusterDataWithoutLeakingTimestampMetadata`。
- `switchingFromLegacyStandaloneRedisKeepsExistingLatestVisible`。

修复方向：在 Lua 中记录本批已接受的每字段最新值；在同一个 Lua 执行中分段 HMGET/HSET，以保留对外原子性并避免 unpack 超限；把删除区间判断和删除放在同一个 Lua 中。元数据兼容处理需要区分裸时间戳和合法的 `name:ts` 遥测字段。迁移以及版本/EDQS 通知语义需要单独设计，不能通过简单改变 Redis 键前缀或把版本替换为历史时间戳解决所有问题。

额外代码审查发现：普通删除返回 null 版本，`LatestTsKv` 将其映射为 0，而 EDQS `VersionsStore` 会拒绝小于已有版本的事件；上层对历史回填结果仍发送删除事件。此风险也涉及原有 Redis 路径。已通过的 mock EDQS 测试不代表真实 EDQS 删除及历史回填端到端链路已通过。

执行与复现说明：

必须使用当前源码的依赖模块。初次单独运行 `mvn -pl dao test` 时，本机 Maven 缓存包含旧 `common/cache` 和旧 `common/dao-api`，分别造成 hash 读取异常和 backend 装配假阳性；这些结果不纳入最终源码缺陷计数。最终使用 `-am` reactor 统一引用当前模块。开发环境出现的测试 fixture 循环依赖和缺少 poolSize 属性也已经修正。

在项目根目录用专用测试 Redis 执行：

```powershell
docker run --detach --rm --name codex-redis-fast-regression-20260903 --publish 127.0.0.1:16379:6379 redis:6.2

mvn -o -pl dao -am '-Dtest=RedisFastTimeseriesLatestDaoTest,RedisFastLatestConfigurationTest,BaseTimeseriesServiceRedisFastTest,BaseTimeseriesServiceBatchTest,BaseTimeseriesServiceEdqsTest,TsLatestAwareEntityQueryDaoTest,TBRedisCacheConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dredis.fast.test.port=16379' '-Dlicense.skip=true' test

docker stop codex-redis-fast-regression-20260903
```

上述 `-o` 使用已有 Maven 依赖缓存；新环境需要先下载依赖。`license.skip=true` 仅跳过与此次回归无关、会扫描工作区已有无许可证文件的 license 检查，未跳过选中的测试。省略 `redis.fast.test.port` 时，DAO 测试默认通过 Testcontainers 启动独立 Redis。外部 Redis 必须为专用测试实例，因为用例会清理本次 UUID 实体键并刷新服务器 Lua 脚本缓存。

原始证据保留于：

- `dao/target/redis-fast-regression-reactor.log`。
- `dao/target/surefire-reports/TEST-org.thingsboard.server.dao.timeseries.fast.RedisFastTimeseriesLatestDaoTest.xml` 及对应 `.txt`。
- `dao/target/surefire-reports/` 中其余五类相关 DAO 报告，以及 `common/cache/target/surefire-reports/` 中序列化报告。

范围限制：本轮针对当前 standalone 配置。Redis Cluster/Sentinel 实际拓扑、TLS/ACL、真实网络中断与重连、持久化重启、真实历史数据库以及 HTTP/MQTT/规则链/EDQS 全链路未运行；`SCRIPT FLUSH` 恢复和 WRONGTYPE 处理不等同于网络故障恢复。存在已复现的数据正确性问题，因此本轮不能作为发布通过凭据。
