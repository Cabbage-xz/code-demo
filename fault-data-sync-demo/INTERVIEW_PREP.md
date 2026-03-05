# 面试准备文档 — fault-data-sync-demo

> 适用岗位：Java 初级 / 中级开发工程师（1-3 年经验）
> 面试官视角：高级开发 / 架构师
> 本文档包含：项目介绍话术、面试问答（含标准答案）、技术延伸题

---

## 一、项目介绍（面试者自述稿）

> 场景：面试官说"介绍一下你觉得最有技术含量的项目"

---

**一句话概括**：设计并落地了一套**基于 PowerJob + RocketMQ 的故障日志每日全量同步流水线**：采用 rank 游标翻页消除深翻页性能陷阱、MQ 异步解耦拉取与写库、各领域独立 PowerJob 定时任务并发调度，DB 写入吞吐从逐条串行的 800 条/秒提升至批量入库的 4 万条/秒（提升 50 倍），单领域每日同步全链路由同步串行方案的约 17 分钟压缩至约 2 分钟，支撑 20 个领域每日数百万条故障记录的 SLA。

### 完整口述版（2-3 分钟 · 面向技术面试官）

---

"我来介绍一个我参与设计并落地的**故障数据每日全量同步系统**。

**业务背景**：公司的设备管理平台每天需要把各业务领域的设备故障日志从上游数据源同步到本地 MySQL，供质检和运维团队做故障分析和告警。核心挑战有三：上游接口单次最多返回 5000 条，必须分页拉取；顶峰期单领域单日数据量可达百万级；上游数据存在最长 5 天的延迟更新，所以每天要对最近 5 天全部重同步一遍。

**技术选型**：整体采用 PowerJob + RocketMQ + MySQL 的架构。PowerJob 负责定时调度和任务级重试，RocketMQ 作为拉取和写库之间的缓冲解耦层，MyBatis-Plus 负责批量写入。

**核心设计，我重点讲四个决策：**

第一，**翻页策略选 rank 游标**。最直觉的 offset 分页在百万级数据下会退化成全表扫描，第 200 页的耗时从 50ms 增长到 8 秒以上。改用 rank 游标后，每批请求走索引范围扫描，耗时恒定在 50-80ms，200 批总拉取时间从约 27 分钟压缩到约 16 秒。

第二，**MQ 异步解耦拉取与写库**。拉完直接写库会导致 DB 压力峰值和线程阻塞。每批 5000 条封装成一条 MQ 消息，Consumer 做批量 INSERT IGNORE，DB 写入吞吐从逐条的约 800 条/秒提升到约 4 万条/秒，提升 50 倍。消费失败自动重试 3 次，超限进死信队列，DLQ Consumer 负责告警和标记任务失败。

第三，**各领域独立 PowerJob 任务**。没有做一个大任务处理所有 20 个领域，而是每个领域配置独立的定时任务，共用一个通用处理器，通过 instanceParams 传入领域名称。这样失败隔离粒度到领域级，每个领域可以独立配置 cron、超时和重试策略。处理器内部用 CompletableFuture + 共享有界线程池并发执行 5 个日期任务，CallerRunsPolicy 在多域任务并发争抢线程池时自然产生背压。

第四，**幂等双保险**。主策略是每次同步前先 DELETE 该 domain+date 的旧数据再全量插入，天然幂等支持任意重跑。安全兜底是 fault_record 上的联合唯一索引 `(domain, data_date, rank)` + INSERT IGNORE，防止 MQ 消息重投时重复写入。

**进度跟踪**：设计了 sync_task_record 表，状态机为 PENDING → RUNNING → MESSAGES_SENT → SUCCESS / FAILED。MESSAGES_SENT 是关键哨兵状态，表示生产侧已完成、消费侧正在追；Consumer 每消费完一批用一条原子 UPDATE + CASE WHEN SQL 推进计数，全部消费完自动翻到 SUCCESS，不依赖分布式锁。

**量化结果**：正常场景（约 2 万条/域/天）单领域全链路约 2 分钟完成，20 个领域并发约 2 分钟内全部到达 SUCCESS，每日同步约 200 万条数据。顶峰场景（百万级/域/天）约 15-20 分钟，相比原串行方案预估的 6+ 小时缩短了 95% 以上。

这个项目让我对**批处理管道设计、MQ 可靠性保障、幂等设计，以及如何在高吞吐和可靠性之间做工程权衡**有了比较深入的实践理解。"

---

### 精简要点版（面试时分点阐述）

---

"我来介绍一个我参与设计并落地的**故障数据每日同步系统**。

**背景**：我们的业务平台需要每天从上游数据源拉取设备上报的故障日志，持久化到本地 MySQL 供后续分析。挑战在于三点：
一是上游接口**每次最多返回 5000 条**，需要分页；
二是**顶峰时单领域单日数据量可达百万级**；
三是数据存在**最长 5 天的延迟更新**，需要每天重同步历史数据。

**我做了以下设计：**

**① 翻页策略选 rank 游标而非 offset。**
对于百万级数据，`LIMIT 0, 5000` → `LIMIT 995000, 5000` 的深翻页在上游接口底层产生全表扫描，耗时从第1页的 50ms 线性增长到第200页的 8s+。改用 rank 游标后每页耗时稳定在 **50-80ms**，200批次总拉取时间从 **~27 分钟压缩到 ~16 秒**。

**② MQ 异步解耦拉取与写库。**
拉取完每批 5k 数据后立即发 MQ，不等待写库结果，实现生产和消费的解耦。消费端批量 INSERT IGNORE，DB 写入吞吐从串行逐条写的 **~800条/秒** 提升到批量1000条/批的 **~4万条/秒**，提升约 **50倍**。

**③ 各领域独立 PowerJob 任务，通用处理器单域并发。**

每个领域在 PowerJob 中配置各自的定时任务，均指向同一个通用处理器 `FaultDataSyncJob`，通过 `instanceParams` 传入该领域的专属参数（domain 名称、同步天数）。领域级别的并行由 PowerJob 并发调度 20 个任务实例实现，单个处理器内部仅对 5 个日期并发执行（CompletableFuture + 共享有界线程池 20 线程），正常场景全链路约 **2 分钟**完成 20 域所有数据。单域5天任务共享 20 线程有界线程池，PowerJob 同时调起多个域任务时 pool 充当全局并发限速器。顶峰场景（100万条/domain/date）全链路约 **15-20 分钟**，相比原来串行方案预估的 **6+ 小时**，缩短了 **95% 以上**。

**④ 幂等双保险。**
主策略是每次同步前先 DELETE 该 domain+date 的旧数据，天然幂等，支持 PowerJob 任意重跑。安全兜底是在 fault_record 上加了联合唯一索引 `(domain, data_date, rank)` + INSERT IGNORE，防 MQ 消息重投导致重复写入。

**⑤ 可靠性保障。**
MQ 消费失败自动重试 3 次，超限进 DLQ，DLQ Consumer 将 sync_task_record 状态置为 FAILED 并预留告警钩子。sync_task_record 表用单条原子 UPDATE + CASE WHEN 跟踪批次完成进度，避免分布式锁依赖。

**量化指标总结：**

| 指标 | 正常场景 | 顶峰场景 |
|------|---------|---------|
| 每日同步总量 | ~200 万条（20域×5天×2万，20个PowerJob任务并发执行） | ~1 亿条（20域×5天×100万） |
| Pull+MQ发送 Wall Time | ~10 秒 | ~80 秒 |
| 全链路同步耗时 | ~2 分钟 | ~15-20 分钟 |
| DB写入吞吐（vs逐条） | 4万条/s（+50倍） | 4万条/s（+50倍） |
| 翻页耗时（vs offset方案） | 稳定50-80ms/批 | 减少约95%耗时 |
| 幂等重跑 | 支持任意次数 | 支持任意次数 |

这个项目让我对**高吞吐批处理架构、MQ 可靠性保障、幂等设计**有了比较深的实践理解。"

---

## 二、系统架构与完整链路图

---

### 系统架构图

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                               外 部 基 础 设 施                                   │
│                                                                                  │
│  ┌─────────────────────────┐   ┌──────────────────────────┐  ┌────────────────┐ │
│  │  PowerJob Server :7700  │   │  RocketMQ Broker :9876   │  │  MySQL :3306   │ │
│  │                         │   │                          │  │  DB: code_demo │ │
│  │  每域独立 cron 任务      │   │  Topic:                  │  │                │ │
│  │  instanceParams:        │   │  fault-data-sync-topic   │  │  fault_record  │ │
│  │  {                      │   │                          │  │  (UNIQUE idx:  │ │
│  │    "domain": "xxx",     │   │  DLQ:                    │  │  domain+date   │ │
│  │    "syncDays": 5        │   │  %DLQ%fault-data-sync-   │  │  +rank)        │ │
│  │  }                      │   │  consumer                │  │                │ │
│  │                         │   │                          │  │  sync_task_    │ │
│  │  20 个任务对应 20 个域   │   │                          │  │  record        │ │
│  └────────────┬────────────┘   └────────────┬─────────────┘  └───────┬────────┘ │
└───────────────│────────────────────────────── │─────────────────────── │─────────┘
                │ 任务下发                       │ 投递 / 消费             │ 读写
                ▼                               │                        │
┌──────────────────────────────────────────────────────────────────────────────────┐
│                  fault-data-sync-demo  (Spring Boot App :8083)                   │
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  Job 层                                                                  │   │
│  │  FaultDataSyncJob  (PowerJob BasicProcessor)                             │   │
│  │    parseDomain() · parseSyncDays() · buildSyncDates()                    │   │
│  │    CompletableFuture × syncDays  →  syncExecutor                         │   │
│  └─────────────────────────────────┬────────────────────────────────────────┘   │
│                                    │ syncDomainDate(domain, date)                │
│  ┌─────────────────────────────────▼────────────────────────────────────────┐   │
│  │  Service 层                                                              │   │
│  │  FaultSyncServiceImpl                                                    │   │
│  │    RUNNING → DELETE → rank游标循环(pull + send) → MESSAGES_SENT          │   │
│  │  SyncTaskRecordServiceImpl                                               │   │
│  │    状态机：PENDING → RUNNING → MESSAGES_SENT → SUCCESS / FAILED          │   │
│  └──────────────┬───────────────────────────────────────┬────────────────── ┘   │
│                 │ pull(lastRank, 5000)                   │ syncSend(batch)       │
│  ┌──────────────▼──────────────────────┐  ┌─────────────▼──────────────────┐   │
│  │  Client 层                          │  │  MQ 生产层                     │   │
│  │  FaultDataSourceClient (接口)        │  │  FaultDataProducer             │   │
│  │  └─ MockFaultDataSourceClient        │  │  syncSend · retry=2            │   │
│  │       生产替换为真实 HTTP 调用        │  │                                │   │
│  └─────────────────────────────────────┘  └─────────────┬──────────────────┘   │
│                                                          │ → RocketMQ            │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  Config 层   SyncThreadPoolConfig                                        │   │
│  │  ThreadPoolExecutor(core=max=20, ArrayBlockingQueue(100), CallerRuns)    │   │
│  │  双重作用：① 5个日期并发执行  ② 多域任务共享，充当全局并发限速器           │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                            ↑ 从 RocketMQ 消费                    │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  MQ 消费层                                                               │   │
│  │  FaultDataConsumer  (@RocketMQMessageListener, maxReconsumeTimes=3)      │   │
│  │    CollUtil.split(1000) → batchInsert(INSERT IGNORE)                     │   │
│  │    → incrementCompletedBatch → status = SUCCESS                          │   │
│  │  FaultDataDlqConsumer  (订阅 %DLQ%fault-data-sync-consumer)              │   │
│  │    → status = FAILED + 告警钩子（钉钉 / 邮件 / PagerDuty 可扩展）        │   │
│  └─────────────────────────────────┬────────────────────────────────────────┘   │
│                                    │ batchInsert / updateStatus                  │
│  ┌─────────────────────────────────▼────────────────────────────────────────┐   │
│  │  Persistence 层  (MyBatis-Plus)                                          │   │
│  │  FaultRecordMapper      ·  resources/mapper/FaultRecordMapper.xml        │   │
│  │  SyncTaskRecordMapper   ·  resources/mapper/SyncTaskRecordMapper.xml     │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

### 完整链路图

#### 正常链路（Happy Path）

```
                       PowerJob Server（cron，每日触发）
                                    │
                  instanceParams: {"domain":"domain_a","syncDays":5}
                                    │
                                    ▼
                  ┌─────────────────────────────────────────┐
                  │         FaultDataSyncJob.process()       │
                  │  parseDomain()    → "domain_a"           │
                  │  buildSyncDates() → [D-1,D-2,D-3,D-4,D-5] │
                  └─────────────────────┬───────────────────┘
                                        │ 提交 5 个 CompletableFuture
                    ┌───────────────────┼───────────────────┐
                    │                   │                   │
              CF: D-1              CF: D-2 ···         CF: D-5
                    └───────────────────┼───────────────────┘
                                        │ syncExecutor
                                        │ （20线程有界线程池，与其他域共享）
                                        ▼
           ┌────────────────────────────────────────────────────────────┐
           │   FaultSyncServiceImpl.syncDomainDate("domain_a", D-1)    │
           │                                                            │
           │  [1] sync_task_record  →  RUNNING                         │
           │  [2] DELETE fault_record                                   │
           │        WHERE domain='domain_a' AND data_date='D-1'        │
           │        ↑ 全量覆盖：保证本次同步到最新数据                   │
           │                                                            │
           │  [3] rank 游标翻页拉取循环  (lastRank 初始值 = 0)          │
           │  ┌──────────────────────────────────────────────────────┐  │
           │  │  a. records = client.pull(domain, D-1, lastRank, 5000)│  │
           │  │     → 返回 List<FaultRecordDTO>（rank > lastRank）    │  │
           │  │                                                      │  │
           │  │  b. FaultDataProducer.syncSend(                      │  │
           │  │       FaultDataBatchMessage{domain, date,            │  │
           │  │         batchSeq, records})                          │  │
           │  │     → RocketMQ: fault-data-sync-topic                │  │
           │  │       发送失败自动重试 2 次                           │  │
           │  │                                                      │  │
           │  │  c. lastRank = max(records[*].rank)                  │  │
           │  │                                                      │  │
           │  │  d. records.size() < 5000  →  EXIT（最后一批）       │  │
           │  │     else  →  继续拉取下一批                          │  │
           │  └──────────────────────────────────────────────────────┘  │
           │     正常场景：2万条 / 4批；顶峰：100万条 / 200批            │
           │                                                            │
           │  [4] sync_task_record  →  MESSAGES_SENT(batchCount=N)     │
           └────────────────────────────────────────────────────────────┘
                                        │
                    [5 个 CF 全部完成，汇总结果]
                                        │
                                        ▼
                  PowerJob ProcessResult(true, "成功=5 失败=0 总计=5")
                  → PowerJob 标记任务实例 SUCCESS


═══════════════════ 异步消费链路（与拉取并行执行）═══════════════════

                  RocketMQ: fault-data-sync-topic
                                    │
                         投递 FaultDataBatchMessage
                                    │
                                    ▼
           ┌────────────────────────────────────────────────────────────┐
           │          FaultDataConsumer.onMessage(msg)                  │
           │                                                            │
           │  [A] msg.records  →  List<FaultRecordEntity>              │
           │                                                            │
           │  [B] CollUtil.split(entities, 1000)                       │
           │      → faultRecordMapper.batchInsert(subBatch)            │
           │         INSERT IGNORE INTO fault_record VALUES (...)      │
           │         唯一索引 (domain, data_date, rank) 防重复写入      │
           │                                                            │
           │  [C] syncTaskRecordService.incrementCompletedBatch(N)     │
           │      UPDATE sync_task_record                               │
           │      SET completed_batch_count = completed_batch_count + 1,│
           │          status = CASE WHEN completed_batch_count+1 >= N  │
           │                        THEN 'SUCCESS' ELSE status END     │
           │      WHERE domain=? AND data_date=? AND status='MESSAGES_SENT' │
           │      → completed_batch_count == N 时 → status = SUCCESS ✓ │
           └────────────────────────────────────────────────────────────┘
```

#### 故障路径

```
═══════════════════ 故障路径 A：拉取阶段报错 ═══════════════════

  例：共8批数据，前3批发送成功，第4批 client.pull() 抛异常

  client.pull() 抛出异常
          │
          ▼
  FaultSyncServiceImpl catch 块：
    sync_task_record  →  FAILED（MESSAGES_SENT 未被设置，batchCount 未记录）
    异常向上传播  →  CompletableFuture 返回 "FAIL:domain_a_D-1:连接超时"

  已发出的前3批 MQ 消息：
    Consumer 正常消费，INSERT IGNORE 写库 ✓
    BUT: incrementCompletedBatch 的 WHERE status='MESSAGES_SENT' 不匹配
         （当前状态为 FAILED）→  0行更新  →  任务永远不会变 SUCCESS

  PowerJob 任务重试：
    [1] DELETE fault_record（清掉前3批已入库数据）
    [2] lastRank = 0，重新拉取全部8批
    [3] 老的3条MQ消息若仍在队列被再次消费 → INSERT IGNORE 跳过或正常插入
    [4] 8批全部消费成功 → SUCCESS ✓
    ⚠ 当前设计无断点续传，每次重跑必须从头拉取


═══════════════════ 故障路径 B：消费侧入库失败 ═══════════════════

  例：前4批成功入库，第5批 batchInsert 抛异常（DB连接断）

  pull 循环不感知消费侧结果：
    第5批 MQ 发出后继续拉取 → 8批全部发出
    sync_task_record  →  MESSAGES_SENT(8)

  FaultDataConsumer 消费第5批 → batchInsert 抛异常
          │
          ▼
  RocketMQ 指数退避重投（间隔：10s / 30s / 1min，最多3次）
          │ 其余7批独立消费，不受影响，正常写库
          │ 第5批3次仍失败
          ▼
  %DLQ%fault-data-sync-consumer
          │
          ▼
  FaultDataDlqConsumer.onMessage()
    sync_task_record  →  FAILED + 记录错误信息
    触发告警钩子（钉钉 / 邮件 / PagerDuty 可扩展）

  最终状态：fault_record 有7批数据（35000条），第5批5000条缺失

  PowerJob 任务重跑：
    [1] DELETE 清掉7批数据
    [2] 重拉全部8批，发8条新 MQ 消息
    [3] 8批全部消费成功，INSERT IGNORE 幂等保障 → SUCCESS ✓


═══════════════════ 两种故障的恢复层级对比 ═══════════════════

  ┌───────────────────────┬──────────────────────────────┬───────────────────┐
  │  保障层               │  负责的故障类型               │  代价             │
  ├───────────────────────┼──────────────────────────────┼───────────────────┤
  │  MQ 自动重试（3次）   │  瞬时故障（DB抖动、连接闪断） │  低：仅重投单条   │
  │  PowerJob 任务重跑    │  持久故障（宕机、Bug修复补跑） │  重拉全量数据     │
  │  DELETE+INSERT IGNORE │  幂等兜底，任意次重跑正确     │  短暂空窗口可接受 │
  └───────────────────────┴──────────────────────────────┴───────────────────┘
```

---

### sync_task_record 状态机详解

每个 `(domain, data_date)` 组合对应 sync_task_record 表中的一行，贯穿同步任务的完整生命周期。

#### 状态转换图

```
                          任务首次触发
                               │
                               ▼
                          ┌─────────┐
                          │ PENDING │  记录已创建，等待执行
                          └────┬────┘
                               │  syncDomainDate() 开始 → DELETE 执行完毕
                               ▼
                          ┌─────────┐
                          │ RUNNING │  DELETE 已完成，rank游标拉取循环进行中
                          └────┬────┘
                    ┌──────────┴──────────┐
          pull/send 全部完成           pull() 抛出异常
          batchCount 记录完毕          或 sendBatch 失败
                    │                       │
                    ▼                       ▼
           ┌──────────────┐           ┌────────┐
           │ MESSAGES_SENT│           │ FAILED │ ←─── DLQ Consumer 也会
           │ batchCount=N │           │        │      直接写入此状态
           └──────┬───────┘           └────────┘
                  │  Consumer 每消费完一批：
                  │  completed_batch_count + 1
                  │  CASE WHEN completed+1 >= N → SUCCESS
                  │
                  ├─ completed < N  →  保持 MESSAGES_SENT，继续等待
                  │
                  └─ completed == N
                               │
                               ▼
                          ┌─────────┐
                          │ SUCCESS │  所有批次消费完毕，数据完整入库
                          └─────────┘
```

#### 各状态含义与设计考量

| 状态 | 含义 | 触发时机 | 设计意图 |
|------|------|---------|---------|
| PENDING | 任务记录已初始化 | syncDomainDate() 入口 upsert | 幂等创建，重跑时不报错 |
| RUNNING | 生产侧正在执行 | DELETE 完成后立即设置 | 标记空窗口开始；监控可感知正在执行 |
| MESSAGES_SENT | 生产侧完成，等待消费侧 | pull 循环结束，batchCount 写入 | 关键哨兵：Consumer 的 incrementCompletedBatch WHERE status='MESSAGES_SENT' 依赖此状态；FAILED 后此条件不再匹配，防止幽灵计数 |
| SUCCESS | 全部批次消费写库完成 | completed_batch_count == batchCount 时原子翻转 | 终态，表示数据完整可用 |
| FAILED | 生产侧报错或消费侧进 DLQ | pull 异常 catch / DLQ Consumer | 告警出口；PowerJob 重跑时 DELETE + 状态重置 |

#### 关键 SQL：incrementCompletedBatch

```sql
UPDATE sync_task_record
SET completed_batch_count = completed_batch_count + 1,
    status = CASE
               WHEN completed_batch_count + 1 >= #{batchCount} THEN 'SUCCESS'
               ELSE status
             END,
    end_time = CASE
                 WHEN completed_batch_count + 1 >= #{batchCount} THEN NOW()
                 ELSE end_time
               END
WHERE domain = #{domain}
  AND data_date = #{dataDate}
  AND status = 'MESSAGES_SENT'
```

**为什么这样设计：**
1. **原子性**：`completed_batch_count + 1` 在 DB 层计算，不是先 SELECT 再 UPDATE，MySQL 行锁保证多个 Consumer 并发时串行化，无需应用层锁
2. **防幽灵计数**：`WHERE status='MESSAGES_SENT'` 保证只有在正常流程中才推进计数；一旦状态变为 FAILED（DLQ 触发），后续 Consumer 的 UPDATE 影响行数为 0，不会意外将失败任务变成 SUCCESS
3. **自动终态**：CASE WHEN 在同一条 SQL 内完成 SUCCESS 的翻转，无需额外的状态更新请求

#### 状态机与故障场景的对应关系

| 故障场景 | 最终状态 | completed_batch_count | 原因 |
|---------|---------|----------------------|------|
| 正常完成 | SUCCESS | == batchCount | 所有批次消费完毕 |
| 拉取阶段报错 | FAILED | 0（或部分值，但无意义） | MESSAGES_SENT 未设置，WHERE 不匹配，计数不推进 |
| 消费侧入库失败进 DLQ | FAILED | < batchCount | DLQ Consumer 强制置 FAILED；其余批次的 increment 因 WHERE 不匹配而停止 |
| PowerJob 任务重跑 | 重置为 RUNNING | 0 | DELETE 清理数据，状态机重新走一遍 |

---

## 三、面试问答

---

### 【模块一：项目设计相关】

---

**Q1：你提到用 rank 游标翻页，能解释一下为什么 offset 深翻页慢吗？**

**A（面试者答）：**

offset 分页的 SQL 是 `SELECT ... LIMIT offset, pageSize`，数据库执行时必须先扫描并跳过前 offset 条记录，即使这些记录最终不返回。当 offset 很大时（比如第 200 页，offset=995000），数据库要扫描近 100 万行然后丢弃其中 995000 行，只保留最后 5000 行。这个过程接近全表扫描，耗时随页数线性增长。

rank 游标方案改成 `WHERE rank > lastRank ORDER BY rank LIMIT 5000`，rank 列上有索引，每次查询都是从索引的某个位置开始向后取 5000 条，无论是第 1 页还是第 200 页，执行计划都一样，耗时恒定。

**面试官追问：rank 游标有什么局限性？**

rank 游标要求数据有一个单调递增、不重复的排序字段。如果数据需要多维度排序（比如先按时间再按 ID），就很难直接用。另外，如果上游数据中途插入了新的 rank 值（rank 不连续但乱序），游标会漏数据。所以这个方案的前提是上游对每条数据的 rank 赋值是稳定的、递增的。

---

**Q2：为什么拉取完数据不直接写库，要多走一层 MQ？引入 MQ 有什么代价？**

**A：**

核心是**解耦**和**削峰**。

解耦：拉取和写库是两个不同速率的操作——API 拉取受上游限流约束（比如 QPS 限制），DB 写入受 MySQL 连接数和磁盘 IO 约束。把两者直接串联，慢的一方会拖慢整体。MQ 作为中间缓冲，让两侧可以以各自最优速率运行。

削峰：顶峰场景 100 万条数据会产生 200 条 MQ 消息，Consumer 可以按自己消化得了的速度消费，不会因为瞬时大量写库请求压垮 MySQL。

代价：
1. 增加了系统复杂度，需要维护 RocketMQ 集群
2. 引入了最终一致性，数据写库有延迟（不是拉完立刻可查）
3. 消息序列化/反序列化有额外开销
4. 需要处理 MQ 故障场景（Broker 不可用时生产者怎么办）

这个场景的特点是每日批处理、允许异步，所以引入 MQ 的收益远大于代价。如果是实时同步、要求秒级可见，就需要重新评估。

**追问(a)：为什么选 RocketMQ 而不是 Kafka 或 RabbitMQ？**

- RocketMQ 内置重试（指数退避）+ DLQ（`%DLQ%{consumerGroup}`），零配置即支持失败隔离和告警出口
- 单批 5k 条 JSON 约 500KB，RocketMQ 对这个消息体量友好；Kafka 的消息模型更适合流式日志，单消息大小限制默认 1MB 且配置复杂
- `@RocketMQMessageListener` 声明式消费，Spring Boot Starter 集成简单
- RabbitMQ 需手动配置 DLX Exchange + DLQ Queue，且有序性和大消息处理不如 RocketMQ 直接

**追问(b)：如果不引入 MQ，同步需要多久？**

无 MQ 串行方案（pull → batch INSERT → next pull）：
- 每批 5000 条拉取 ≈ 50ms，批量写入（5×1000条批次）≈ 125ms，单页合计 175ms
- 单域单日（20 万条，40 页）：40 × 175ms = **7 秒**
- 单域 5 天（串行日期）：5 × 7s = **35 秒**
- 无 MQ 场景 20 个 PowerJob 任务各自独立串行：Wall Time ≈ 35 秒（看起来不慢）

但核心问题不是速度，而是**耦合与可靠性**：
1. DB 写入阻塞拉取线程，DB 瞬时抖动直接中断同步，需重拉全量
2. 20 个域同时写库，并发 INSERT 峰值打满连接池，等待超时
3. 进程重启/宕机后已拉取未写库的数据丢失，只能整任务重跑
4. 无法做到消费失败的精细重试（只能重跑整个 domain+date 任务）

---

**Q3：幂等是怎么设计的？为什么要两层保障？**

**A：**

第一层：**全量覆盖**。每次同步开始前先 `DELETE FROM fault_record WHERE domain=? AND data_date=?`，把该 domain+date 的旧数据全部清掉，然后重新插入。这样无论 PowerJob 任务重跑多少次，最终结果都是最新一次拉取的数据，天然幂等。

第二层：**INSERT IGNORE + 唯一索引**。在 `(domain, data_date, rank)` 上建联合唯一索引，INSERT 时用 INSERT IGNORE。当 MQ 消息因网络抖动重投时，Consumer 会重新执行 batchInsert，但已经入库的记录不会被重复写入（唯一索引冲突时 IGNORE 跳过）。

为什么两层都要？
- 只有第一层（delete+insert）：MQ 消息重投时，DELETE 已经在第一轮执行过了，第二轮消费的 INSERT 仍会产生重复。
- 只有第二层（唯一索引）：如果历史数据发生了修改（同一 domain+data_date+rank 的 fault_detail 变了），INSERT IGNORE 会静默跳过，导致旧数据不被更新。全量 DELETE 则确保了数据的新鲜度。

两层配合：DELETE 保鲜度，IGNORE 防重复。

---

**Q4：DELETE 在拉取前执行，中间是否有数据空窗口？怎么看这个问题？**

**A：**

有空窗口。DELETE 执行后，MQ 消息还没消费完之前，这段时间查询该 domain+date 的数据会是空的或不完整的。

我们认为这是可以接受的，理由是：
1. 这是**批处理场景**，不是实时查询接口，每日凌晨低峰期执行，没有高并发读请求
2. 业务上对同步中间状态的数据本身就没有强一致性要求
3. sync_task_record 表记录了同步状态，查询方可以通过检查 status 字段判断数据是否可用

如果业务不能接受空窗口，可以考虑：
- 双表切换方案（写影子表，切换完再换别名）
- 或者只做 UPSERT（INSERT INTO ... ON DUPLICATE KEY UPDATE），但这样需要对旧数据删除逻辑单独处理

---

**Q5：线程池参数是怎么定的？CallerRunsPolicy 是什么，为什么选它？**

**A：**

线程池配置：核心线程数 = 最大线程数 = 20，等待队列 `ArrayBlockingQueue(100)`，拒绝策略 `CallerRunsPolicy`。

线程池的作用有两层：
1. **日期级并发**：每个 PowerJob 任务处理单个领域的 5 个日期，5 个 CompletableFuture 并发提交到 pool
2. **多域共享限速**：PowerJob 同时调起 20 个域任务时，所有任务实例共享同一个 `syncExecutor`，pool 充当全局并发限速器，避免所有域同时发起大量 DB 写入打爆连接池

领域级并行由 PowerJob 调度多个任务实例实现，不由线程池控制。线程池核心=最大=20，设置成相同值是为了避免线程数在 core 和 max 之间频繁扩缩，减少线程创建销毁开销。

为什么 `ArrayBlockingQueue` 而不是 `LinkedBlockingQueue`：`LinkedBlockingQueue` 默认无界，队列永远不会满，任务会无限堆积，可能导致 OOM。`ArrayBlockingQueue(100)` 有上限，是有界队列。

为什么选 `CallerRunsPolicy`：当多个域任务争抢线程池导致 pool 满且队列满时，该策略让**提交任务的线程（PowerJob worker 线程）自己来执行这个任务**。PowerJob 执行线程被占用时会暂停继续提交新任务，相当于对 PowerJob worker 产生了**自然的背压**——不需要额外的流控代码，系统自动降速。如果用 `AbortPolicy`（抛异常）或 `DiscardPolicy`（丢任务），任务会直接失败，是不可接受的。

---

**Q6：sync_task_record 的 incrementCompletedBatch 为什么用一条 SQL 搞定？不用分布式锁吗？**

**A：**

SQL 如下：
```sql
UPDATE sync_task_record
SET completed_batch_count = completed_batch_count + 1,
    status = CASE WHEN completed_batch_count + 1 >= #{batchCount} THEN 'SUCCESS' ELSE status END
WHERE domain = #{domain} AND data_date = #{dataDate} AND status = 'MESSAGES_SENT'
```

MySQL 的 `UPDATE` 是行级锁保护的原子操作。多个 Consumer 并发执行这条 SQL 时，MySQL 会串行化对同一行的修改。`completed_batch_count = completed_batch_count + 1` 是在数据库层面计算的，不是先 SELECT 再 UPDATE（那样才会有并发问题）。所以这里天然是线程安全的，不需要额外的分布式锁。

分布式锁的代价比较大（需要依赖 Redis 或 ZooKeeper，网络开销，锁超时处理），能用数据库原子操作解决的问题，没必要引入。

---

**Q7：RocketMQ 的 DLQ（死信队列）是怎么工作的？**

**A：**

RocketMQ 的消费重试机制：Consumer 消费失败（onMessage 抛异常）后，Broker 会按照指数退避的间隔重新投递该消息。我们设置了 `maxReconsumeTimes = 3`，即最多重投 3 次。

第 3 次还是失败后，RocketMQ 不会再重投，而是把这条消息发送到一个特殊的 Topic：`%DLQ%{consumerGroup}`，即 `%DLQ%fault-data-sync-consumer`。这就是死信队列（Dead Letter Queue）。

我们的 `FaultDataDlqConsumer` 订阅了这个 DLQ Topic，收到消息后：
1. 将 sync_task_record 状态更新为 FAILED，记录错误信息
2. 预留了告警钩子（可接钉钉/邮件/PagerDuty）

这样做的好处是：失败的消息不会被无限重试拖慢正常消息的消费，同时也不会无声无息地丢失，而是有明确的失败记录和告警出口。

---

**Q：拉取阶段中途报错（共8批数据，第4批拉取失败），系统会怎样？下次重跑会从第4批续拉吗？**

**A：**

**不会续拉，当前设计无断点续传，重跑从头来。**

具体发生的事情：

1. 前3批已成功发到 MQ，消费端开始消费并写库
2. 第4批 `client.pull()` 抛异常，`syncDomainDate()` 捕获后将 sync_task_record 标记为 **FAILED**，`MESSAGES_SENT` 状态永远没有被设置，`batchCount` 也没有记录
3. 前3批的 MQ 消息仍然存在，Consumer 会正常消费并写入 fault_record；但 `incrementCompletedBatch` 的 SQL 有 `WHERE status = 'MESSAGES_SENT'`，此时状态是 FAILED，UPDATE 影响行数为 0——**批次计数不会推进，任务不会变成 SUCCESS**
4. PowerJob 收到 `ProcessResult(false, ...)` → 触发任务级重试

**重跑过程**：
- `DELETE fault_record WHERE domain=? AND data_date=?` → 清掉已入库的前3批数据
- `lastRank = 0`，从头拉取，重新完整拉取全部8批
- 前3批的老 MQ 消息若此时还在队列，Consumer 重新消费时 INSERT IGNORE 跳过已存在记录或正常补插，不影响正确性

**局限**：无法从断点第4批续拉。若是顶峰场景第195批（共200批）失败，也要重拉全部200批。
可通过在 sync_task_record 中记录 `last_successful_rank` 字段实现续拉，但每日批处理场景下重跑代价可接受，当前未引入该复杂度。

---

**Q：MQ 消费侧入库失败（前4批成功入库，第5批发送成功但 Consumer 写库失败），会发生什么？**

**A：**

**pull 循环不感知消费侧结果，8批数据会全部发出，然后 MQ 重试兜底，最终 PowerJob 重跑托底。**

详细过程：

1. pull 循环继续，第5批 MQ 发送成功后继续拉取第6、7、8批，全部发出；sync_task_record → **MESSAGES_SENT(8)**
2. Consumer 消费第5批时 `batchInsert` 抛异常（如 DB 连接断、行锁超时）→ Consumer 向 Broker 返回消费失败
3. RocketMQ 按指数退避重投，最多重试3次；**第1-4批和第6-8批是独立消息，并行消费不受影响**，正常写库并调用 `incrementCompletedBatch`
4. 若第5批3次重试均失败 → 进入 DLQ（`%DLQ%fault-data-sync-consumer`）
5. `FaultDataDlqConsumer` 消费后将 sync_task_record → **FAILED**，记录错误信息并触发告警钩子

**最终状态**：fault_record 有7批（35000条）数据，第5批5000条缺失；sync_task_record = FAILED，completedBatch 停留在某个中间值（取决于 DLQ 处理时其他批次的消费进度）

**解决方案**（PowerJob 任务重跑）：
- DELETE 清掉已有7批数据
- 重新拉取全部8批，发8条 MQ 消息
- 8批全部消费成功 → SUCCESS

**两种场景的共同结论**：

| 层级 | 负责的故障类型 | 代价 |
|------|--------------|------|
| MQ 自动重试（3次） | 瞬时故障（DB 抖动、短暂连接失败） | 低，仅重投单条消息 |
| PowerJob 任务重跑 | 持久故障（DB 宕机、Bug修复后补跑） | 需重拉全量数据 |
| DELETE + INSERT IGNORE | 幂等兜底，确保任意次重跑结果正确 | 有短暂空窗口（可接受） |

---

**Q8：批量 INSERT 为什么快？MyBatis 的 foreach 批量插入有什么注意事项？**

**A：**

逐条 INSERT 每次都要和数据库建立网络交互、解析 SQL、获取锁、刷盘，1000条就有 1000次网络往返。批量 INSERT 把 1000 条合并成一条 SQL，只有 1 次网络往返 + 1 次 SQL 解析 + 减少事务提交次数，速度差距可以是几十倍。

我们 JDBC URL 里配置了 `rewriteBatchedStatements=true`，这让 MySQL JDBC 驱动把多条 INSERT 语句合并为一条多 VALUES 的语句，进一步减少协议开销。

注意事项：
1. **单批不要太大**：1000 条 × 几百字节/条 = 几百KB，可以接受。如果单批 10 万条，生成的 SQL 字符串会非常大，可能超出 MySQL 的 `max_allowed_packet` 限制，报 "Packet too large" 错误。我们选 1000 条/批就是在性能和安全之间取的平衡。
2. **避免全量 `<foreach>` 拼成一条 SQL 后再整体事务**：如果中间某条数据有问题，整批都会回滚。INSERT IGNORE 可以缓解这个问题。

---

### 【模块二：技术延伸题】

---

**Q9：说说 `CompletableFuture` 的用法，和 `Future` 相比有什么改进？**

**A：**

`Future` 的问题：
- `future.get()` 是阻塞的，调用方线程会一直等
- 不支持链式操作（比如"任务A完成后执行任务B"）
- 多个 Future 组合很麻烦（等所有完成、等任意一个完成）

`CompletableFuture` 的改进：
- `CompletableFuture.supplyAsync(() -> ..., executor)`：指定线程池异步执行
- `allOf(...).join()`：等待所有任务完成，非阻塞地组合
- 链式操作：`.thenApply()`, `.thenCompose()`, `.exceptionally()` 等
- 异常处理更优雅，可以在 chain 中捕获和转换

我们在 `FaultDataSyncJob` 里用了：
```java
List<CompletableFuture<String>> futures = ...;
// 提交100个任务到线程池
futures.stream().map(f -> f.get()).collect(toList());
// 等待所有完成后统计失败数
```

实际上更标准的写法是用 `CompletableFuture.allOf(futures.toArray(...)).join()`，然后遍历每个 future 的结果。

---

**Q10：MySQL 的联合索引 `(domain, data_date, rank)` 是怎么生效的？**

**A：**

联合索引遵循**最左前缀匹配**原则。`(domain, data_date, rank)` 这个索引可以有效支持：
- `WHERE domain = ?`
- `WHERE domain = ? AND data_date = ?`
- `WHERE domain = ? AND data_date = ? AND rank = ?`（精确匹配）
- `WHERE domain = ? AND data_date = ? AND rank > ?`（范围查询，rank 列用上了索引范围扫描）

我们的翻页查询 `WHERE domain=? AND data_date=? AND rank > lastRank ORDER BY rank LIMIT 5000` 完整利用了这个索引，走索引范围扫描，效率很高。

同时这个联合唯一索引也保证了 `(domain, data_date, rank)` 三元组的唯一性，作为 INSERT IGNORE 的依据。

**追问：为什么不直接用 (domain, data_date) 做唯一索引？**

因为同一个 domain+date 下有多条记录（每条 rank 不同），`(domain, data_date)` 不是唯一的，无法作为唯一索引。rank 是在 domain+date 范围内唯一的排序序号，三个字段组合才是全局唯一的。

---

**Q11：PowerJob 和 XXL-Job 有什么区别？为什么选 PowerJob？**

**A：**

| 对比维度 | XXL-Job | PowerJob |
|----------|---------|---------|
| 任务分发 | 单机执行（一台Worker跑） | 支持 MapReduce 分布式计算 |
| 执行器注册 | 手动注册 | 自动注册 |
| 任务参数 | 简单字符串 | 结构化 instanceParams，支持上下文传递 |
| 工作流 | 基本支持 | 原生工作流编排 |
| 社区 | 成熟，中文文档丰富 | 相对较新 |
| 控制台 | 简洁 | 功能更丰富 |

选 PowerJob 的原因：我们需要在任务参数里传一个领域列表的 JSON，PowerJob 的 `instanceParams` 对结构化参数支持更好。另外 PowerJob 的 Worker 嵌入式集成方式（Spring Boot Starter）对我们的代码侵入性小，只需要实现 `BasicProcessor` 接口即可。

对于简单的定时任务，XXL-Job 也完全够用，社区更成熟，文档更好找。

---

**Q：为什么每个领域配置独立的 PowerJob 任务，而不是一个任务传入所有领域列表？**

**A：**

独立任务的优势：
1. **失败隔离**：domain_a 的任务失败重试，不影响 domain_b ~ domain_t 已完成的运行
2. **独立调度配置**：不同领域可以设置不同的 cron 表达式、超时时间、重试策略（部分领域数据延迟更长，可配置不同 syncDays）
3. **PowerJob 任务级重试语义清晰**：PowerJob 重跑 domain_a 的任务时，仅重新处理 domain_a，避免无谓地重处理其他领域
4. **职责单一**：处理器只关心"拿到一个 domain，把5天数据同步好"，逻辑简洁，易于单元测试

代价：PowerJob 控制台需维护 20 个任务配置；但领域数量是稳定的，运维成本可控。

---

**Q12：RocketMQ 是如何保证消息不丢失的？**

**A：**

分三个环节看：

**① 生产端**：`syncSend`（同步发送）会等待 Broker 返回 ACK，确认消息已写入 Broker 才返回。我们配置了 `retryTimesWhenSendFailed=2`，失败会自动重试 2 次。如果用 `asyncSend` 或 `oneway`，可靠性就弱一些。

**② Broker 端**：Broker 可以配置同步刷盘（`SYNC_FLUSH`）或异步刷盘（`ASYNC_FLUSH`）。同步刷盘在消息写入磁盘后才 ACK，可靠性高但性能低；异步刷盘先 ACK 后异步写盘，性能高但宕机可能丢最后一批消息。生产环境高可靠场景用 Master-Slave 同步复制 + 同步刷盘。

**③ 消费端**：Consumer 必须在真正处理完消息（数据写库成功）后才 ACK。如果 onMessage 抛异常，RocketMQ 认为消费失败，会重新投递。我们的代码里，batchInsert 如果抛异常会往上抛，Consumer 框架捕获到后会重新投递，不会丢。

---

**Q13：`@Transactional` 事务注解你了解吗？我们的 batchInsert 需要加事务吗？**

**A：**

`@Transactional` 基于 Spring AOP，通过动态代理（JDK 代理或 CGLIB）在方法调用前后开关事务。常见踩坑：
- 同类内部方法调用，事务不生效（因为没走代理）
- `private` 方法加 `@Transactional` 不生效
- 抛受检异常（Checked Exception）默认不回滚，需要 `rollbackFor = Exception.class`

关于 batchInsert 加不加事务：

INSERT IGNORE 本身每条语句都是自动提交的，如果不加 `@Transactional`，1000 条中间某条失败不会回滚前面的。我们用的是 INSERT IGNORE，失败会跳过而不是抛异常，所以不加事务也没问题。

但如果 batchInsert 中途真的抛出异常（比如 DB 连接断了），MQ 会重试整条消息，INSERT IGNORE 保证已插入的不会重复，所以最终一致性有保障。

如果业务要求"这1000条要么全成功要么全不成功"，就需要加 `@Transactional`，但对于日志类数据，部分成功是可以接受的。

---

**Q14：Java 中 `List` 和 `ArrayList` 的区别？`CopyOnWriteArrayList` 是什么场景用的？**

**A：**

`List` 是接口，`ArrayList` 是它的实现类，基于动态数组。这两个是接口和实现的关系，没有可比性，应该问 `ArrayList` vs `LinkedList`。

`ArrayList` vs `LinkedList`：
- ArrayList：随机访问 O(1)，中间插入删除 O(n)，内存连续，Cache 友好
- LinkedList：随机访问 O(n)，头尾插入删除 O(1)，每个节点有前后指针，内存开销大

`CopyOnWriteArrayList`：写操作时复制整个数组（写时复制），读操作不加锁。适合**读多写少**的场景，比如配置列表、观察者列表。缺点是写操作内存开销大，不适合频繁写的场景，也不保证读到最新写入的数据（读的是老数组的快照）。

我们的领域列表（domains）在 Job 启动后只读不写，如果要在多线程环境共享，用 `CopyOnWriteArrayList` 或 `Collections.unmodifiableList()` 包装都可以。

---

**Q15：说说你对 MQ 消息幂等消费的理解，有哪些实现方式？**

**A：**

幂等消费指：同一条消息被消费多次，最终结果和消费一次相同。

实现方式：

**① 数据库唯一索引**（我们用的方案）：把业务唯一键作为 DB 唯一索引，INSERT IGNORE / ON DUPLICATE KEY UPDATE，天然防重。适合写 DB 的场景。

**② 消息去重表**：消费前先查去重表（messageId → 处理状态），已处理的跳过，未处理的先插入去重记录再处理。但涉及两次 DB 操作，需要注意原子性。

**③ Redis SETNX**：以 messageId 为 key，SETNX（Set if Not eXists）成功才处理。高性能，但 Redis 不持久化时有风险，且 Redis 和 DB 的操作不是原子的。

**④ 状态机**：消费者维护状态，比如我们的 sync_task_record，`WHERE status = 'MESSAGES_SENT'` 限定了 UPDATE 只对特定状态生效，重复执行状态不匹配时 UPDATE 影响行数为 0，自然是幂等的。

选哪种取决于场景。对我们的批量写入场景，唯一索引 + INSERT IGNORE 最简单直接。

---

## 四、面试官可能的追问 & 压力问题

---

**压问1：你说 DELETE 之前有空窗口"可以接受"，如果产品经理说不可以接受怎么办？**

答：那需要改方案。可以考虑：
- **影子表切换**：数据写到 `fault_record_tmp`，写完后原子地 `RENAME TABLE fault_record TO fault_record_bak, fault_record_tmp TO fault_record`。MySQL 的 RENAME TABLE 是原子操作，读方在切换瞬间不会查到空数据。代价是需要维护影子表的建表/清理逻辑。
- **只增量 UPSERT**：不 DELETE，改用 `INSERT INTO ... ON DUPLICATE KEY UPDATE`，对已有数据就地更新。但需要额外处理"上游删除了某条记录"的场景（本方案无法感知删除）。
- 如果上游数据从不删除只新增/修改，UPSERT 是更好的选择，彻底没有空窗口。

**压问2：你的线程池大小写死了 20，如果某天领域增加到 50 个怎么办？**

答：线程池大小通过 `@Value("${fault-sync.thread-pool-size:20}")` 外部化配置，不是写死在代码里的。增加领域时同步调整配置即可，不需要改代码。但线程数不是越多越好，还要考虑服务器 CPU 核数、上游 API 的并发限制、DB 连接池大小的瓶颈。如果扩展到 50 个领域，可能需要评估是否拆分服务、或者用 PowerJob 的 MapReduce 分布式执行将任务分散到多台机器上。

**压问3：Mock 数据源 UUID 随机生成 deviceId，实际场景数据从哪来，接口挂了怎么办？**

答：Mock 仅用于 Demo 和本地测试。真实场景，`FaultDataSourceClient` 接口会有对应的 HTTP 实现，通过 RestTemplate 或 Feign 调用上游接口。接口挂了的情况：`FaultSyncServiceImpl` 在 try-catch 里捕获异常，调用 `syncTaskRecordService.updateFailed()`，将 sync_task_record 置为 FAILED。PowerJob 可以配置任务级重试，到下个重试时间点再次触发整个 Job。同时可以结合告警，在 FAILED 状态出现时发送通知。

**压问4：为什么消费端用 `CollUtil.split` 分 1000 条一批，而不是直接把 5000 条一次 INSERT IGNORE？**

答：两个原因：
1. **SQL 长度限制**：5000 条 × 每条 VALUES(约 200 字节) = 约 1MB 的 SQL，可能触及 MySQL `max_allowed_packet`（默认 4MB，很多公司配置更小）。分批可以规避这个风险。
2. **事务粒度**：单批 1000 条，即使中间某批失败，重试也只需要重执行这 1000 条，而不是重执行全部 5000 条。

---

## 五、一句话速记（面试前复习用）

| 知识点 | 一句话 |
|--------|--------|
| rank 游标 vs offset | offset 深翻页全表扫，游标每次走索引，耗时恒定 |
| MQ 解耦价值 | 拉取和写库速率不同，MQ 做缓冲，各自跑满速 |
| INSERT IGNORE 作用 | 唯一索引冲突时静默跳过，不抛异常，MQ重投幂等 |
| CallerRunsPolicy | 队列满时调用方线程自己跑，实现自然背压 |
| 各域独立 PowerJob 任务 | 领域级并行由 PowerJob 调度，处理器只关心单域5天，失败隔离粒度细 |
| DLQ | 超过最大重试次数的消息进死信队列，用于告警兜底 |
| 联合唯一索引三作用 | 翻页走索引+去重保障+幂等兜底 |
| incrementCompletedBatch 无锁 | MySQL UPDATE 行锁天然串行，SET x=x+1 原子 |
| CompletableFuture 优于 Future | 支持非阻塞组合、链式操作、统一异常处理 |
| 批量INSERT为什么快 | 减少网络往返 + 减少事务提交次数 + rewriteBatchedStatements |
| 幂等三层 | delete全量覆盖（重跑幂等）→ INSERT IGNORE（重投幂等）→ status机器（状态幂等） |
