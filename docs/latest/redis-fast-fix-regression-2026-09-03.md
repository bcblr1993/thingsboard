# redis-fast 两项修复与复测记录（2026-09-03）

按用户确认，本轮只修复 Lua 大批次 unpack 超限和并发删除误删新值，批写每轮处理 **3000 个测点**。批内重复测点、null 删除语义、历史回填版本和旧数据兼容问题不在本轮修复范围。

结论：约定范围内复测通过。真实 Redis 6.2.17 standalone，JDK 17.0.12，使用 Maven reactor 编译当前源码及依赖。2026-09-03 14:22:29 +08:00 完成，耗时 2 分 43 秒，`BUILD SUCCESS`。

共计 **126 项：120 通过、0 失败、0 错误、6 跳过**。其中 DAO 模块 122 项，common/cache 模块 4 项。6 项跳过均为用户明确排除的诊断测试，使用 `@Disabled` 保留原断言及原因；不表示这些问题已经修复。

| 测试类 | 通过 | 跳过 |
| --- | ---: | ---: |
| `RedisFastTimeseriesLatestDaoTest` | 52 | 6 |
| `RedisFastLatestConfigurationTest` | 13 | 0 |
| `BaseTimeseriesServiceRedisFastTest` | 17 | 0 |
| `BaseTimeseriesServiceBatchTest` | 4 | 0 |
| `BaseTimeseriesServiceEdqsTest` | 7 | 0 |
| `TsLatestAwareEntityQueryDaoTest` | 23 | 0 |
| `TBRedisCacheConfigurationTest` | 4 | 0 |

实现位于 [AbstractFastTimeseriesLatestDao.java](/D:/project/thingsboard-4.1/dao/src/main/java/org/thingsboard/server/dao/timeseries/fast/AbstractFastTimeseriesLatestDao.java)，回归用例位于 [RedisFastTimeseriesLatestDaoTest.java](/D:/project/thingsboard-4.1/dao/src/test/java/org/thingsboard/server/dao/timeseries/fast/RedisFastTimeseriesLatestDaoTest.java)。实现由 Redis/Valkey fast DAO 共用，本轮实际服务端验证使用 Redis。

**3000 点分段批写**

Java 保持一次脚本调用，Lua 内每轮取最多 3000 个字段，通过 HMGET 读取、时间戳守卫筛选后，以 HSET 写回。每次 unpack 最多展开 3000 个读取字段、6000 个字段/值参数。各段接受数量累计返回；整段均被时间戳守卫拒绝时不执行 HSET。全部分段在同一个 Lua 执行中完成，分段之间不会插入其他客户端命令。

测试通过 Redis `INFO commandstats` 前后差值断言实际 HMGET/HSET 数量，并完整读回所有字段。所有字段通过守卫时的结果如下：

| 测点数 | HMGET 次数 | HSET 次数 |
| ---: | ---: | ---: |
| 200 / 1000 / 2999 / 3000 | 1 | 1 |
| 3001 / 4000 / 5000 / 5999 / 6000 | 2 | 2 |
| 6001 | 3 | 3 |
| 10000 | 4 | 4 |

另验证 6001 点中间一整段为旧数据：实际读取 3 次、写入 2 次、返回接受数量 3001，所有测点值及时间戳正确；全部为旧数据时返回 0，HSET 次数为 0。

**原子条件删除**

将读取字段、检查时间戳半开区间 `[startTs, endTs)` 和 HDEL 合并为一个 Lua 脚本。Java 根据脚本实际删除数量设置 `removed`，只有确实删除且请求回填时才查询历史数据。已有 null 值处理方式予以保留。

测试把并发写入注入点放到 Redis 删除脚本执行位置，不再依赖原先 Java 预读的方法：

- 先写入 ts=300，再执行 `[100,200)` 删除：返回 `removed=false`，300 保留，不触发历史回填。
- 先删除区间内的 ts=100，再写入 ts=300：返回 `removed=true`，300 保留。
- 时间戳 99 / 100 / 150 / 199 / 200 分别验证起点包含、终点排除；其他字段不受影响。
- 不存在字段返回 false；null 与空字符串保持各自原有行为；损坏时间戳及 WRONGTYPE 通过 Future 报错。
- 实际删除后可恢复历史数据，回填期间到达的新值不会被历史值覆盖。

常规五种类型读写、UTF-8 序列化、批量读取顺序、实体隔离、异步并发写入、Lua 缓存丢失后重新加载、Spring 装配及上层服务/查询回归同时通过。

复现命令（项目根目录；外部 Redis 必须是专用测试实例）：

```powershell
docker run --detach --rm --name codex-redis-fast-fix-20260903 --publish 127.0.0.1:16379:6379 redis:6.2

mvn -o -pl dao -am '-Dtest=RedisFastTimeseriesLatestDaoTest,RedisFastLatestConfigurationTest,BaseTimeseriesServiceRedisFastTest,BaseTimeseriesServiceBatchTest,BaseTimeseriesServiceEdqsTest,TsLatestAwareEntityQueryDaoTest,TBRedisCacheConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dredis.fast.test.port=16379' '-Dlicense.skip=true' test

docker stop codex-redis-fast-fix-20260903
```

`-o` 使用本机现有依赖缓存；`-am` 避免使用已过期的项目模块 jar。`license.skip=true` 仅跳过此前受工作区其他文件影响的 license 扫描，选中的测试照常运行。测试清理本次 UUID 实体键，脚本恢复用例会刷新专用服务器的 Lua 缓存。

原始日志为 [redis-fast-fix-regression.log](/D:/project/thingsboard-4.1/dao/target/redis-fast-fix-regression.log)，XML/TXT 报告位于 `dao/target/surefire-reports/` 及 `common/cache/target/surefire-reports/`。初轮失败与未纳入修复的事项保留在 [初轮回归记录](/D:/project/thingsboard-4.1/docs/latest/redis-fast-regression-2026-09-03.md)。
