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

# MQ 选型面试问答

## 一、为什么选 RocketMQ，而不是 Kafka 或 RabbitMQ？

结合 fault-data-sync-demo 场景：

**1. DLQ 机制天然契合业务失败链路**

RocketMQ 超过 `maxReconsumeTimes=3` 后自动路由到 `%DLQ%{consumerGroup}`，无需额外开发死信逻辑。本项目 DLQ 消费者直接触发任务级重试（PowerJob），整个失败链路是 `MQ retry → DLQ → FAILED → PowerJob retry`，与 RocketMQ 的重试模型天然吻合。

**2. Push Consumer 简化消费端代码**

消费端只需幂等 INSERT IGNORE，不需要手动管理 offset，Push 模型更省心。

**3. 延迟消息原生支持**

可用于批次重试的退避策略，Kafka 不支持，RabbitMQ 需插件。

**4. 国内生态成熟**

与 PowerJob、Spring Boot 整合文档完善。

---

## 二、DLQ"天然吻合"具体指什么？其他 MQ 无法实现吗？

### DLQ 失败链路的具体流转

```
【MQ层】  消费失败 → 自动进重试队列(%RETRY%) → 重试3次仍失败 → 自动路由到DLQ(%DLQ%fault-data-sync-consumer)
                                                                          ↓
【任务层】                                                    DLQ消费者 markInsertFailed()
                                                              + syncTaskRecordService.updateFailed()
                                                                          ↓
                                                                    PowerJob 捕获 FAILED 状态，触发任务级重试
```

### "天然吻合"的实质

关键是**零配置、确定性命名、消费者组粒度**：

| 特性 | RocketMQ |
|---|---|
| DLQ 自动创建 | 是，Broker 自动建 |
| DLQ Topic 命名 | 固定规则：`%DLQ%{consumerGroup}`，代码里写死即可 |
| 粒度 | 消费者组级别，与业务隔离边界一致 |
| 触发条件 | 超过 `maxReconsumeTimes` 自动触发，业务无感知 |

DLQ 消费者代码因此极简，只需专注"处理最终失败"这一件事，不需要在消费者里写任何"我失败了，我要路由到死信"的逻辑。

### 其他 MQ 能否实现？

**功能上都能实现，差距在于配置代价：**

**RabbitMQ** — 有 DLQ，但需要在每个队列声明时显式绑定：

```java
QueueBuilder.durable("fault-sync-queue")
    .withArgument("x-dead-letter-exchange", "fault-sync-dlx")  // 手动配
    .withArgument("x-max-delivery-count", 3)                    // 手动配
    .build();
// 还要单独声明 DLX、DLQ 并绑定
```

**Kafka** — 完全没有原生 DLQ，需业务代码自己造：

```
消费失败 → 业务代码捕获异常 → 手动发到 retry topic
→ 自己实现重试次数计数、退避、最终路由到 dlq topic
（Spring Kafka 提供 DeadLetterPublishingRecoverer 可简化，但仍需显式配置）
```

| | RocketMQ | RabbitMQ | Kafka |
|---|---|---|---|
| DLQ 是否原生 | 是，全自动 | 是，但需配置 | 否，需自建 |
| 业务代码侵入 | 无 | 少量（队列声明） | 较多（异常捕获+手动发送）|
| 维护成本 | 极低 | 低~中 | 中~高 |

---

## 三、RocketMQ、Kafka、RabbitMQ 核心差异对比

| 维度 | RocketMQ | Kafka | RabbitMQ |
|---|---|---|---|
| **设计目标** | 金融级消息、业务消息 | 高吞吐日志/流式处理 | 灵活路由、低延迟 |
| **吞吐量** | 十万级/s | 百万级/s（顺序写磁盘） | 万级/s（受 AMQP 协议开销影响）|
| **消息延迟** | ms 级 | ms~百ms（批量写入有积压） | μs~ms 级（内存队列时）|
| **消息模型** | Topic + Queue（推/拉） | Partition（拉，offset 自管理）| Exchange + Queue（AMQP，灵活路由）|
| **顺序消息** | 分区有序（Queue 级别） | Partition 内严格有序 | 单队列有序，集群模式无法保证 |
| **事务消息** | 原生支持（Half Message） | 支持（事务 Producer，较复杂）| 不原生支持 |
| **死信/重试** | 原生 DLQ，重试次数可配 | 无原生重试，需业务自己处理 | 原生 DLQ（x-dead-letter-exchange）|
| **延迟消息** | 原生支持（RocketMQ 5.x 任意延迟）| 不支持 | 需插件（rabbitmq-delayed-message-exchange）|
| **消费模型** | Push / Pull | Pull（拉，自管理 offset）| Push（Broker 推，ACK 机制）|
| **运维复杂度** | 中（NameServer + Broker）| 中高（依赖 ZooKeeper 或 KRaft）| 低（单机简单，集群需 erlang 运维）|
| **生态** | 阿里/国内主流 | 大数据/流处理生态（Flink、Spark）| 企业级 AMQP 标准 |

---

## 四、各 MQ 消息重试机制与超限后处理

### RocketMQ

- **默认重试次数**：16 次，间隔递增退避（10s / 30s / 1min / ... 最长 2 小时）
- **超出后**：自动路由到 `%DLQ%{consumerGroup}`，命名确定，Broker 自动创建

### RabbitMQ

- **默认重试次数**：无限制（不配 `x-max-delivery-count` 则无限重投）
- **超出后**：进入配置的 DLX/DLQ；若未配置则消息丢弃或永久堆积

> 常见生产事故：忘记配 `x-max-delivery-count`，消费失败消息在队列里无限循环，把 Broker 打垮。

重试方式分两种，容易混淆：

| 方式 | 行为 | 风险 |
|---|---|---|
| Nack + requeue=true | 消息打回队列头部，立刻重发 | 无次数上限，容易无限循环 |
| x-dead-letter-exchange + x-max-delivery-count | 超次数路由到 DLQ | 需手动在队列声明时配置 |

### Kafka

- **原生重试**：无，Kafka 不主动重投消息
- **消费失败后**：offset 不推进则消息永久可读，但后续消息全部阻塞

实践依赖框架：Spring Kafka 提供本地重试（RetryTemplate，默认3次，间隔1s）+ DeadLetterPublishingRecoverer（约定命名 `{topic}.DLT`），但均为应用层行为，非 Broker 行为。

### 三者对比

| | RocketMQ | RabbitMQ | Kafka |
|---|---|---|---|
| 默认重试次数 | **16次**（间隔递增）| 无限（不配则无限）| **无重试** |
| 超出后去哪 | 自动进 `%DLQ%{consumerGroup}` | 进配置的 DLX/DLQ（不配则丢弃或堆积）| offset 不推进，后续消息阻塞 |
| DLQ 命名 | 自动、确定性 | 手动配置 | 手动配置（框架约定 `.DLT` 后缀）|
| 重试间隔 | 递增退避 | 立即重投（默认），需插件实现退避 | 无 |
| 业务代码是否感知 | 无感知 | 需在队列声明处配置 | 需在消费逻辑里显式处理 |

---

## 五、适用场景总结

**选 RocketMQ，当你需要：**
- 业务消息（订单、支付、核对）对消息丢失零容忍
- 事务消息（本地事务 + MQ 原子性）
- 延迟/定时消息（如 30 分钟未支付自动取消）
- 原生 DLQ + 重试机制，减少业务代码
- 国内金融、电商核心链路

**选 Kafka，当你需要：**
- 极高吞吐（日志采集、埋点、监控数据流）
- 消息回溯（offset 可重置，历史数据重消费）
- 与 Flink / Spark Streaming 集成做实时计算
- 消费者自己控制消费进度

**选 RabbitMQ，当你需要：**
- 复杂路由（fanout、topic、header exchange）
- 低延迟（内存队列，μs 级）
- 多语言客户端（AMQP 协议标准）
- 快速上手，单机运维简单

> 一句话总结：Kafka 是"高速公路"，适合海量数据吞吐；RabbitMQ 是"立交桥"，适合灵活路由；RocketMQ 是"快递专线"，适合业务消息的可靠投递和精细化重试。

---

## 六、推拉模式

### Pull 模式

消费者主动去 Broker 拉取消息，自己控制节奏。

### Push 模式

Broker 主动将消息推送给消费者，消费者被动接收。

### Kafka：纯 Pull 模式

消费者定时轮询 Broker，主动拉取。用 **long polling** 解决空轮询问题：没有消息时连接挂起，最多等 `fetch.max.wait.ms`（默认 500ms），有消息立即返回。

优点：
- 消费者完全控制消费速度，不会被压垮
- 批量拉取，吞吐高
- offset 由消费者自己管理，支持历史消息回放

### RocketMQ：本质是 Pull，包装成 Push

`@RocketMQMessageListener` 底层是 long polling Pull，SDK 封装了轮询逻辑，对业务代码表现为"消息推过来了"。

```
RocketMQ SDK 内部（业务代码不感知）
  └── 后台线程不断 Pull Broker
        ↓ 拉到消息
      放入本地缓冲队列
        ↓
      回调 onMessage()  ← 业务代码只看到这一步，感觉像 Push
```

RocketMQ 也支持真正的 Pull 模式（`DefaultLitePullConsumer`），由业务代码手动调用 `poll()`，但很少使用。

### 为什么消息队列普遍选择 Pull

真 Push 的问题：Broker 不知道消费者的处理速度，消费者处理慢时会被消息压垮。Pull 让消费者按自己节奏取，天然具备**背压**能力。

本项目中消费者处理一条消息需要 5 次批量 INSERT，若是真 Push，Broker 可能在消费者还在写 DB 时继续推消息，导致消费者内存撑爆。Pull 模式下消费者处理完再拉，自然限速。

| 维度 | Kafka | RocketMQ（Push 模式）|
|---|---|---|
| 本质 | Pull | Pull（封装为 Push）|
| 消费节奏 | 消费者控制 | SDK 控制，回调业务代码 |
| 空轮询处理 | long polling | long polling |
| 消息堆积时 | 消费者按自己速度消费 | 本地缓冲队列可能撑满，需限流 |
| 历史消息回放 | 支持，调整 offset 即可 | 支持，但不如 Kafka 灵活 |

---

## 七、消费者组工作机制：RocketMQ vs Kafka

### Kafka

一个 Partition 只能被同组内一个消费者实例消费。消费者数量 > Partition 数量时，多出的消费者空闲。offset 由消费者自己维护（提交到 `__consumer_offsets` topic）。

不同 ConsumerGroup 各自独立维护消费进度，互不影响（多订阅语义）。

### RocketMQ

与 Kafka 类似，一个 Queue 在同一时刻只分配给同组内一个消费者实例。额外支持**广播消费**模式，同组内每个实例消费全量消息（Kafka 不支持，需每个消费者用不同 Group 实现）。

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 分配单位 | Partition | Queue |
| 消费模式 | 仅集群消费 | 集群 / 广播 |
| 消息顺序 | Partition 内有序 | Queue 内有序 |
| 消费者 > 分区时 | 多余实例空闲 | 多余实例空闲 |
| offset 存储 | `__consumer_offsets` topic | Broker 端 |
| 重试机制 | 需自己实现 | 内置重试队列 + DLQ |

### 多 ConsumerGroup 消费同一消息（正常设计，非重复消费）

```
Topic: order-topic
  └── 同一条消息

ConsumerGroup: order-pay-group     → 支付服务消费，处理扣款
ConsumerGroup: order-notify-group  → 通知服务消费，发短信
ConsumerGroup: order-stats-group   → 统计服务消费，更新报表
```

每个 ConsumerGroup 独立维护消费进度，这是消息队列解耦的核心价值，与"重复消费"是两个概念。

**真正的重复消费**：同一 ConsumerGroup 内，同一条消息因 ACK 超时被重新投递，由同组内实例再次处理。本项目用 `INSERT IGNORE` + `markInsertSuccess()` affected rows 判断解决此问题。

---

## 八、消息有序性

### RocketMQ 消息有序性

RocketMQ 同时支持有序和无序，取决于使用方式：

| 方式 | 机制 | 代价 |
|---|---|---|
| 普通消息（默认）| 轮询路由到多个 Queue，不保证全局顺序 | 无 |
| 分区有序 | 发送时用 `MessageQueueSelector` 将同一 key 路由到同一 Queue | 吞吐下降，消费需用 `MessageListenerOrderly` |
| 全局有序 | Topic 只建 1 个 Queue | 吞吐极低 |

Queue 内部的消息消费是有序的，跨 Queue 无序。

### 本项目的路由策略

```java
// FaultDataProducer.sendBatch()
rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(message).build());
```

`syncSend(topic, message)` 采用**轮询路由**，同一天的消息会分散到不同 Queue，消费顺序无保证。`RocketMQHeaders.KEYS` 字段（domain_date_batchIndex）仅用于控制台按 key 查消息，不影响路由。

本项目不需要顺序消费：每条消息是独立的一批（5k 条），批次间无依赖，`INSERT IGNORE` 保证幂等，乱序消费完全安全。

---

# MQ 消费设计

## 消费者配置

项目共有 **2 个消费者**，分属不同 ConsumerGroup：

| 消费者类 | Topic | ConsumerGroup | 说明 |
|---|---|---|---|
| `FaultDataConsumer` | `fault-data-sync-topic` | `fault-data-sync-consumer` | 正常消费，INSERT IGNORE 写库 |
| `FaultDataDlqConsumer` | `%DLQ%fault-data-sync-consumer` | `fault-data-sync-dlq-consumer` | 死信处理，标记 FAILED 触发 PowerJob 重试 |

```
生产者发消息
  └─ fault-data-sync-topic
       └─ FaultDataConsumer 消费
            ├─ 成功 → INSERT IGNORE + 更新状态
            └─ 失败重试 3 次后 → 消息自动进入 %DLQ%fault-data-sync-consumer
                                      └─ FaultDataDlqConsumer 消费
                                           └─ 标记 FAILED，触发 PowerJob 重试
```

目前单节点部署，`fault-data-sync-consumer` 只有 1 个实例，独占 Topic 全部 4 个 Queue。`@RocketMQMessageListener` 未配置 `consumeThreadMax`，走 RocketMQ Spring 默认值（consumeThreadMin=20，consumeThreadMax=64），消费者内部并发处理消息。

## 消息总量与吞吐量估算

**消息总量：**

| 场景 | 计算 | 消息数 |
|---|---|---|
| 默认（mock-total=20000）| 20 域 × 5 天 × (20000÷5000) | 400 条 |
| 顶峰（mock-total=1000000）| 20 域 × 5 天 × (1000000÷5000) | 20000 条 |

**单条消息处理耗时：**

```
5000 条记录 ÷ 1000 (batch-size) = 5 次 INSERT IGNORE
每次批量插入 ~50ms（rewriteBatchedStatements=true 已开启）
单条消息耗时 ≈ 250~500ms
```

**吞吐估算：**

```
20 线程并发 ÷ 0.3s/条 ≈ 60~80 条消息/秒
```

| 场景 | 消息数 | 处理时间 | 结论 |
|---|---|---|---|
| 默认 | 400 条 | < 10 秒 | 几乎无积压 |
| 顶峰 | 20000 条 | ~5 分钟 | 有临时积压，可自然消化 |

## 按域配置消费者（扩展方案）

当前所有 20 个域的消息共享同一消费者实例和线程池。若需域间隔离（域 A 积压不影响域 B），可通过以下方案实现，但需新增代码：

**方案：Tag 过滤 + 编程式消费者注册**

1. 生产者发送时带 Tag（domain 名）：`topic:domain` 格式
2. 放弃 `@RocketMQMessageListener` 注解，改用 RocketMQ 原生 API 编程式注册消费者
3. 启动时按域列表动态创建消费者，每个消费者 `subscribe(topic, domain)` 只消费自己的 Tag

**当前 demo 场景不需要此方案**。20 域共享线程池，正常消息量 400 条处理时间 10 秒内，无域间竞争问题。只有各域数据量差异悬殊时，隔离才值得引入这个复杂度。

---

## 故障数据写库的幂等保障

### 当前方案（MySQL）

消费者通过三层机制保证故障数据不重复写入，同时防止重复消费导致任务计数虚高：

**第一层：`markInsertSuccess` 幂等条件更新（防计数虚高）**

`syncBatchRecordService.markInsertSuccess()` 内部使用条件 UPDATE：

```sql
UPDATE sync_batch_record
SET insert_status = 'SUCCESS', update_time = NOW()
WHERE domain = ? AND data_date = ? AND batch_index = ?
  AND insert_status != 'SUCCESS'    -- 已成功则跳过
```

返回 affected rows：
- `affected = 1`：首次消费成功，继续调用 `incrementCompletedBatch` 推进任务进度
- `affected = 0`：重复消费检测到，跳过 `incrementCompletedBatch`，防止 `completed_batch_count` 虚高

**第二层：`INSERT IGNORE`**：写库语句统一使用 `INSERT IGNORE`，保证数据层重复写入安全。

**第三层：唯一索引**：`fault_record` 表有 `UNIQUE KEY uk_domain_date_rank(domain, data_date, rank)`，唯一索引在 DB 层兜底拦截任何重复记录，不报错，不影响已有数据。

`rank` 由上游数据源在 `(domain, date)` 维度内单调递增赋值，是故障记录的业务主键。任何场景下的重复写入（MQ 重投、PowerJob 重试重拉、消费者实例重平衡）都会被唯一索引静默拦截。

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

### 当前实现

当前代码通过 `markInsertSuccess` 的条件 UPDATE（`ne insert_status SUCCESS`）+ 返回 affected rows 的方式实现了轻量级重复消费防护，无需引入 `INSERTING` 中间态：

- 正常链路（首次消费）：affected=1 → 推进计数 → 无额外开销
- 重复消费：affected=0 → 跳过计数 → 一次 UPDATE 拦截，无其他操作

`INSERTING` 中间态方案（CAS 抢占）可解决两个消费者**同时**处理同一消息时的并行写入问题，但该场景触发条件为 onMessage 超时 15 分钟，正常写入链路下不可达。若后续 ClickHouse 写入出现长尾耗时，应重新评估是否启用此方案。
