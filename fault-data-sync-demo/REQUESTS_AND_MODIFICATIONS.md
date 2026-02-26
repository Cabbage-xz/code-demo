# Fault Data Sync Demo Module - Requests & Modifications Log

## Overview
This document tracks requests made for the fault-data-sync-demo module and any modifications made in response to those requests. This helps maintain a history of changes and understand the evolution of the module.

## Entry Template
Use this template for each new entry:

### Request #[Number]: [Brief Description]
- **Date**: [YYYY-MM-DD]
- **Request Details**: [Full description of the request]
- **Modification Made**: [Description of changes implemented]
- **Files Modified**: [List of files changed]
- **Status**: [Open/In Progress/Completed]

---

## Request Log

### Request 1: Initial Module Design & Implementation
- **Date**: 2026-02-26
- **Request Details**: 设计并实现 `fault-data-sync-demo` 模块。背景：需要一个每日从原始数据源拉取设备上报故障日志并持久化到本地 MySQL 的数据同步模块。核心挑战包括：原数据源接口每次只能返回 5k 数据、顶峰时单领域日数据量可达百万级、需保证同步可靠性（MQ 重试保证批量原子插入）、需兼容历史 5 日数据延迟更新的重同步需求。

  **确认的设计决策**：

  | 维度 | 决策 |
  |------|------|
  | 接口翻页 | rank 游标（lastRank → 下一批 rank > lastRank） |
  | 拉取终止条件 | 返回数量 < 5000 |
  | MQ 消息粒度 | 每次 5k 拉取结果作为一条 MQ 消息 |
  | 历史重同步 | 全量覆盖（每日同步 D-1 至 D-5，每个 domain+date 先 delete 再 insert） |
  | 领域并行 | 20 个领域并行执行（有界线程池） |
  | 数据库表 | 所有领域共用一张 fault_record 表 |
  | 幂等保障 | 由全量覆盖策略保障（delete 在 pull 开始前） |
  | 失败恢复 | MQ 自动重试 N 次，超次后 DLQ 触发告警；PowerJob 任务级重试 |
  | 进度跟踪 | sync_task_record 表记录每个 domain+date 的同步状态 |
  | delete 时机 | 拉取前先 delete（有中间空窗口，可接受） |

- **Modification Made**: 从零实现完整模块，包含以下组件：
  - **数据库**：`fault_record`（含唯一索引）、`sync_task_record`（状态机）两张表
  - **Job 层**：`FaultDataSyncJob`（PowerJob BasicProcessor），CompletableFuture 并行，支持 jobParams 覆盖领域列表和同步天数
  - **Service 层**：`FaultSyncServiceImpl`（核心同步循环：delete → rank 游标翻页 pull → MQ send）、`SyncTaskRecordServiceImpl`（完整状态生命周期管理）
  - **MQ 层**：`FaultDataProducer`（syncSend）、`FaultDataConsumer`（INSERT IGNORE + CollUtil.split 分批 + incrementCompletedBatch）、`FaultDataDlqConsumer`（DLQ 死信消费，标记 FAILED，预留告警扩展点）
  - **Client 层**：`FaultDataSourceClient` 接口 + `MockFaultDataSourceClient`（可配置数据量，支持百万级模拟）
  - **配置**：`SyncThreadPoolConfig`（有界线程池，CallerRunsPolicy 背压）、`application.yml`（20 个默认领域，端口 8083）
  - **SQL**：`src/main/resources/sql/init.sql`（DDL 建表脚本）

  **关键实现细节**：
  - 幂等双保险：全量覆盖（delete+insert）为主策略 + INSERT IGNORE + 唯一索引为安全兜底
  - `incrementCompletedBatch` 使用单条原子 UPDATE + CASE WHEN 判断批次是否全部完成，无需应用层锁
  - DLQ topic 命名遵循 RocketMQ 规范：`%DLQ%fault-data-sync-consumer`
  - 使用 Hutool `CollUtil.split()` 替代 Guava `Lists.partition()`（hutool 为已有依赖）

- **Files Modified**:
  - `fault-data-sync-demo/pom.xml`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/FaultDataSyncDemoApplication.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/job/FaultDataSyncJob.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/service/FaultSyncService.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/service/SyncTaskRecordService.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/service/impl/FaultSyncServiceImpl.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/service/impl/SyncTaskRecordServiceImpl.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/mq/producer/FaultDataProducer.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/mq/consumer/FaultDataConsumer.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/mq/consumer/FaultDataDlqConsumer.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/client/FaultDataSourceClient.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/client/MockFaultDataSourceClient.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/entity/FaultRecordEntity.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/entity/SyncTaskRecordEntity.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/mapper/FaultRecordMapper.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/mapper/SyncTaskRecordMapper.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/model/FaultRecordDTO.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/model/FaultDataBatchMessage.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/enums/SyncStatus.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/config/SyncThreadPoolConfig.java`
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/common/Result.java`
  - `fault-data-sync-demo/src/main/resources/application.yml`
  - `fault-data-sync-demo/src/main/resources/mapper/FaultRecordMapper.xml`
  - `fault-data-sync-demo/src/main/resources/mapper/SyncTaskRecordMapper.xml`
  - `fault-data-sync-demo/src/main/resources/sql/init.sql`
  - `fault-data-sync-demo/MODULE_DOCS.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
- **Status**: Completed

[Future requests and modifications will be added here as they occur]
