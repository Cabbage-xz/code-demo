# RocketMQ 引入原因分析

## 一、为什么引入 RocketMQ？

### 基本数据流对比

**当前架构（有 MQ）**：
```
PowerJob线程 → [拉取5k条] → sendBatch() → MQ → Consumer线程 → INSERT IGNORE
```

**去掉 MQ 后的退化形态**：
```
PowerJob线程 → [拉取5k条] → INSERT IGNORE → [拉取下一批] → INSERT IGNORE → ...
```

---

### 场景一：生产者与消费者解耦

拉取数据和写入数据库是两个速率完全不同的操作：
- 拉取：内存计算，极快（MockClient 直接返回）
- 写入：每批 1000 条 INSERT IGNORE，受 MySQL 锁、索引维护、事务提交影响，明显更慢

**没有 MQ 的问题**

PowerJob 线程必须同步等待每一批 INSERT 完成才能继续拉取下一批：

```
线程T1: [拉批次1]--[等待INSERT批次1完成]--[拉批次2]--[等待INSERT批次2完成]--...
```

整条流水线变成串行。以 20000 条数据为例：
- 20000 / 5000 = 4 个批次，每批次分 5 个 chunk 写入（每 chunk 1000 条）
- 假设每 chunk 写入 100ms，一个批次 = 500ms
- 4 批次 = 2000ms 纯等待

有 MQ 时，PowerJob 线程只做"拉取+发消息"，4 个批次的发送几乎是毫秒级完成。写入由独立 Consumer 线程异步处理，生产者和消费者各自以最优速率运行。

此外，如果 DB 出现短暂慢查询（锁等待、索引重建），没有 MQ 时这个延迟直接传导给 PowerJob 线程，整个拉取任务被 DB 性能拖累。有 MQ 时，消息堆积在队列里，生产者完全不感知消费者的处理速度。

---

### 场景二：批次级重试机制

MQ 的 `maxReconsumeTimes=3` 提供自动重试，失败超限后自动路由 DLQ，DLQ Consumer 负责标记 `insert_status=FAILED` 并触发 PowerJob 任务级重试。

**没有 MQ 的问题**

INSERT 失败时需在业务代码里手写重试逻辑，面临三个困境：

**困境1：重试边界模糊**

一个批次的 5 个 chunk 如果第 3 个失败，是重试整个批次（重复插入前 2 个 chunk）？还是只重试第 3 个？前者浪费，后者需要额外记录"断点位置"。MQ 方案中一条消息 = 一个批次，消息要么被完整消费，要么整条消息重试，边界非常清晰。

**困境2：重试与主流程耦合**

```java
for (int retry = 0; retry < 3; retry++) {
    try {
        insertBatch(records);
        break;
    } catch (Exception e) {
        if (retry == 2) {
            // 标记失败，但此时PowerJob线程还在这里，
            // 整个syncDomainDate()的后续批次该怎么处理？
        }
        Thread.sleep(backoffMs); // 重试间隔阻塞整个拉取线程
    }
}
```

一个批次的失败会卡住整个域的同步流程，后续批次全部阻塞等待。

**困境3：DLQ 语义消失**

DLQ 承担着"区分瞬时故障（重试能恢复）和持久故障（需要任务级重试）"的职责。没有 MQ，这层故障分级不复存在。

---

### 场景三：削峰与流量控制

20 个域并行，每域同步 5 天，理论上有 100 个并发的"拉取+写入"单元同时运行。

**没有 MQ 的问题**

- 最坏情况下 20 × 5 = 100 个并发 INSERT 同时打向 `fault_record` 表
- `uk_domain_date_rank` 唯一索引在大量并发写入时锁争抢加剧
- 事务排队，响应时间从 100ms 劣化到秒级
- 连接池等待超时引发大量批次异常，进入重试逻辑，形成正反馈雪崩

有 MQ 后，DB 写入速率由 Consumer 线程池决定（上限可配置），是受控的匀速流量，而不是随 PowerJob 调度时机集中爆发的峰值。

---

### 场景四：批次级状态追踪的精准性

每条 MQ 消息对应一条 `sync_batch_record`，消息消费成功 → `insert_status=SUCCESS`，消息进 DLQ → `insert_status=FAILED`。重试时只需重跑 FAILED 的批次，精确到单个批次。

**没有 MQ 的问题**

批次与"消息"没有一一对应关系，很难精确知道"哪些批次的写入失败了"。失败检测要么靠异常捕获（同步链路），要么靠后置校验（对比源数据条数与目标表条数），两者都难以精确标定失败批次，重试时容易退化为整个域/整天重跑。

此外，当前状态机的 `MESSAGES_SENT` 中间状态消失：

```
当前: RUNNING → MESSAGES_SENT → SUCCESS / FAILED
退化: RUNNING → SUCCESS / FAILED
```

`MESSAGES_SENT` 让系统能区分"任务卡在拉取/发送阶段"还是"任务卡在消费者写入阶段"，丢失后可观测性降级。

---

## 二、纯 DB 状态追踪能否替代 MQ？

### 方案描述

用 `sync_batch_record` 记录每个批次的拉取状态和写入状态，同步执行 INSERT，失败时在表中标记，重试时查询 FAILED 批次重拉：

```
PowerJob线程 → 拉取批次 → 同步INSERT → 在sync_batch_record记录成功/失败
                                          ↓
                            重试时：查询FAILED批次 → 重拉 → 重INSERT
```

### 结论

**方案可行**，`sync_batch_record` 本来就在做"记录哪些批次成功/失败"的事，这一点 DB 完全胜任。但与 MQ 方案相比有以下实质性劣势：

### 劣势一：吞吐量降级（最核心）

```
DB方案(串行): [拉批次1]→[等INSERT完成]→[拉批次2]→[等INSERT完成]→...
MQ方案(并行): [拉批次1→发]→[拉批次2→发]→[拉批次3→发] （Consumer并行消费）
```

以 20000 条数据 / 5000 每批、每批 INSERT 耗时 500ms 为例：

| 方案 | 完成时间 |
|---|---|
| DB 状态追踪（串行） | 4批 × 500ms = 2000ms |
| MQ 异步 | ~20ms（发消息）+ Consumer 并行消费 |

### 劣势二：重试触发机制需要自己实现

MQ 的重试是框架级行为，消费失败后自动延迟重投，内置退避策略（1s, 5s, 10s...），无需任何业务代码。

DB 方案需要自己选择重试触发方式：

- **内联重试**：重试间隔阻塞整个拉取线程，一个批次故障拖慢后续所有批次
- **异步轮询**：引入定时任务轮询 FAILED 批次，是用更笨重的机制复现 MQ 的延迟重投能力，且轮询间隔决定重试延迟响应

### 劣势三：故障隔离性差

MQ 方案中 Consumer 抛异常仅影响这一条消息，Producer 早已结束，其他批次消息安静等待。

DB 方案中 INSERT 异常在 PowerJob 线程调用栈上冒泡，若 try-catch 粒度不够细，一个批次失败可能导致整个 `syncDomainDate()` 提前终止，后续批次没有机会执行，`sync_batch_record` 也不会有它们的记录，下次重试无从区分"没拉到"还是"拉到了但没写入"。

### 劣势四：并发写入无缓冲

MQ 方案中 Consumer 线程池大小决定 DB 写入并发上限，是可控的匀速流量。

DB 方案中 20 × 5 = 100 个子任务同时到达 INSERT 阶段，全部并发打向 `fault_record` 表，DB 连接池和唯一索引锁争抢均无缓冲，峰值压力集中爆发。

### 对比总结

| 能力 | DB 状态追踪方案 | MQ 方案 |
|---|---|---|
| 批次级状态记录 | 可以（同样用 sync_batch_record） | 可以 |
| 失败批次重试 | 可以，但需自己实现触发机制 | 框架自动，maxReconsumeTimes |
| 拉取与写入解耦 | 不行，串行耦合 | 天然解耦，各自最优速率 |
| 重试期间阻塞主流程 | 是（内联重试方案） | 否 |
| DB 写入流量平滑 | 不行，峰值裸打 | Consumer 线程池控流 |
| 故障隔离 | 差，异常传播至上层 | 好，消息独立重试 |
| 实现复杂度 | 高（重试触发、退避、DLQ 等均需手写） | 低（配置即得） |

DB 状态追踪只解决了"知道失败了"的问题，没有解决"谁来重试、怎么重试、重试时不阻塞主流程"的问题，而这三个问题 MQ 是用框架级机制一并解决的。

---

# MQ 消费设计

## 消费者配置

| 配置项 | 值 | 说明 |
|---|---|---|
| topic | `fault-data-sync-topic` | 正常消费 topic |
| consumerGroup | `fault-data-sync-consumer` | 消费组 |
| maxReconsumeTimes | 3 | 超出后消息进入 DLQ |
| DLQ topic | `%DLQ%fault-data-sync-consumer` | RocketMQ 自动创建 |

---

## 故障数据写库的幂等保障

### 当前方案（MySQL）

消费者通过两层机制保证故障数据不重复写入：

1. **`INSERT IGNORE`**：写库语句统一使用 `INSERT IGNORE`
2. **唯一索引**：`fault_record` 表有 `UNIQUE KEY uk_domain_date_rank(domain, data_date, rank)`

`rank` 由上游数据源在 `(domain, date)` 维度内单调递增赋值，是故障记录的业务主键。任何场景下的重复写入（MQ 重投、PowerJob 重试重拉、消费者实例重平衡）都会被唯一索引静默拦截，不报错，不影响已有数据。

**这是替换 `MockFaultDataSourceClient` 的契约前提**：真实数据源必须保证同一条故障记录的 `rank` 值在 `(domain, date)` 内稳定不变，否则幂等机制失效。

### 若写入 ClickHouse

ClickHouse 无唯一索引，`INSERT IGNORE` 语义不存在，需组合两个方案：

**方案 A（应用层去重，主防线）**：消费者写 ClickHouse 前，先查 `sync_batch_record.insert_status`，若已为 `SUCCESS` 则跳过写入，直接调用 `incrementCompletedBatch` 后返回。`sync_batch_record` 保留在 MySQL 中充当消费状态账本，与存储引擎无关。

**方案 B（ReplacingMergeTree，兜底）**：ClickHouse 表使用 `ReplacingMergeTree`，`ORDER BY (domain, data_date, rank)`，后台 Merge 时对相同 rank 的记录去重，查询加 `FINAL` 获得精确结果。用于兜底方案 A 未能拦截的极端场景。

---

## 已知极端边界场景：消费者并发重复消费

### 场景描述

```
Consumer1: 查 insert_status → PENDING → 开始写 ClickHouse（耗时中）
                                         ↓ 尚未调用 markInsertSuccess
Consumer2: 同一条消息被重投 → 查 insert_status → 仍是 PENDING → 也开始写 ClickHouse
                                                                  → 重复写入
```

### 触发条件（极端）

RocketMQ 同一消费组内，同一条消息被两个消费者**同时**处理，仅在以下条件下发生：

- Consumer1 的 `onMessage()` 处理时间超过 broker 消费超时（默认 **15 分钟**）仍未 ACK
- broker 判定该消费者超时，将消息重新投递给组内另一个实例

正常链路（ClickHouse 写入秒级完成）不会触发此条件。

### 解决方案（若需严格防御）

在 `insert_status` 中引入 `INSERTING` 中间态，消费者写库前通过 CAS 原子抢占：

```sql
-- 抢占：只有 PENDING/FAILED 状态可以抢到
UPDATE sync_batch_record
SET insert_status = 'INSERTING', update_time = NOW()
WHERE domain = ? AND data_date = ? AND batch_index = ?
  AND insert_status IN ('PENDING', 'FAILED')
```

- `affected = 1`：本消费者独占，继续写 ClickHouse，完成后改为 `SUCCESS`，失败改回 `FAILED`
- `affected = 0`：查当前状态；若 `SUCCESS` 则直接 `incrementCompletedBatch` 返回；若 `INSERTING` 则说明另一消费者正在处理，直接返回 OK

**INSERTING 卡死兜底**：`findFailed` 查询需加超时条件，将 `update_time` 超过阈值（如 30 分钟）的 `INSERTING` 记录视为失败，纳入 PowerJob 重试范围：

```sql
OR (insert_status = 'INSERTING' AND update_time < NOW() - INTERVAL 30 MINUTE)
```

### 当前选择

当前代码未实现上述防御，理由：

1. 触发条件（onMessage 超时 15 分钟）在正常写入链路下不可达
2. 即使触发，ClickHouse 侧的 ReplacingMergeTree 兜底可在 Merge 时消除重复数据
3. 引入 `INSERTING` 状态会增加消费者逻辑复杂度和额外的 MySQL 写压力

若后续 ClickHouse 写入出现长尾耗时（超分钟级），应重新评估是否启用此方案。
