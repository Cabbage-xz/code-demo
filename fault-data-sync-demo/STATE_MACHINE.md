# 状态机流转文档

本文档说明 fault-data-sync-demo 模块中，数据从拉取到写库全流程的状态变更逻辑。

---

## 一、状态表概览

| 表 | 粒度 | 状态字段 |
|---|---|---|
| `sync_task_record` | domain + date（一次同步任务） | `status` |
| `sync_batch_record` | domain + date + batch_index（一个批次） | `pull_status` / `insert_status` |

---

## 二、sync_task_record 状态机

### 状态定义

```
PENDING → RUNNING → MESSAGES_SENT → SUCCESS
                                  ↘ FAILED
```

| 状态 | 含义 |
|---|---|
| `RUNNING` | 任务已启动，正在拉取并发送 MQ |
| `MESSAGES_SENT` | 生产者已发完所有 MQ 批次，消费者可能仍在处理 |
| `SUCCESS` | 所有批次均已写入 DB |
| `FAILED` | 拉取异常或 DLQ 触发失败，等待 PowerJob 任务级重试 |

### 转换触发点

#### `→ RUNNING`
- **方法**：`SyncTaskRecordServiceImpl.createOrUpdateRunning(domain, date)`
- **调用方**：`FaultSyncServiceImpl.syncDomainDate()` 入口处
- **行为**：
  - 首次：新建记录，status=RUNNING，startTime=now()
  - 重试：status→RUNNING，清空 batchCount / completedBatchCount，retry_count+1

#### `RUNNING → MESSAGES_SENT`
- **方法**：`SyncTaskRecordServiceImpl.updateMessagesSent(domain, date, batchCount)`
- **调用方**：`runFirstSync()` / `runRetrySync()` 在 pull 循环结束后调用
- **行为**：回填 batchCount，status→MESSAGES_SENT

#### `MESSAGES_SENT → SUCCESS`（两条路径，任一先到）

**路径 A — 消费者触发**（正常路径）
- **方法**：`SyncTaskRecordMapper.incrementCompletedBatch()`（原子 SQL）
- **调用方**：`FaultDataConsumer.onMessage()` 每批写库成功后调用
- **SQL 核心逻辑**：
  ```sql
  UPDATE sync_task_record
  SET completed_batch_count = completed_batch_count + 1,
      status = CASE WHEN completed_batch_count + 1 >= batch_count
                    AND batch_count > 0 THEN 'SUCCESS' ELSE status END,
      end_time = CASE WHEN completed_batch_count + 1 >= batch_count
                      AND batch_count > 0 THEN NOW() ELSE end_time END
  WHERE domain = #{domain} AND data_date = #{dataDate}
    AND status IN ('RUNNING', 'MESSAGES_SENT')
  ```
- **WHERE 包含 RUNNING**：防止消费者比生产者先完成（消费者超跑），导致状态卡在 RUNNING

**路径 B — 生产者补偿**（边界场景）
- **方法**：`SyncTaskRecordMapper.checkAndMarkSuccessIfAllDone()`
- **调用方**：生产者在 `updateMessagesSent()` 之后立即调用
- **触发条件**：所有消费者都已完成（completed_batch_count >= batch_count），但此时 status 已是 MESSAGES_SENT，路径 A 不会再被触发
- **SQL 核心逻辑**：
  ```sql
  UPDATE sync_task_record
  SET status = 'SUCCESS', end_time = NOW()
  WHERE domain = #{domain} AND data_date = #{dataDate}
    AND status = 'MESSAGES_SENT'
    AND batch_count > 0
    AND completed_batch_count >= batch_count
  ```

> **为什么需要两条路径？**
> 消费者和生产者异步并发。若消费者处理速度极快，最后一个 incrementCompletedBatch 可能在 updateMessagesSent 之前执行，此时 status 仍是 RUNNING，条件 `IN ('RUNNING','MESSAGES_SENT')` 能捕获，但 CASE 里 batch_count 此时为 0（还没有被回填），guard `batch_count > 0` 阻止了错误的 SUCCESS。待生产者后续调用 updateMessagesSent → checkAndMarkSuccessIfAllDone，才真正打上 SUCCESS。

#### `→ FAILED`
- **方法**：`SyncTaskRecordServiceImpl.updateFailed(domain, date, errorMessage)`
- **调用方（三处）**：
  1. `runFirstSync()` 的 pull/send 循环 catch 块
  2. `runRetrySync()` 的 pull/send 循环 catch 块
  3. `FaultDataDlqConsumer.onMessage()` —— MQ 超过最大重试次数（3次）进入 DLQ
- **行为**：status→FAILED，回填 errorMessage（截断至 500 字符），end_time=NOW()
- **后续**：PowerJob 检测到任务 FAILED 后触发任务级重试

---

## 三、sync_batch_record 状态机

每条记录有两个独立状态字段：

```
pull_status:   PENDING → SUCCESS / FAILED
insert_status: PENDING → SUCCESS / FAILED
```

### pull_status 转换

#### `PENDING → SUCCESS`
- **方法**：`SyncBatchRecordServiceImpl.markPullSuccess(domain, date, batchIndex, startRank, endRank, recordCount)`
- **调用方**：`runFirstSync()` / `runRetrySync()` 在 `sourceClient.pull()` 返回数据后
- **SQL**：`INSERT ... ON DUPLICATE KEY UPDATE`，回填 endRank、recordCount，pull_status→SUCCESS，insert_status→PENDING（重试场景下重置）

#### `PENDING → FAILED`
- **方法**：`SyncBatchRecordServiceImpl.markPullFailed(domain, date, batchIndex, startRank, errorMessage)`
- **调用方**：`runFirstSync()` / `runRetrySync()` 在 `sourceClient.pull()` 抛异常后
- **行为**：记录 startRank 供重试使用，pull_status→FAILED

### insert_status 转换

#### `PENDING → SUCCESS`
- **方法**：`SyncBatchRecordServiceImpl.markInsertSuccess(domain, date, batchIndex)`
- **调用方**：`FaultDataConsumer.onMessage()` 批次全部 INSERT IGNORE 完成后
- **行为**：insert_status→SUCCESS

#### `PENDING → FAILED`
- **方法**：`SyncBatchRecordServiceImpl.markInsertFailed(domain, date, batchIndex, errorMessage)`
- **调用方**：`FaultDataDlqConsumer.onMessage()` —— MQ 消费超过 3 次重试进入 DLQ
- **行为**：insert_status→FAILED，记录错误原因

### findFailed 查询（重试路由依据）
```sql
SELECT * FROM sync_batch_record
WHERE domain = #{domain} AND data_date = #{dataDate}
  AND (pull_status = 'FAILED' OR insert_status = 'FAILED')
ORDER BY batch_index
```
`runRetrySync()` 通过此查询决定哪些批次需要重新处理。

---

## 四、完整数据流转

### 4.1 首次同步（hasSuccessBatch = false）

```
PowerJob 触发 FaultDataSyncJob.process(domain, date)
  │
  ▼
syncDomainDate(domain, date)
  ├─ createOrUpdateRunning()          [sync_task_record] status → RUNNING
  │
  └─ runFirstSync(domain, date)
       ├─ DELETE fault_record WHERE domain+date（清空旧数据）
       │
       ├─ loop: while sourceClient.pull() 有数据
       │    ├─ pull(domain, date, lastRank, 5000)
       │    ├─ (成功) markPullSuccess()     [sync_batch_record] pull_status → SUCCESS
       │    ├─ (异常) markPullFailed()      [sync_batch_record] pull_status → FAILED
       │    │         updateFailed()        [sync_task_record]  status → FAILED → PowerJob 重试
       │    └─ sendBatch() → RocketMQ
       │
       ├─ updateMessagesSent(batchCount)   [sync_task_record] status → MESSAGES_SENT
       └─ checkAndMarkSuccessIfAllDone()   （补偿检查）
                                          [sync_task_record] status → SUCCESS（若消费者已全部完成）

  ──────── 异步 ────────

  FaultDataConsumer.onMessage(batch)
    ├─ INSERT IGNORE fault_record（分 1000 条一块）
    ├─ markInsertSuccess()              [sync_batch_record] insert_status → SUCCESS
    └─ incrementCompletedBatch()        [sync_task_record]  completed_batch_count++
                                         → status → SUCCESS（若为最后一批）

  FaultDataDlqConsumer.onMessage(batch)（MQ 重试 3 次后）
    ├─ markInsertFailed()               [sync_batch_record] insert_status → FAILED
    └─ updateFailed()                   [sync_task_record]  status → FAILED → PowerJob 重试
```

### 4.2 重试同步（hasSuccessBatch = true）

```
PowerJob 任务重试（retry_count 累加）
  │
  ▼
syncDomainDate(domain, date)
  ├─ createOrUpdateRunning()           [sync_task_record] status → RUNNING，retry_count+1
  │
  └─ runRetrySync(domain, date)
       ├─ findFailed()                 查询所有 pull/insert 失败的批次，按 batchIndex 排序
       │
       ├─ for each failedBatch:
       │    │
       │    ├─ [pull_status=FAILED]
       │    │   从 batch.startRank 重新拉取到末尾（含后续所有批次）
       │    │   ├─ while pull() 有数据
       │    │   │   ├─ markPullSuccess()   [sync_batch_record] pull_status → SUCCESS
       │    │   │   └─ sendBatch() → RocketMQ
       │    │   └─ （异常）markPullFailed() / updateFailed() → FAILED
       │    │
       │    └─ [insert_status=FAILED]
       │        仅重拉该批次（INSERT IGNORE 保证幂等）
       │        ├─ pull(startRank, endRank 范围内)
       │        ├─ markPullSuccess()       [sync_batch_record] pull_status/insert_status 重置
       │        └─ sendBatch() → RocketMQ
       │
       ├─ updateMessagesSent(sentBatchCount)  [sync_task_record] status → MESSAGES_SENT
       └─ checkAndMarkSuccessIfAllDone()

  ──────── 异步（同首次同步）────────
```

---

## 五、竞态与原子性保障

| 场景 | 问题 | 解决方案 |
|---|---|---|
| 消费者超跑（先于生产者完成） | incrementCompletedBatch 时 batch_count=0，不能判断是否完成 | `batch_count > 0` guard 阻止，生产者后续 checkAndMarkSuccessIfAllDone 兜底 |
| 多消费者并发 increment | 最后一次 +1 可能被多个线程同时执行 | 原子 CASE 语句在单条 UPDATE 中判断并翻转状态，无需应用层加锁 |
| 生产者和消费者同时尝试写 SUCCESS | 重复更新 | WHERE status = 'MESSAGES_SENT' 保证只有第一个执行成功，后续更新行数为 0 |
| incrementCompletedBatch 时任务已 FAILED | 多余的成功计数 | WHERE status IN ('RUNNING', 'MESSAGES_SENT') 排除 FAILED 状态 |

---

## 六、重试路由逻辑（runRetrySync 核心判断）

```
findFailed() 返回的批次列表，按 batchIndex 升序处理：

  pull_status = FAILED
  └─ 游标断裂：从 startRank 重拉到末尾
     （该批及后续所有批次的 MQ 消息均需重新发送）

  insert_status = FAILED（pull_status = SUCCESS）
  └─ 仅 MQ 消费失败：重拉该批次数据重新发 MQ
     （INSERT IGNORE 保证不重复写库）
```

---

## 七、关键幂等设计

- `fault_record` 有 `UNIQUE KEY uk_domain_date_rank(domain, data_date, rank)`，消费者统一使用 INSERT IGNORE
- `sync_batch_record` 使用 `INSERT ... ON DUPLICATE KEY UPDATE`，markPullSuccess 可安全重入
- 首次同步前执行 DELETE 清空旧数据，配合 INSERT IGNORE 实现全量覆写语义
