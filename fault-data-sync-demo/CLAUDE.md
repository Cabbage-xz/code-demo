# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the module
./mvnw spring-boot:run

# Build (skip tests)
./mvnw package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=FaultSyncServiceImplTest
```

## Architecture

This module implements a fault-log full-overwrite resync pipeline using PowerJob + RocketMQ + MySQL.

### Data Flow

```
PowerJob (cron, per domain)
  └─ FaultDataSyncJob.process()
       └─ FaultSyncServiceImpl.syncDomainDate(domain, date)
            ├─ [first run]  DELETE by domain+date → pull all batches → sendBatch() per 5k records
            └─ [retry run]  query failed batches → re-pull only those → sendBatch()
                                                      ↓ RocketMQ
                                              FaultDataConsumer.onMessage()
                                                ├─ INSERT IGNORE in chunks of 1000
                                                ├─ syncBatchRecordService.markInsertSuccess()
                                                └─ syncTaskRecordService.incrementCompletedBatch()
                                                      ↓ (on maxReconsumeTimes=3 exceeded)
                                              FaultDataDlqConsumer.onMessage()
                                                ├─ syncBatchRecordService.markInsertFailed()
                                                └─ syncTaskRecordService.updateFailed()  → triggers PowerJob retry
```

### State Machine (sync_task_record.status)

`RUNNING` → `MESSAGES_SENT` → `SUCCESS` / `FAILED`

- `MESSAGES_SENT`: producer finished sending all MQ batches; consumer may still be lagging.
- SUCCESS is set atomically by `checkAndMarkSuccessIfAllDone` (SQL: `completed_batch_count = batch_count`), called from both producer and consumer to handle race conditions.
- FAILED triggers PowerJob task-level retry. On retry, `hasSuccessBatch()` detects prior `sync_batch_record` SUCCESS rows and routes to `runRetrySync()` instead of re-doing the full DELETE.

### Key Design Decisions

**Rank-cursor pagination**: The upstream source assigns monotonically increasing `rank` per (domain, date). Pulls use `WHERE rank > lastRank LIMIT pageSize`. Cursor advances to `max(rank)` of each batch.

**Idempotency**: `fault_record` has `UNIQUE KEY uk_domain_date_rank (domain, data_date, rank)`. All inserts use `INSERT IGNORE`, making consumer retries safe.

**Batch-level retry granularity** (`sync_batch_record`):
- `pull_status=FAILED` → rank cursor broke; re-pull from `startRank` to end (all subsequent batches).
- `insert_status=FAILED` → single batch MQ delivery failed; re-pull just that batch.

**Thread pool** (`syncExecutor`): bounded `ThreadPoolExecutor(20, 20, queue=100, CallerRunsPolicy)`. Each PowerJob instance handles one domain; date-level parallelism (5 dates) uses `CompletableFuture` on this shared pool.

### PowerJob Configuration

Each domain requires a separate PowerJob task pointing to handler `org.cabbage.codedemo.faultdatasync.job.FaultDataSyncJob`.

instanceParams JSON:
```json
{"domain": "domain_a", "syncDays": 5}
```

`syncDays` defaults to `${fault-sync.sync-days:5}` if omitted. The job syncs D-1 through D-syncDays.

### Database Tables

- `fault_record` — main data table; DDL in `src/main/resources/sql/init.sql`
- `sync_task_record` — per-(domain, date) task status; same file
- `sync_batch_record` — per-batch pull/insert status; DDL in `src/main/resources/db/sync_batch_record.sql`

### Configuration Reference (`application.yml`)

| Property | Default | Purpose |
|---|---|---|
| `fault-sync.sync-days` | 5 | Days to sync (D-1 to D-N) |
| `fault-sync.thread-pool-size` | 20 | syncExecutor core/max threads |
| `fault-sync.page-size` | 5000 | Records per pull from source |
| `fault-sync.batch-size` | 1000 | DB INSERT chunk size |
| `fault-sync.mock-total-per-domain` | 20000 | Mock source total records (set to 1000000 for peak simulation) |
| `fault-sync.mq.topic` | `fault-data-sync-topic` | RocketMQ topic |
| `fault-sync.mq.consumer-group` | `fault-data-sync-consumer` | RocketMQ consumer group |

DLQ topic is automatically `%DLQ%fault-data-sync-consumer` (RocketMQ convention).

### Data Source Abstraction

`FaultDataSourceClient` is an interface. `MockFaultDataSourceClient` is the only implementation (demo). Replace with a real HTTP/RPC client by implementing the same interface.
