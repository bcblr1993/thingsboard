# IoTDB 写路径代码走读

> 配套《IoTDB技术设计文档》§4。设计文档讲**为什么这样设计**，本文讲**代码到底怎么跑的**——
> 跟着一条遥测消息从规则引擎走到 IoTDB，逐行解释每段代码在做什么、为什么必须这么写。
>
> 主体代码：`dao/src/main/java/org/thingsboard/server/dao/timeseries/iotdb/IotdbTimeseriesWriteBuffer.java`

---

## 0. 先建立全局图景

### 0.1 两类线程的分工

```
规则引擎线程（生产者，几十~几百个）        shard 线程（消费者，固定 N 个 = write.shards）
   IotdbBaseTimeseriesDao.saveBatch            run() 死循环:
        → buffer.addBatch()                       drain 队列 → 攒批 → flush 到 IoTDB
             │                                          ▲
             └──── ConcurrentLinkedQueue ───────────────┘
                      (无锁, 每 shard 一个)
```

**生产者只负责入队，绝不碰 IoTDB**（否则规则引擎线程会被网络 IO 阻塞）；
**shard 线程独占攒批与发送 RPC**。二者通过无锁队列交接，是典型的生产者-消费者解耦。

### 0.2 为什么必须有这一层缓冲

ThingsBoard 的 DAO 接口是**逐点**的（一次一个 `TsKvEntry`），而 IoTDB 的性能几乎全部来自
**Tablet 批量接口**。4000 设备 × 每秒 150 个测点 ≈ **60 万次/秒**的逐点调用，若逐点发 RPC，
IoTDB 会被瞬间打爆。

写缓冲的全部工作，就是把"逐点流"重组成 IoTDB 喜欢的形态：

> **每设备一个对齐 Tablet（二维表），多设备再合并成一次 RPC。**

### 0.3 核心数据结构一览

| 类 | 作用 | 生命周期 |
|---|---|---|
| `WritePoint` | 一个待写的点：`(measurement, ts, type, value, dataPoints)` | 入队到落库 |
| `WriteBatch` | 入队单元：`(device, List<WritePoint>, completion)` | 入队到被 shard 取走 |
| `BatchCompletion` | **一条遥测消息一个**，持有唯一的 `SettableFuture` | 消息全部点结算完毕 |
| `DeviceBatch` | **一个设备一个**，把散点攒成二维表 | 一个 flush 窗口内 |
| `Shard` | 一个消费线程 + 一个队列 + 一份 `pending` | 进程生命周期 |

---

## 1. 入口：`addBatch()`

```java
public ListenableFuture<Integer> addBatch(String device, List<WritePoint> points, int dataPoints) {
    if (points.isEmpty()) { ... return 立即完成的 future(0); }
    int pointCount = points.size();
    BatchCompletion completion = new BatchCompletion(pointCount, dataPoints);   // ①
    if (!running) { ... }                                                       // ②
    int s = (device.hashCode() & 0x7fffffff) % shards;                          // ③
    Shard target = shard[s];
```

### ① 一条消息一个 Future

`BatchCompletion` 内部是 `remaining = pointCount` 的**倒计数器**。规则引擎拿到的
`completion.future` 会在**这条消息的所有点都结算完毕后**才完成。

> 早期版本是"一个点一个 Future"，高扇出场景下 Future/回调的分配与唤醒开销很大。
> 现在改成消息级，规则引擎只需 ack 一次。
>
> 单点写入的 `add()` 只是一层包装：`addBatch(device, List.of(单个点), dataPoints)`，语义完全一致。

### ② 停机后拒收

`running` 是 `volatile boolean`，`stop()` 会把它置 false。此处**必须拒收**：shard 线程的退出
条件是"停了 **且** 队列空 **且** pending 空"，如果线程恰在"队列空"的瞬间判定退出，之后再入队
的点就**永远没人消费**，其 Future 永不完成 → 上游 Kafka offset 提交不了、停机被卡死。

### ③ 分片路由

```java
int s = (device.hashCode() & 0x7fffffff) % shards;
```

- `& 0x7fffffff` 是**抹掉符号位**：`hashCode()` 可能返回负数，负数取模会得到负索引 → 数组越界。
- **同一 device 恒定落在同一 shard**，这是整个设计的基石：
  单设备的数据只被一个线程碰，因此 `DeviceBatch` 的攒批过程**完全不需要加锁**。

---

## 2. 背压与分段入队

```java
int offset = 0;
while (offset < pointCount) {
    int chunkCount = Math.min(maxPendingPerShard, pointCount - offset);          // ①
    if (running && (long) target.size.get() + chunkCount > maxPendingPerShard) { // ②
        stats.backpressureEvents.increment();
        long deadlineNanos = maxBackpressureWaitMs > 0 ? System.nanoTime() + ... : 0L;
        while (running && (long) target.size.get() + chunkCount > maxPendingPerShard) {
            if (deadlineNanos > 0L && System.nanoTime() >= deadlineNanos) {      // ③
                int rejected = pointCount - offset;
                completion.fail(rejected, new RuntimeException("backpressure wait exceeded ..."));
                stats.failedPoints.add(rejected);
                return completion.future;
            }
            LockSupport.parkNanos(100_000);   // 自旋等 100µs
        }
    }
    ...
}
```

### ① 为什么要"分段"

假设水位 `maxPendingPerShard = 100`，来了一条 **150 个点**的消息。
若整条消息一起参与判断，条件 `0 + 150 > 100` **永远成立**——即使分片是空的，也会一直等到超时。

分段后切成 `100 + 50` 两段，每段都能满足水位条件，随着 flush 推进逐段入队。
**所有段共享同一个 `completion`**，因此对上游依然是"一条消息一个 Future"。

> 构造函数里有 `if (maxPendingPerShard <= 0) throw ...` 的校验，正是为这段服务：
> 若水位为 0，`Math.min(0, ...)` 恒为 0，`offset` 永不前进 → 死循环。

### ② 背压的本质：`size` 何时加、何时减

`Shard.size` 的语义是"**已接受但尚未落库**的点数"：

| 时机 | 动作 | 代码位置 |
|---|---|---|
| 入队时 | `size += chunkCount` | `addBatch` |
| **出队时** | **不减！** | `run()` 循环里有注释特意标明 `size is NOT decremented here` |
| flush 完成（成功或失败） | `size -= batch.pointCount()` | `onFlushed` |
| 类型漂移丢点 | `size--` | `accumulate` |
| 停机清队 | `size -= count` | `stop()` |

**出队不减是刻意的**：点从队列取出后会进入 `pending`（还在内存里攒着），如果这时就减掉，
背压就漏掉了"已出队但未落库"的这部分，内存上界失效。

因此即使 IoTDB 完全无响应、所有 flush 卡死，`size` 也降不下来 → 生产者被 park →
**内存被硬性锁死在 `shards × maxPendingPerShard`**，绝不会无限缓冲直至 OOM。
这就是"背压如实传导到 Kafka"——lag 会涨，但数据不丢、可恢复。

### ③ 背压硬上限（可选）

`max_backpressure_wait_ms = 0`（默认）时无限等，反压如实传导；
`> 0` 时超时即快速失败该点、释放调用线程——代价是丢该点，收益是规则引擎线程不被长期占用。
生产建议保持 0（可恢复不丢数），除非明确希望"宁可丢点也不能卡住规则引擎"。

---

## 3. 入队与停机竞态（最微妙的一处）

```java
List<WritePoint> chunk = new ArrayList<>(points.subList(offset, offset + chunkCount));
stats.addedPoints.add(chunkCount);
target.size.addAndGet(chunkCount);
WriteBatch enqueued = new WriteBatch(device, chunk, completion);
target.queue.add(enqueued);
offset += chunkCount;

// 入队后复核
if (!running && target.queue.remove(enqueued)) {
    completion.fail(chunkCount, bufferStoppedEx(device));
    target.size.addAndGet(-chunkCount);
    stats.failedPoints.add(chunkCount);
}
```

**要解决的竞态**：`stop()` 可能恰好发生在"我入队"与"线程退出"之间，此时这个批次无人消费。

**解法**利用 `ConcurrentLinkedQueue` 的一个性质：
> `queue.remove(x)` 与消费端的 `queue.poll()` 对**同一个节点是互斥的**——只有一方能拿到它。

于是：

| `remove` 结果 | 含义 | 谁负责结算 |
|---|---|---|
| **成功** | shard 线程还没取走 | **本线程**：失败 Future + 归还 `size` |
| **失败** | shard 线程已经取走 | **shard 线程**：走正常 `complete/fail` → `onFlushed` |

**恰好一方处理，不重不漏。**

> 这里曾有一个真实缺陷：早期版本无条件 `setException + size--`，不检查线程是否已取走。
> 结果当线程已经取走并成功写入 IoTDB 时，会出现
> **"数据已落库，却告诉上游失败"**（RETRY 下重复写）+ **`size` 被减两次变成负数**（统计失真）。
> 现在的 `remove` 互斥版本彻底消除了这个窗口。

---

## 4. shard 线程主循环

```java
public void run() {
    while (running || !queue.isEmpty() || !pending.isEmpty()) {          // ①
        try {
            int drained = 0;
            WriteBatch batch;
            while ((batch = queue.poll()) != null) {
                accumulate(batch);
                drained += batch.points().size();
                if (drained >= 8192) break;                               // ②
            }
            long now = System.currentTimeMillis();
            if (now - lastFlush >= flushIntervalMs) { flushAll(); lastFlush = now; }
            if (drained == 0 && running) LockSupport.parkNanos(200_000);
        } catch (Throwable t) {
            log.error("[IoTDB] shard loop error (thread kept alive)", t);  // ③
        }
    }
    flushAll();                                                           // ④
}
```

### ① 退出条件

`running || !queue.isEmpty() || !pending.isEmpty()` —— 必须"停了 **且** 队列空 **且** pending 空"
才退出。保证停机时把**已接受的数据全部写完**，正常发布不丢数。

### ② 单轮排空上限 8192

若不设上限，高负载下线程会一直忙于排空队列而**迟迟不去 flush**，攒批窗口被无限拖长、
端到端延迟失控。8192 个点后强制跳出，去检查是否该 flush。

### ③ `catch (Throwable)` 是保命的

**线程一旦死亡，后果是灾难性的**：
- 该分片**永久停写**；
- `size` 再也不会被释放 → **背压永久阻塞**所有路由到该分片的规则引擎线程。

所以任何未预期异常都只记日志，**绝不允许异常逃逸杀死线程**。这是最后一道保险。

### ④ 退出前最后 flush

排干 `pending` 里最后一批攒着的数据。

### 双触发 flush 机制

| 触发条件 | 代码 | 作用 |
|---|---|---|
| 设备行数 ≥ `batch_size`(默认 1000) | `accumulate` 里 `flushOne` | 高频设备不憋大批次 |
| 距上次 flush ≥ `flush_interval_ms`(默认 1000ms) | `run()` 里 `flushAll` | 约束端到端延迟上界 |

> **flush 窗口是变化上报形态下的第一吞吐杠杆**：窗口越大 → 每设备 tablet 行数越多 →
> 服务端每点 CPU 成本越低。实测 50ms→2000ms 把拐点从 45 万点/秒推到 ≥68 万点/秒（+50%）。
> 代价是崩溃丢数窗口同步变大，且规则引擎 `pack_processing_timeout` 必须 > flush 窗口。

---

## 5. 核心：`DeviceBatch.add()` —— 把散点拼成二维表

### 5.1 要解决的问题

IoTDB 的 `Tablet` 本质是一张**二维表**：行 = 时间戳，列 = 测点，且必须先知道"有哪些列"
才能填数据。而 TB 喂进来的是**一个一个孤立的点**：

```
("temp", ts=1000, DOUBLE, 25.5)
("hum",  ts=1000, INT64,  60)
("temp", ts=2000, DOUBLE, 26.1)
```

`add()` 的职责就是**边来边拼**——来第一个点时，你并不知道后面会有多少列、多少行。

### 5.2 四个字段的分工

```java
Map<String,Integer> columnIndex   // "temp"→0, "hum"→1        : 测点名 → 列号
List<MeasurementSchema> schemas   // [temp:DOUBLE, hum:INT64]  : 每列的名字+类型
LinkedHashMap<Long,Object[]> rows // 1000→[25.5,60], 2000→[26.1,null] : ts → 整行
int pointCount                    // 本批点数（背压记账用）
```

**关键：`columnIndex` 与 `schemas` 共用同一套下标。**
`columnIndex.get("hum") == 1` ⟹ `schemas.get(1)` 是 hum 的 schema ⟹ `row[1]` 是 hum 的值。
三者靠"列号"串起来。

### 5.3 逐点走一遍

#### 点 ①：`("temp", ts=1000, DOUBLE, 25.5)`

```java
Integer col = columnIndex.get("temp");   // null —— 从没见过
if (col == null) {
    col = schemas.size();                // = 0
    columnIndex.put("temp", 0);
    schemas.add(cachedSchema("temp", DOUBLE));
}
```

> **`col = schemas.size()` 是关键技巧**：列号就等于"当前已有多少列"。
> 第一个新列拿 0，第二个拿 1……列号天然连续，且与 `schemas` 下标严格对齐。

```java
Object[] row = rows.computeIfAbsent(1000L, t -> new Object[Math.max(8, 1)]);
// ts=1000 不存在 → 新建长度 8 的数组（预留，避免立刻扩容）
row[0] = 25.5;
pointCount = 1;
```

状态：
```
columnIndex: {temp:0}
schemas:     [temp:DOUBLE]
rows:        {1000 → [25.5, _, _, _, _, _, _, _]}
```

#### 点 ②：`("hum", ts=1000, INT64, 60)` —— 合并的精髓

```java
col = columnIndex.get("hum");    // null → 新列
col = schemas.size();            // = 1
columnIndex.put("hum", 1); schemas.add(hum:INT64);

row = rows.computeIfAbsent(1000L, ...);   // ts=1000 已存在 → 拿到同一个数组！
row[1] = 60;
```

```
rows: {1000 → [25.5, 60, _, _, _, _, _, _]}   ← 两个点合并进同一行的两列
```

**这就是"按 (device, ts) 合并"**：同一时刻的第二个测点没有新建行，而是填进同一行的第 2 列。

> 这里也是**"历史 + latest 双写合并"的落点**：历史 DAO 和 latest DAO 对同一
> `(device, ts, key)` 各 add 一次，第二次只是覆写同一个格子 —— **物理上只写一次**。

#### 点 ③：`("temp", ts=2000, DOUBLE, 26.1)`

```java
col = columnIndex.get("temp");   // = 0，已知列，直接复用（不再新建）
// 类型校验：schemas.get(0).getType()==DOUBLE == p.type ✓
row = rows.computeIfAbsent(2000L, t -> new Object[Math.max(8, 2)]);   // 新行
row[0] = 26.1;
```

```
rows: {1000 → [25.5, 60  , ...],
       2000 → [26.1, null, ...]}     ← hum 列留 null = 稀疏
```

**稀疏是常态**（30% 变化率场景下每行测点集合都不同），不是异常。null 会在 `toTablet` 里
被 bitmap 标记为"该格无值"。

#### 点 ④：`("temp", ts=2000, TEXT, "err")` —— 类型漂移

```java
col = columnIndex.get("temp");                     // = 0
else if (schemas.get(0).getType() != p.type) {     // DOUBLE != TEXT → 命中
    completion.drop(1, p.dataPoints);
    return false;                                   // 该点不入批
}
```

temp 这列已按 DOUBLE 建好，底层是 `double[]`。若放行，`toTablet` 里
`((double[])arr)[r] = (Double)"err"` 会抛 **ClassCastException**，导致**整个设备批次**
（含正常的 hum）全部失败。

所以在此拦下，**只丢这一个点**。返回 false 后，调用方 `accumulate` 会 `size--` 释放背压计数。

`drop(1, dataPoints)` 的语义是"**从这条消息的成功点数里扣掉，但不算失败**"——因为类型漂移是
确定性数据错误（IoTDB 序列类型不可变），重试一万次也不会成功。若让整条消息失败，
Kafka RETRY 队列会**无限重放同一条毒丸消息**，lag 永远消不掉。

### 5.4 两个容易看不懂的细节

#### ① `Math.max(8, schemas.size())` 与倍增扩容

```java
Object[] row = rows.computeIfAbsent(p.ts, t -> new Object[Math.max(8, schemas.size())]);
if (row.length <= col) {
    Object[] grown = new Object[Math.max(schemas.size(), row.length * 2)];
    System.arraycopy(row, 0, grown, 0, row.length);
    rows.put(p.ts, grown);
}
```

**行数组为什么会不够长？** 因为行是在"某个时刻"创建的，只按**当时**的列数分配；
而列是**后续还会增加**的。

例：ts=1000 的行创建时只有 2 列（分配长度 8）。之后该设备又上报了 10 个新测点，
列数涨到 12。此时若又有点要写进 ts=1000 的第 11 列 → `row.length(8) <= col(11)` → 必须扩容。

**为什么倍增而不是刚好扩够？** 设一台宽设备有 500 个测点：

| 策略 | 扩容次数 | 元素复制总量 |
|---|---|---|
| 每次 +1 | ~492 次 | 8+9+10+…+500 ≈ **12 万次** = O(n²) |
| **倍增** | 6 次（8→16→32→64→128→256→512） | 8+16+…+256 ≈ **500 次** = 摊还 O(1) |

这是 5000 设备 × 500 测点压测后的针对性优化。

#### ② `completionPoints` 为什么是 `IdentityHashMap`

```java
final Map<BatchCompletion, List<WritePoint>> completionPoints = new IdentityHashMap<>();
completionPoints.computeIfAbsent(completion, k -> new ArrayList<>()).add(p);
```

一个 `DeviceBatch` 里可能混着**多条不同遥测消息**的点（同一设备在一个 flush 窗口内上报 3 次，
就有 3 个 `BatchCompletion`）。这个 map 记录"**哪条消息贡献了哪些点**"，两个用途：

1. flush 成功时按消息分别结算：`completion.complete(该消息贡献的点数)`；
2. 类型冲突降级时（`splitByMeasurement`）能精确知道每个点属于哪条消息，
   从而只丢冲突列、其余列照常结算给对应消息。

**为什么用 Identity（按引用比较）而非普通 HashMap（按 equals）？**
两条不同的消息即使内容完全相同，也**必须**是两个独立的 Future，绝不能被合并。
`IdentityHashMap` 明确表达了这个语义，也避免将来有人给 `BatchCompletion` 添加
`equals/hashCode` 时意外踩坑。

### 5.5 最终产物

4 个点（1 个被丢）之后：

```
schemas: [temp:DOUBLE, hum:INT64]
rows:    {1000 → [25.5, 60  ],
          2000 → [26.1, null]}
pointCount: 3
```

**3 次 `save()` 调用 → 最终只是一次 RPC 里的一小块**。这就是吞吐的来源。

---

## 6. `toTablet()` —— 转成 IoTDB 的列式结构

```java
Tablet toTablet(String device) {
    Tablet tablet = new Tablet(device, schemas, rows.size());
    tablet.initBitMaps();
    BitMap[] bm = tablet.bitMaps;
    Object[] cellArrays = tablet.values;      // 列式：每列一个基本类型数组
    int r = 0;
    for (Map.Entry<Long, Object[]> e : rows.entrySet()) {
        tablet.addTimestamp(r, e.getKey());
        Object[] row = e.getValue();
        for (int c = 0; c < cols; c++) {
            Object v = c < row.length ? row[c] : null;      // ①
            if (v != null) {
                fillCell(cellArrays[c], schemas.get(c).getType(), r, v);   // ②
            } else {
                bm[c].mark(r);                                             // ③
                TSDataType ct = schemas.get(c).getType();
                if (ct == TEXT || ct == STRING) {
                    ((Binary[]) cellArrays[c])[r] = Binary.EMPTY_VALUE;    // ④ 关键!
                }
            }
        }
        r++;
    }
    tablet.rowSize = r;
    return tablet;
}
```

### ① 行数组可能比列数短

因为行创建后列还会增加（见 5.4①），所以 `c < row.length ? row[c] : null` —— 越界即视为 null。

### ② `fillCell` 直写类型数组

```java
case DOUBLE: ((double[]) colArray)[r] = (Double) v; break;
case INT64:  ((long[])   colArray)[r] = (Long)   v; break;
...
```

Tablet 内部是**列式**存储：每列一个基本类型数组（`double[]`/`long[]`/`Binary[]`…）。
这里直接按列号索引写入，**避免调用 `tablet.addValue(measurementName, ...)`**——
后者每格都要按测点名做一次哈希查找，宽设备（500 列）下开销巨大。

### ③④ 稀疏格与那个必须知道的 NPE 坑

缺失格子用 **bitmap 标记 null** 本已足够，**但 TEXT/STRING 列还必须额外填 `Binary.EMPTY_VALUE`**：

> tsfile 的 `Tablet.getTotalValueOccupation()` 在遍历 `Binary[]` 计算序列化体积时
> **不检查 bitmap**，数组里留 null 会直接抛 **NullPointerException**。

这是生产事故换来的教训（设计文档 §11.1），且经交叉验证 **tsfile 1.1.3 / IoTDB 1.3.7 仍存在**。
JSON 走 `STRING` 类型、普通字符串走 `TEXT`，两者底层都是 `Binary[]`，故两种类型都要填占位符。

### ⑤ 稀疏 null 会不会覆盖库里已有的值？——**不会**（实测验证）

这是最容易让人担心的一点：ts=2000 这行里 hum 是 null，写进去会不会把库里**已存在的**
hum 值抹成 null？

**答案：不会。** IoTDB 对齐写入中，bitmap 标记为 null 的格子语义是
**"本次不提供该测点的值"**，而非"把该测点置为 null"——服务端会跳过这些格子，已有数据原封不动。

真实容器（IoTDB 1.3.7）实测：

| | temp | hum | note(TEXT) |
|---|---|---|---|
| 第 1 次写 ts=2000 | 25.0 | 60 | hello |
| 第 2 次写 ts=2000 | **26.1**（有值） | `bitMaps[1].mark(0)` | `bitMaps[2].mark(0)` + `EMPTY_VALUE` |
| **库中实际结果** | **26.1** ✅ 已更新 | **60** ✅ **未被覆盖** | **hello** ✅ **未被覆盖** |
| `select last *` | 26.1 | 60 | hello（latest 同样不受影响） |

由此可见两件事：

1. **`bm[c].mark(r)` 才是语义所在**——声明"该格无值"，服务端跳过；
2. **`Binary.EMPTY_VALUE` 纯粹是序列化占位符**，不会被当作真实值写入
   （实测中 `note` 保持 `hello` 而非变成空字符串，正是证明）。

> **这正是变化上报模型成立的前提**：
> ```
> ts=1000  设备上报全部 500 个测点   → 全部落库
> ts=2000  设备只上报变化的 3 个     → 只更新这 3 个，其余 497 个历史值不受影响
> ```
> 若 null 会覆盖，每次稀疏上报都会抹掉未变化的测点，30% 变化率场景将完全不可用。

---

## 7. `flushAll()` —— 多设备合并 RPC

```java
List<Map.Entry<String, DeviceBatch>> toFlush = new ArrayList<>(pending.entrySet());
pending.clear();                                          // ① 先快照并清空
...
for (...) {
    try { tablet = batch.toTablet(e.getKey()); }
    catch (Throwable ex) {                                // ② 构造期毒丸隔离
        batch.fail(ex); onFlushed(batch, false); continue;
    }
    chunk.put(e.getKey(), tablet);
    cells += (long) batch.rows.size() * batch.schemas.size();
    if (cells >= MAX_CELLS_PER_RPC || chunk.size() >= MAX_DEVICES_PER_RPC) {   // ③
        flushChunk(chunk, chunkBatches);
        chunk = new LinkedHashMap<>(); chunkBatches = new ArrayList<>(); cells = 0;
    }
}
```

### ① 先快照再清空

即使后续任何环节抛异常，同一批也**不会被重复 flush**（幂等保护）。

### ② 构造期毒丸隔离

`toTablet` 若因数据异常抛出（如残留的 CCE），只让该设备失败，
**不影响本轮其他设备，更不允许异常逃逸杀死 shard 线程**。

### ③ 合并的理由与切块上限

稀疏场景下每设备每窗口往往只有 1~2 行，逐设备单发 RPC 会导致 RPC 数爆炸（每秒数千次）。
合并成一次 `insertAlignedTablets` 后 RPC 数直接降一个数量级。

两个切块上限防止 thrift 消息过大：
- `MAX_CELLS_PER_RPC = 500_000`（总单元格数）
- `MAX_DEVICES_PER_RPC = 1000`（设备数）

---

## 8. 失败处理与结算

### 8.1 `onFlushed` —— 记账闭环

```java
private void onFlushed(DeviceBatch batch, boolean success) {
    size.addAndGet(-batch.pointCount());                        // 释放背压计数
    (success ? stats.writtenPoints : stats.failedPoints).add(points);
}
```

**每个点在整个生命周期里恰好被结算一次**（多减 → `pendingPoints` 变负数；漏减 → 计数泄漏、
背压永久卡住）。四条结算路径：

| 路径 | 触发 | 代码 |
|---|---|---|
| 正常 flush | RPC 成功/失败 | `onFlushed` |
| 类型漂移丢点 | `add()` 返回 false | `accumulate` 里 `size--` |
| 停机拒收（入队后复核） | `remove` 成功 | `addBatch` |
| 停机清队 | `stop()` join 后 | `stop()` |

> 单测 `concurrentAddDuringStopKeepsAccountingConsistent` 就是断言这条不变量：
> 并发停机后 `pendingPoints()` 必须**恰好为 0**。

### 8.2 `BatchCompletion` —— 消息级结算

```java
void complete(int count)                   { finish(count, null); }
void fail(int count, Throwable error)      { finish(count, error); }
void drop(int count, int droppedDataPoints){ acceptedDataPoints.addAndGet(-droppedDataPoints);
                                             finish(count, null); }

private void finish(int count, Throwable error) {
    if (error != null) failure.compareAndSet(null, error);       // 记住第一个错误
    if (remaining.addAndGet(-count) == 0) {                      // 全部点结算完毕
        Throwable cause = failure.get();
        if (cause == null) future.set(Math.max(0, acceptedDataPoints.get()));
        else               future.setException(cause);
    }
}
```

三种结算语义：

| 方法 | 含义 | 对 Future 的影响 |
|---|---|---|
| `complete` | 点已成功落库 | 计入成功 |
| `fail` | **基础设施故障**（连接/超时/权限/磁盘） | 整条消息 Future 失败 → 上游 RETRY 可恢复 |
| `drop` | **确定性数据错误**（非法 key / 类型漂移） | **不失败**，只从 `acceptedDataPoints` 扣减 |

**`fail` 与 `drop` 的分野是整个失败处理的灵魂**：
- 基础设施故障**可以**靠重试恢复 → 必须让 Future 失败，让 RETRY 队列重放；
- 数据错误**永远不可能**靠重试恢复 → 若让 Future 失败，就成了 **Kafka 永久毒丸**
  （无限重放、lag 消不掉、还阻塞后续消息）。

`remaining` 归零才结算，保证"一条消息一个 Future"在所有分段、所有设备批次间正确汇聚。

### 8.3 跨批类型冲突的恢复（`recoverTypeConflict`）

批内漂移在 `add()` 就被拦下；但**跨批**冲突（本批 DOUBLE，IoTDB 里已有的序列是 INT64）
只能由服务端在 RPC 时发现，此时**整个设备的 Tablet 被拒**。

处理策略：不连坐整设备，而是**按 measurement 拆分逐列重试**——

```
整设备 Tablet 被拒 (507 data type of X is not consistent)
        ↓ splitByMeasurement()
   temp 单列 → 重试 → 仍报类型冲突 → drop（丢该列，成功结算）
   hum  单列 → 重试 → 成功 → complete（照常落库）
   press单列 → 重试 → 权限错误 → fail（真故障，Future 失败）
```

`isTypeConflict()` 只匹配 IoTDB **明确的类型不兼容错误串**，已对 1.3.7 真实 SessionPool
异常实测校准：

```
507: Fail to insert measurements [k1] caused by
     [data type of root.tb.d.k1 is not consistent, registered type INT64, inserting type DOUBLE...]
```

> ⚠️ **版本耦合**：升级 IoTDB 后必须重验错误串。缓解措施是集成测试
> `IotdbIntegrationIT.persistedTypeDriftIsDroppedWithoutFailingTheMessage` 在真实容器中
> 覆盖该路径，升级后跑一次即可捕获失效。

权限/磁盘/连接/超时等**绝不**被误判为可丢，仍失败 Future。

---

## 9. 一条消息的完整时间线

```
[规则引擎线程]
  saveBatch(device, 8 个 key)
    → addBatch: 建 BatchCompletion(remaining=8) → hash 到 shard-3
    → size += 8, 入队 WriteBatch
    → 立即返回 Future（此时尚未写 IoTDB）

[shard-3 线程]
  poll 出 WriteBatch
    → accumulate: 8 个点填进该设备 DeviceBatch 的一行 8 列
    → (等待 flush_interval_ms；期间可能又攒进别的消息、别的设备)
    → flushAll: 本设备 + 其他 40 台设备的 Tablet 合并
    → insertAlignedTablets(41 个 Tablet)   ← 一次 RPC
    → 成功: 每个 DeviceBatch.complete() → BatchCompletion.remaining 归零
            → future.set(acceptedDataPoints)
    → onFlushed: size -= 8

[规则引擎]
  Future 完成 → ack 消息 → Kafka offset 提交
```

**端到端延迟 ≈ flush 窗口 + RPC 耗时**（默认约 1s + 几十 ms）。

> 因此规则引擎的 `pack_processing_timeout` **必须大于 flush 窗口 + flush 耗时**
> （建议 10000~30000ms），否则会误判为超时。

---

## 10. 排查速查

| 现象 | 看什么 | 常见原因 |
|---|---|---|
| `pendingPoints` 持续高位 | IoTDB 侧 GC / 磁盘 | flush 变慢，背压将传导至规则引擎 |
| `backpressureEvents` 持续非零 | 水位配置 / IoTDB 健康 | 水位过小，或写入能力不足 |
| `typeDriftPoints` 持续非零 | **设备上报的数据类型** | 同 key 类型不稳定，需修数据源而非 IoTDB |
| `rpcFailures` 非零 | 网络 / 服务端错误日志 | 连接类故障或服务端拒绝 |
| `pendingPoints` 为负 | **代码 bug** | 记账重复扣减（不应出现，有单测覆盖） |
| 规则引擎 `timeoutMsgs` 非零但 Kafka lag 不涨 | 先查 IoTDB GC | 写延迟超过 pack 超时 |

指标前缀 `iotdbWriteBuffer.*`，每 `write.stats_interval_ms`（默认 10s）输出一行统计日志。

---

## 附：关键常量与配置

| 常量/配置 | 值 | 位置 | 作用 |
|---|---|---|---|
| `MAX_CELLS_PER_RPC` | 500,000 | 代码常量 | 单次 RPC 总单元格上限 |
| `MAX_DEVICES_PER_RPC` | 1000 | 代码常量 | 单次 RPC 设备数上限 |
| `MAX_RETRY_PROBES` | 3 | 代码常量 | 连续连接失败判定系统性故障的阈值 |
| 单轮 drain 上限 | 8192 | 代码常量 | 防止排空挤占 flush 时机 |
| `write.shards` | 0=auto(CPU) | 配置 | 分片/flush 线程数 |
| `write.batch_size` | 1000 | 配置 | 单设备行数触发 flush |
| `write.flush_interval_ms` | 1000 | 配置 | 攒批窗口（第一吞吐杠杆） |
| `write.max_pending_per_shard` | 200000 | 配置 | 背压硬水位（内存上界） |
| `write.max_backpressure_wait_ms` | 0=无限等 | 配置 | 背压等待上限 |
