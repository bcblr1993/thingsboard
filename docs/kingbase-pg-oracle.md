# ThingsBoard 金仓（KingbaseES V8）适配 —— pg / oracle 兼容模式

> 适配版本：KingbaseES **V008R006C009B0014**（内核 PostgreSQL 12）
> 代码基线：`feature/kingbase-V008R006C009B0014-pg-oracle`，基于 `origin/master` (`ee23fccb`)
> 范围：**pg 与 oracle 两种兼容模式**。mysql 兼容模式不在本期范围，原因见第六节。

## 一、结论先行

| 兼容模式 | Java 代码改动 | 需要做什么 |
|---|---|---|
| **pg** | **无** | 按 PostgreSQL 常规方式配置连接串即可 |
| **oracle** | **无** | 连接串加 `ora_input_emptystr_isnull=off`；安装程序自动执行一个 addon 脚本 |

本期没有修改任何运行期 Java 代码，也没有改动任何 SQL 查询、Hibernate 方言或 schema 脚本。
对现有 PostgreSQL 部署的影响为零 —— 产品代码在 PostgreSQL 路径上一个字节都没有变化。

新增内容只有三项：安装期的模式探测逻辑（`@Profile("install")` 内，运行期不加载）、
一个金仓 oracle 专用 addon 脚本、以及配置注释与本文档。

## 二、部署配置

### pg 兼容模式

```bash
SPRING_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:54321/<db>
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<pass>
```

用 PostgreSQL 官方驱动（TB 自带，无需额外安装），方言沿用 `ThingsboardPostgreSQLDialect`。

### oracle 兼容模式

```bash
SPRING_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:54321/<db>?options=-c%20ora_input_emptystr_isnull%3Doff'
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<pass>
```

**`ora_input_emptystr_isnull=off` 不可省略。** 金仓 oracle 兼容模式默认把空串转为 NULL（Oracle 语义），
会导致空串写入 `NOT NULL` 列报错、唯一性判定失效、`COALESCE` 与 `= ''` 的结果与 PostgreSQL 不一致。
该参数为 user 级，只能经连接串传入。

### 安装账号权限（仅 oracle 模式）

oracle 模式需要安装一个补丁函数到 `sys` 模式，因此安装账号需要 `sys` 的 CREATE 权限。二选一：

1. 用金仓 DBA 账号（如 `system`）执行安装 —— 默认满足，无需额外操作；
2. 用普通应用账号，由 DBA 预先授权一次：
   ```sql
   GRANT CREATE ON SCHEMA sys TO <应用账号>;
   ```

权限不足时安装会**直接中断并打印修复指引**，不会静默跳过。

## 三、oracle 模式为什么需要 addon

金仓 oracle 兼容模式在 `sys` 模式下只注册了二元的 `sys.concat(text, text)`，
而 `sys` 在函数名解析时硬性优先于 `pg_catalog`（调整 `search_path` 无效）。
于是 TB 原生查询中的 `concat(a, b, c)` 被解析到 `sys.concat` 并报错：

```
ERROR: function sys.concat(unknown, varchar, unknown) does not exist
```

实测该错误在完整 dao 测试套件中出现 **2740 次**，导致 **448 个用例失败**。

| 兼容模式 | `sys.concat` | `pg_catalog.concat` |
|---|---|---|
| pg | 不存在 | `VARIADIC "any"` |
| oracle | 仅 `(text, text)` | `VARIADIC "any"`（存在，但被 `sys` 遮蔽） |

解法是在 `sys` 下补一个 VARIADIC 重载，转发给依然存在的 PostgreSQL 原生实现：

```sql
CREATE OR REPLACE FUNCTION sys.concat(VARIADIC args text[]) RETURNS text AS $$
  SELECT pg_catalog.concat(VARIADIC args);
$$ LANGUAGE sql IMMUTABLE;
```

语义与 PostgreSQL 完全一致（含 NULL 参数被忽略的行为），二元调用仍走金仓原生实现不受影响。
用 VARIADIC 而非固定三参，可覆盖任意参数个数，上游将来新增四参 `concat` 时无需再改。

脚本位于 `dao/src/main/resources/sql/schema-entities-kingbase-oracle-addon.sql`（打包时随其他 schema 脚本一起进入安装包的 `data/sql/` 目录），
由 `SqlEntityDatabaseSchemaService.applyKingbaseOracleAddonIfNeeded()` 在安装期
按「探测 → 幂等检查 → 权限预检 → 执行 → 结果复验」的顺序自动完成，任一步不通过都会中断安装。

金仓识别依据是金仓独有的 `database_mode` 参数，而非 JDBC 产品名 ——
用 PostgreSQL 官方驱动接入金仓时产品名返回的是 `PostgreSQL`，按产品名判断会漏判。
该查询在 PostgreSQL 上返回 NULL，因此金仓分支在 PostgreSQL 部署中根本不会进入。

## 四、验证结果

完整 dao 测试套件（950 个用例）在四个环境各跑一轮，以 PostgreSQL 15.4 为基线逐项比对：

| 环境 | 代码 | Tests | Failures | Errors | 与基线差异 |
|---|---|---|---|---|---|
| PostgreSQL 15.4 | 基线 | 950 | 4 | 6 | — |
| 金仓 pg 模式 | 无改动 | 950 | 4 | 6 | **失败清单逐项一致** |
| 金仓 oracle 模式 | 无改动、无 addon | 950 | 128 | 320 | +441 |
| 金仓 oracle 模式 | 无改动、**有 addon** | 950 | 4 | 6 | **失败清单逐项一致** |

四个环境共有的失败均与金仓无关，分别是：`topology_template` 表由自定义升级脚本创建而测试库只装基础 schema（3 个）、
Redis 测试镜像无法拉取（3 个）、性能测试超时（1 个）、校验消息已中文化而断言仍为英文（1 个）、
NoSql 时序断言（1 个）、自定义实体类型未注册 `EntityDaoService`（1 个）。

另有安装期逻辑的单元测试 9 个全部通过，覆盖非金仓跳过、pg/mysql 模式跳过、
重复安装幂等、无权限中断、正常安装、执行后未生效中断等分支。

## 五、对 PostgreSQL 的影响

实测为零：用 master 原始代码与本分支代码在同一个 PostgreSQL 15.4 上各跑一轮，失败清单完全一致。
本期产品代码的改动只有安装期的模式探测，位于 `@Profile("install")` 内，运行期不加载；
探测查询在 PostgreSQL 上返回 NULL，所有金仓分支都不会进入。

## 六、mysql 兼容模式为何不在本期

mysql 兼容模式存在两类无法通过 addon 消除的差异：

1. **`||` 被解释为逻辑 OR 而非字符串拼接**，且不报错、只是静默返回错误结果
   （裸用于 `ILIKE` 右侧因优先级恒真，经 Hibernate 渲染带 `::text` 则恒假）。
   这需要改写查询与 Hibernate 方言，改动面远大于本期。
2. **强制大小写不敏感**（`enable_ci=on`，`initdb` 时固化，不可在线修改，`COLLATE` 也覆盖不了）。
   设备名 `CaseDev-A` 与 `casedev-a` 会被唯一约束判为重复，属于业务语义变更，需业务侧确认。

此外 mysql 模式下 `concat` 遇 NULL 参数返回 NULL（MySQL 语义），
会使 `searchText` 为 null 的搜索静默返回空结果，该问题同样存在于上游原始代码。

**对大小写敏感有要求的场景，请使用 pg 或 oracle 兼容模式。**

## 七、回归测试方法

测试数据源已参数化，可将同一套用例指向外部数据库：

```bash
# 金仓 pg 模式
mvn test -pl dao -DTB_TEST_DB_DRIVER=org.postgresql.Driver \
  -DTB_TEST_DB_URL='jdbc:postgresql://<host>:54321/<db>' \
  -DTB_TEST_DB_USERNAME=<user> -DTB_TEST_DB_PASSWORD=<pass>

# 金仓 oracle 模式（测试库需先执行 addon 脚本）
mvn test -pl dao -DTB_TEST_DB_DRIVER=org.postgresql.Driver \
  -DTB_TEST_DB_URL='jdbc:postgresql://<host>:54321/<db>?options=-c%20ora_input_emptystr_isnull%3Doff' \
  -DTB_TEST_DB_USERNAME=<user> -DTB_TEST_DB_PASSWORD=<pass>
```

不设置 `TB_TEST_DB_*` 时仍是原有的 Testcontainers + PostgreSQL 16.6 行为，与改造前完全一致。
外部数据库不会触发 Testcontainers 的 `TC_INITFUNCTION`，需先按 `PostgreSqlInitializer` 中的脚本顺序初始化测试库。
