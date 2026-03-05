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

---

### Request 2: 新增面试准备文档
- **Date**: 2026-02-26
- **Request Details**: 为 `fault-data-sync-demo` 模块新增面试准备文档，覆盖高级开发/架构师视角下的常见问答，包含项目自述话术、技术问答（rank游标、MQ解耦、幂等设计、线程池、DLQ等）及延伸题。
- **Modification Made**:
  - 新增 `INTERVIEW_PREP.md`，包含：
    - Section一：项目介绍自述稿 + 量化指标表
    - Section二：15+ 道面试问答（含标准答案和追问应对）
    - Section三：压力追问参考答案
    - Section四：一句话速记表
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md` (新增)
- **Status**: Completed

---

### Request 4: 面试文档新增故障场景问答
- **Date**: 2026-03-05
- **Request Details**: 新增两个关于系统故障场景的面试问答：
  1. 拉取阶段中途失败（如第4批拉取报错），系统行为和下次重跑是否会断点续拉
  2. MQ 消费侧入库失败（第5批发送成功但 Consumer 写库异常），系统行为和解决方案
- **Modification Made**:
  - `INTERVIEW_PREP.md` 在 Q7（DLQ）之后新增两道 Q，覆盖：
    - 拉取失败场景：sync_task_record 停留 FAILED、incrementCompletedBatch 无法推进、重跑时 DELETE 清理 + 从头重拉、无断点续传的局限及可能的优化方向
    - 消费入库失败场景：pull 循环不感知消费侧结果、MQ 重试3次→DLQ→FAILED、PowerJob 重跑托底、最终一致性保障
    - 两种场景的对比总结表（MQ重试/PowerJob重跑/DELETE幂等各自负责的故障层级）
  - `REQUESTS_AND_MODIFICATIONS.md` 新增本条 Request 4
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
- **Status**: Completed

---

### Request 3: 架构修正（单域处理器）+ 面试文档增补
- **Date**: 2026-03-05
- **Request Details**: 发现现有代码设计（单任务 + `jobParams` 传入所有域列表）与实际业务模型冲突。实际业务中每个领域在 PowerJob 中配置独立定时任务，处理器只负责单域5天同步。需同步修正代码、配置和文档。

  面试文档同时增补以下内容：
  - 一句话项目概括（HR/架构师视角）
  - ③段改为"各领域独立 PowerJob 任务，通用处理器单域并发"
  - Q2 追问：RocketMQ 选型理由 vs Kafka/RabbitMQ；无MQ方案对比分析
  - Q5 线程池：说明双层作用（日期级并发 + 多域共享限速）
  - 新增 Q：为什么各域独立配置 PowerJob 任务而非一个任务处理所有域
  - 一句话速记新增"各域独立 PowerJob 任务"条目

- **Modification Made**:
  - `FaultDataSyncJob`：删除 `parseDomains()/defaultDomains`，新增 `parseDomain()` 读取单个 domain，移除外层 domain 循环，处理器只负责单域 × syncDays 天
  - `application.yml`：删除 `fault-sync.domains` 列表（22行），领域配置下沉到 PowerJob 各任务的 instanceParams
  - `INTERVIEW_PREP.md`：增补七处内容（见上述请求详情）
  - `MODULE_DOCS.md`：同步更新架构流程图、Parallelism & Back-pressure 描述、Configuration Reference、Running Locally 步骤
  - `REQUESTS_AND_MODIFICATIONS.md`：补录 Request 2、Request 3

- **Files Modified**:
  - `fault-data-sync-demo/src/main/java/org/cabbage/codedemo/faultdatasync/job/FaultDataSyncJob.java`
  - `fault-data-sync-demo/src/main/resources/application.yml`
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
  - `fault-data-sync-demo/MODULE_DOCS.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
- **Status**: Completed
