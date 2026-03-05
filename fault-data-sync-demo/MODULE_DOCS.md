# Fault Data Sync Demo Module Documentation

## Overview
The fault-data-sync-demo module implements a reliable, large-scale daily fault-log synchronization pipeline. It pulls device-reported fault records from an upstream data source, publishes them to RocketMQ in batches, and persists them to MySQL — all driven by a PowerJob scheduled job.

**Module Name**: fault-data-sync
**Type**: Spring Boot Application
**Purpose**: Daily batch synchronization of fault records from upstream API to local MySQL, with MQ-backed reliability and full resync support for historical data
**Port**: 8083

---

## Background & Core Challenges

| Challenge | Solution |
|-----------|----------|
| Upstream API returns at most 5,000 records per call | rank-based cursor pagination |
| Peak daily volume per domain can reach 1,000,000+ records | Parallel domain processing + chunked MQ messaging |
| Sync reliability required | MQ automatic retry (maxReconsumeTimes=3) + DLQ alerting |
| Historical data may arrive up to 5 days late | Full-overwrite resync for D-1 through D-5 every run |

---

## Architecture

### Overall Sync Flow

```
PowerJob Server (per-domain daily trigger)
        │  (each domain has its own PowerJob task, all pointing to FaultDataSyncJob)
        ▼
FaultDataSyncJob.process()
        │
        ├── Parse single domain from instanceParams (e.g. {"domain":"domain_a","syncDays":5})
        │
        ├── Build 5 sync dates (D-1 to D-syncDays)
        │
        ├── Submit 5 CompletableFutures to shared bounded thread pool (20 threads)
        │   (20 concurrent PowerJob tasks share the same syncExecutor as a global rate limiter)
        │
        │   [each date task] FaultSyncServiceImpl.syncDomainDate(domain, date)
        │           │
        │           ├── 1. sync_task_record → RUNNING
        │           ├── 2. DELETE fault_record WHERE domain=? AND data_date=?
        │           ├── 3. loop:
        │           │     a. pull(domain, date, lastRank, 5000) from data source
        │           │     b. wrap as FaultDataBatchMessage
        │           │     c. FaultDataProducer.sendBatch(...)
        │           │     d. lastRank = max(response[].rank)
        │           │     e. if response.size < 5000 → break
        │           └── 4. sync_task_record → MESSAGES_SENT (batchCount recorded)
        │
        ▼
    CompletableFuture.allOf — wait all tasks, collect success/fail stats
        │
        ▼
    PowerJob returns ProcessResult

    ═══════════ Async MQ Consumption ═══════════

FaultDataConsumer.onMessage(FaultDataBatchMessage)
        │
        ├── Convert DTOs → Entities
        ├── CollUtil.split(entities, 1000) → loop batchInsert (INSERT IGNORE)
        └── syncTaskRecordService.incrementCompletedBatch()
              └── if completedBatchCount == batchCount → status = SUCCESS

FaultDataDlqConsumer  (%DLQ%fault-data-sync-consumer)
        └── mark sync_task_record → FAILED, log error (alert hook)
```

### Package Structure

```
org.cabbage.codedemo.faultdatasync/
├── FaultDataSyncDemoApplication.java
├── job/
│   └── FaultDataSyncJob.java              # PowerJob BasicProcessor
├── service/
│   ├── FaultSyncService.java
│   ├── SyncTaskRecordService.java
│   └── impl/
│       ├── FaultSyncServiceImpl.java      # Core loop: delete → pull → MQ send
│       └── SyncTaskRecordServiceImpl.java # Full status lifecycle management
├── mq/
│   ├── producer/
│   │   └── FaultDataProducer.java
│   └── consumer/
│       ├── FaultDataConsumer.java         # INSERT IGNORE + progress tracking
│       └── FaultDataDlqConsumer.java      # DLQ → FAILED + alert hook
├── client/
│   ├── FaultDataSourceClient.java         # Upstream API interface
│   └── MockFaultDataSourceClient.java     # Demo mock (configurable data volume)
├── entity/
│   ├── FaultRecordEntity.java
│   └── SyncTaskRecordEntity.java
├── mapper/
│   ├── FaultRecordMapper.java
│   └── SyncTaskRecordMapper.java
├── model/
│   ├── FaultRecordDTO.java                # Upstream response DTO
│   └── FaultDataBatchMessage.java         # MQ message body
├── enums/
│   └── SyncStatus.java                    # PENDING/RUNNING/MESSAGES_SENT/SUCCESS/FAILED
├── config/
│   └── SyncThreadPoolConfig.java          # Bounded ThreadPoolExecutor (CallerRunsPolicy)
└── common/
    └── Result.java                         # Unified response wrapper
```

---

## Database Design

### fault_record — Fault Record Table

```sql
CREATE TABLE fault_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain      VARCHAR(100) NOT NULL       COMMENT '数据领域',
    data_date   DATE NOT NULL               COMMENT '数据所属日期',
    rank        INT NOT NULL                COMMENT '当天唯一排序值',
    fault_type  VARCHAR(200)                COMMENT '故障类型',
    device_id   VARCHAR(100)               COMMENT '设备ID',
    fault_detail TEXT                       COMMENT '故障详情',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_domain_date_rank (domain, data_date, rank)
) ENGINE=InnoDB;
```

### sync_task_record — Sync Task Status Table

```sql
CREATE TABLE sync_task_record (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain                VARCHAR(100) NOT NULL,
    data_date             DATE NOT NULL,
    status                VARCHAR(20) NOT NULL,   -- PENDING/RUNNING/MESSAGES_SENT/SUCCESS/FAILED
    batch_count           INT DEFAULT 0,           -- total MQ batches sent
    completed_batch_count INT DEFAULT 0,           -- batches successfully consumed
    total_records         INT DEFAULT 0,
    retry_count           INT DEFAULT 0,
    error_message         VARCHAR(500),
    start_time            DATETIME,
    end_time              DATETIME,
    create_time           DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_domain_date (domain, data_date)
) ENGINE=InnoDB;
```

### sync_task_record State Machine

```
                       Task triggered
                            │
                            ▼
                       ┌─────────┐
                       │ PENDING │  Record upserted at syncDomainDate() entry
                       └────┬────┘
                            │  DELETE executed, pull loop starts
                            ▼
                       ┌─────────┐
                       │ RUNNING │  DELETE done; rank-cursor loop in progress
                       └────┬────┘
               ┌────────────┴─────────────┐
       All batches pulled            pull() or sendBatch()
       & sent successfully           throws exception
               │                          │
               ▼                          ▼
      ┌──────────────┐              ┌────────┐
      │ MESSAGES_SENT│              │ FAILED │ ←── DLQ Consumer also
      │ batchCount=N │              │        │     writes FAILED directly
      └──────┬───────┘              └────────┘
             │  Consumer calls incrementCompletedBatch per batch:
             │  WHERE status='MESSAGES_SENT' (guard: FAILED tasks ignored)
             │
             ├── completed < N  →  stay MESSAGES_SENT
             │
             └── completed == N
                          │
                          ▼
                     ┌─────────┐
                     │ SUCCESS │  All batches consumed and persisted
                     └─────────┘
```

| Status | Meaning | Set by |
|--------|---------|--------|
| PENDING | Record initialised, awaiting execution | `syncDomainDate()` entry upsert |
| RUNNING | Producer loop executing (data window is empty during this phase) | After DELETE completes |
| MESSAGES_SENT | All MQ messages sent; `batchCount` recorded; awaiting consumer | End of pull loop |
| SUCCESS | All batches consumed and written to DB | `incrementCompletedBatch` atomic UPDATE |
| FAILED | Producer exception **or** DLQ consumer triggered | Exception catch / `FaultDataDlqConsumer` |

**Key guard**: `incrementCompletedBatch` uses `WHERE status = 'MESSAGES_SENT'`, so once a task is FAILED the consumer's progress updates become no-ops — preventing a partially-consumed failed task from accidentally flipping to SUCCESS.

---

## Key Design Decisions

### Pagination Strategy
- **rank cursor**: each request passes `lastRank`; upstream returns records with `rank > lastRank`
- **Stop condition**: `response.size() < pageSize (5000)` signals last page
- No offset-based pagination to avoid deep-scan performance issues on large datasets

### Idempotency (layered)

| Layer | Mechanism |
|-------|-----------|
| Primary | Full overwrite: DELETE before INSERT for each domain+date |
| Safety net | INSERT IGNORE + unique index `(domain, data_date, rank)` |

This handles both PowerJob task re-runs and MQ message redelivery.

### Parallelism & Back-pressure
- **Domain-level parallelism**: each domain has its own PowerJob task; PowerJob concurrently schedules up to 20 task instances
- **Date-level parallelism**: each task instance submits 5 `CompletableFuture` tasks (D-1 to D-syncDays) to a shared `syncExecutor`
- `ThreadPoolExecutor(20, 20, 60s, ArrayBlockingQueue(100), CallerRunsPolicy)`
  - Pool serves as a **global rate limiter** across all concurrent domain tasks
  - `CallerRunsPolicy`: when pool + queue are saturated, the PowerJob worker thread executes the task itself — natural back-pressure on the PowerJob executor, no OOM risk

### Progress Tracking
`sync_task_record.incrementCompletedBatch` uses a single atomic UPDATE:
```sql
UPDATE sync_task_record
SET completed_batch_count = completed_batch_count + 1,
    status = CASE WHEN completed_batch_count + 1 >= #{batchCount} THEN 'SUCCESS' ELSE status END,
    end_time = CASE WHEN completed_batch_count + 1 >= #{batchCount} THEN NOW() ELSE end_time END
WHERE domain = #{domain} AND data_date = #{dataDate} AND status = 'MESSAGES_SENT'
```

### MQ Reliability
- **Retry**: `maxReconsumeTimes = 3` on `FaultDataConsumer`
- **DLQ**: `FaultDataDlqConsumer` subscribes to `%DLQ%fault-data-sync-consumer`, marks task `FAILED`
- **Producer**: `syncSend` with `retryTimesWhenSendFailed = 2`

---

## Idempotency & Consistency Analysis

| Scenario | Handling |
|----------|---------|
| PowerJob full task re-run | Re-delete + re-insert, naturally idempotent |
| MQ message redelivery (same 5k batch) | `INSERT IGNORE` + unique index prevents duplicates |
| Partial DB insert failure | MQ retries the entire batch; `INSERT IGNORE` skips already-inserted rows |
| Upstream API timeout/error | Exception propagates → task marked `FAILED`; PowerJob task-level retry |
| DLQ (exceeded max retries) | `FaultDataDlqConsumer` marks `FAILED`; extendable to alerting |

---

## Failure Scenarios

### Pull-phase failure (e.g. batch 4 of 8 throws exception)

1. Batches 1–3 are already in RocketMQ; consumers will INSERT IGNORE them into DB
2. Exception caught in `syncDomainDate()` → `sync_task_record` → **FAILED**; `MESSAGES_SENT` is never set
3. `incrementCompletedBatch` has `WHERE status = 'MESSAGES_SENT'` — since status is FAILED, all consumer updates are no-ops; task never reaches SUCCESS
4. PowerJob task retry: DELETE all data for this domain+date → re-pull from `lastRank = 0`
5. **No checkpoint resume**: the current design always restarts from the beginning; previously consumed batches 1–3 are deleted and re-pulled

### Consume-phase failure (e.g. batch 5 INSERT throws exception)

1. Pull loop is unaware of consumer results; all 8 batches are sent → `sync_task_record` → **MESSAGES_SENT(8)**
2. Consumer fails for batch 5 → RocketMQ exponential back-off retry (×3); other batches consume independently
3. After 3 retries: batch 5 routed to DLQ (`%DLQ%fault-data-sync-consumer`)
4. `FaultDataDlqConsumer` → `sync_task_record` → **FAILED** + alert hook
5. Final state: 7 batches in DB (35 000 rows), batch 5 missing; status = FAILED
6. PowerJob task retry: DELETE + full re-pull + full re-consume → SUCCESS

### Recovery layer summary

| Layer | Handles | Cost |
|-------|---------|------|
| MQ auto-retry (×3) | Transient faults (DB blip, brief connection loss) | Low — single message only |
| PowerJob task retry | Persistent faults (DB down, bug-fix re-run) | Full re-pull required |
| DELETE + INSERT IGNORE | Idempotency guarantee for any number of re-runs | Brief empty window (acceptable) |

---

## Technology Stack

- **Framework**: Spring Boot 3.5.6
- **Job Scheduler**: PowerJob 4.3.6 (Worker)
- **Message Queue**: RocketMQ 2.3.0 (via rocketmq-spring-boot-starter)
- **ORM**: MyBatis-Plus 3.5.6
- **Database**: MySQL 8+
- **Utilities**: Hutool 5.8.27, Lombok

---

## Configuration Reference

```yaml
fault-sync:
  sync-days: 5                          # resync D-1 through D-5 (overridable per task via instanceParams)
  thread-pool-size: 20
  batch-size: 1000                      # DB insert chunk size
  page-size: 5000                       # upstream API page size
  mock-total-per-domain: 20000          # mock data volume (set 1000000 for peak test)
  mq:
    topic: fault-data-sync-topic
    consumer-group: fault-data-sync-consumer
```

---

## Running Locally

### Prerequisites
1. MySQL 8+ running at `127.0.0.1:3306`, database `code_demo`
2. RocketMQ Name Server at `127.0.0.1:9876`
3. PowerJob Server at `127.0.0.1:7700`

### Steps
```bash
# 1. Init DB schema
mysql -u root -p code_demo < src/main/resources/sql/init.sql

# 2. Start the application
cd fault-data-sync-demo
./mvnw spring-boot:run

# 3. Register one PowerJob task per domain in PowerJob console
#    Handler: org.cabbage.codedemo.faultdatasync.job.FaultDataSyncJob
#    instanceParams (required): {"domain":"domain_a","syncDays":5}
#    Repeat for domain_b ... domain_t (20 tasks total)

# 4. Trigger manually and observe:
#    - PowerJob logs: single domain processing 5 dates in parallel
#    - RocketMQ console: messages in fault-data-sync-topic
#    - MySQL: fault_record rows inserted, sync_task_record status → SUCCESS
```

### Idempotency Verification
Trigger the job twice for the same date range — the second run deletes and rewrites data cleanly, no duplicates in `fault_record`.

### Peak Load Simulation
Set `fault-sync.mock-total-per-domain: 1000000` and observe multi-batch MQ flow without memory pressure.
