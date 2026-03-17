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

---

### Request 4: 面试文档新增故障场景问答
- **Date**: 2026-03-05
- **Request Details**: 新增两个关于系统故障场景的面试问答：
  1. 拉取阶段中途失败（如第4批拉取报错），系统行为和下次重跑是否会断点续拉
  2. MQ 消费侧入库失败（第5批发送成功但 Consumer 写库异常），系统行为和解决方案
- **Modification Made**:
  - `INTERVIEW_PREP.md` 在 Q7（DLQ）之后新增两道 Q，覆盖：
    - 拉取失败场景：sync_task_record 停留 FAILED、incrementCompletedBatch 无法推进、重跑时 DELETE 清理 + 从头重拉、无断点续传的局限
    - 消费入库失败场景：pull 循环不感知消费侧结果、MQ 重试3次→DLQ→FAILED、PowerJob 重跑托底
    - 两种场景的对比总结表（MQ重试/PowerJob重跑/DELETE幂等各自负责的故障层级）
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
- **Status**: Completed

---

### Request 5: 面试文档新增系统架构图与完整链路图
- **Date**: 2026-03-05
- **Request Details**: 在面试文档中新增系统架构图（静态视图）和完整链路图（动态时序），原 Section 二/三/四 重新编号为三/四/五。
- **Modification Made**:
  - `INTERVIEW_PREP.md` 新增 Section 二，包含：
    - 系统架构图：外部基础设施（PowerJob/RocketMQ/MySQL）与应用内六层的依赖关系和数据流向
    - 完整链路图（正常链路）：PowerJob触发 → CompletableFuture并发 → rank游标循环 → MQ发送 → 异步消费 → incrementCompletedBatch → SUCCESS 完整时序
    - 完整链路图（故障路径A/B）：拉取报错和消费入库失败的完整流转，含三层保障对比表
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
- **Status**: Completed

---

### Request 6: 面试文档新增完整口述介绍版和状态机详解
- **Date**: 2026-03-05
- **Request Details**: 在面试文档中新增两项内容：一段面向技术面试官的完整口述版项目介绍（2-3分钟），以及 sync_task_record 状态机的详细说明。
- **Modification Made**:
  - `INTERVIEW_PREP.md` Section 一新增「完整口述版（2-3分钟）」：覆盖业务背景、技术选型、四项核心设计决策、量化结果；原分点版保留并标记为「精简要点版」
  - `INTERVIEW_PREP.md` Section 二末尾新增「sync_task_record 状态机详解」：ASCII 状态转换图、各状态含义与设计意图对照表、incrementCompletedBatch SQL 三条设计原则、故障场景与状态对应关系表
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
- **Status**: Completed

---

### Request 7: 面试文档新增批次级失败补跑方案追问
- **Date**: 2026-03-05
- **Request Details**: 追问场景：若某一批次拉取失败或入库失败，在保证其他批次已入库数据不被删除的情况下，该如何设计补跑方案？
- **Modification Made**:
  - `INTERVIEW_PREP.md` 在故障场景问答之后新增追问 Q&A，涵盖三处改造：
    1. Consumer 侧：全局 DELETE+INSERT IGNORE → 批次级 DELETE+INSERT（从 MQ 消息取 minRank/maxRank，@Transactional 保原子性）
    2. sync_task_record 增加 `last_successful_rank` 字段，pull 循环每批持久化进度，重跑时从断点继续，不执行全局 DELETE
    3. DLQ 路径：改为记录失败批次到 `sync_batch_record` 补偿表，修复任务扫表后按 rank 范围重拉+重发 MQ
  - 附改造前后对比表及关键权衡说明（上游 append-only 时安全，否则需全量修复兜底）
- **Files Modified**:
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
- **Status**: Completed

---

### Request 8: MODULE_DOCS 和 REQUESTS_AND_MODIFICATIONS 同步至最新
- **Date**: 2026-03-05
- **Request Details**: 检查并更新 MODULE_DOCS.md 和 REQUESTS_AND_MODIFICATIONS.md，使其与当前最新状态一致。
- **Modification Made**:
  - `MODULE_DOCS.md`：
    - 在 sync_task_record 表设计之后新增状态机图（ASCII 转换图 + 各状态说明表 + key guard 说明）
    - 新增 "Failure Scenarios" 章节，覆盖拉取阶段失败、消费阶段失败两种场景的具体行为，以及三层恢复保障对比表
  - `REQUESTS_AND_MODIFICATIONS.md`：
    - 修正 Request 3/4 顺序（原文件中 4 出现在 3 之前）
    - 补录 Request 5（架构图/链路图）、Request 6（完整口述版+状态机）、Request 7（批次级补跑方案）、本条 Request 8
- **Files Modified**:
  - `fault-data-sync-demo/MODULE_DOCS.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
- **Status**: Completed

---

### Request 9: 同步三份文档至 sync_batch_record 批次级重试实装状态
- **Date**: 2026-03-18
- **Request Details**: 检查 INTERVIEW_PREP.md、MODULE_DOCS.md、REQUESTS_AND_MODIFICATIONS.md 与当前代码的差异，更新所有与实际代码不一致的描述。核心发现：`sync_batch_record` 表及批次级重试逻辑（`runFirstSync` / `runRetrySync`）已在代码中完整实现，但三份文档均将其描述为"提案"或"改造方向"，故障路径分析也仍停留在全量 DELETE 重跑的旧方案。

  **主要差异点**：
  1. `FaultSyncServiceImpl` 已拆分为 `runFirstSync()`（首次：DELETE + 全量拉取）和 `runRetrySync()`（重试：无 DELETE，按 `sync_batch_record` 只补失败批次）
  2. `FaultDataConsumer` 消费成功后调用 `syncBatchRecordService.markInsertSuccess()`
  3. `FaultDataDlqConsumer` 同时调用 `markInsertFailed()` + `updateFailed()`（而非仅 `updateFailed()`）
  4. `FaultDataBatchMessage` 新增 `startRank` 字段供重试路径使用
  5. `incrementCompletedBatch` WHERE 条件已放宽为 `IN ('RUNNING','MESSAGES_SENT')` 并加 `batch_count > 0` 守卫
  6. `sync_task_record` 从未使用 PENDING 状态（`createOrUpdateRunning()` 直接写 RUNNING）

- **Modification Made**:
  - `MODULE_DOCS.md`：
    - 架构流程图更新：FaultSyncServiceImpl 增加首次/重试分支；Consumer 加 `markInsertSuccess`；DLQ Consumer 加 `markInsertFailed`
    - Package Structure 补充 `SyncBatchRecordEntity`、`SyncBatchRecordMapper`、`SyncBatchRecordService`、`SyncBatchRecordServiceImpl`
    - 新增 `sync_batch_record` 表 DDL 章节及重试路由说明
    - 状态机图及说明表更正 PENDING 状态（实际未使用）
    - `incrementCompletedBatch` SQL 更正为实际版本（WHERE IN + `batch_count > 0` 守卫）
    - Failure Scenarios 章节重写，反映批次级补跑实际行为；恢复层级表新增 `sync_batch_record + runRetrySync` 层
    - Running Locally 补充 `sync_batch_record.sql` 初始化步骤
  - `INTERVIEW_PREP.md`：
    - 正常链路图：增加 `markPullSuccess` 步骤 + Producer 补偿检查步骤；Consumer 链路增加 `markInsertSuccess`
    - 故障路径 A/B 图：重写为实际批次级续拉行为（路径A：从失败批次 startRank 续拉；路径B：仅补单批）
    - 恢复层级对比表：新增 `sync_batch_record + runRetrySync` 层，删除"重拉全量数据"的旧描述
    - "拉取阶段中途报错" Q：更正为"会从第4批续拉，当前实现支持批次级断点续拉"
    - "MQ消费侧入库失败" Q：更正为"仅补跑第5批，不删除其他7批数据"
    - 批次级补跑追问 Q：从"改造提案"重写为"当前实现的设计说明"，含 DDL 字段名修正（`start_rank`/`end_rank`/`pull_status`/`insert_status`）
    - 示例数据流：更新为实际字段名和调用链

- **Files Modified**:
  - `fault-data-sync-demo/MODULE_DOCS.md`
  - `fault-data-sync-demo/INTERVIEW_PREP.md`
  - `fault-data-sync-demo/REQUESTS_AND_MODIFICATIONS.md`
  - `fault-data-sync-demo/CLAUDE.md` (新增，/init 生成)
- **Status**: Completed
