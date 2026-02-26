# 面试准备文档 — fault-data-sync-demo

> 适用岗位：Java 初级 / 中级开发工程师（1-3 年经验）
> 面试官视角：高级开发 / 架构师
> 本文档包含：项目介绍话术、面试问答（含标准答案）、技术延伸题

---

## 一、项目介绍（面试者自述稿）

> 场景：面试官说"介绍一下你觉得最有技术含量的项目"

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

**③ 20个领域并行，有界线程池控背压。**
100个 domain+date 任务用 20 线程有界线程池并发执行，正常场景（2万条/domain/date，100任务）的**拉取+MQ发送阶段 Wall Time 约 10 秒**，全链路（含异步消费入库）**约 2 分钟**，完成所有领域 200 万条数据的每日同步。顶峰场景（100万条/domain/date）全链路约 **15-20 分钟**，相比原来串行方案预估的 **6+ 小时**，缩短了 **95% 以上**。

**④ 幂等双保险。**
主策略是每次同步前先 DELETE 该 domain+date 的旧数据，天然幂等，支持 PowerJob 任意重跑。安全兜底是在 fault_record 上加了联合唯一索引 `(domain, data_date, rank)` + INSERT IGNORE，防 MQ 消息重投导致重复写入。

**⑤ 可靠性保障。**
MQ 消费失败自动重试 3 次，超限进 DLQ，DLQ Consumer 将 sync_task_record 状态置为 FAILED 并预留告警钩子。sync_task_record 表用单条原子 UPDATE + CASE WHEN 跟踪批次完成进度，避免分布式锁依赖。

**量化指标总结：**

| 指标 | 正常场景 | 顶峰场景 |
|------|---------|---------|
| 每日同步总量 | ~200 万条（20域×5天×2万） | ~1 亿条（20域×5天×100万） |
| Pull+MQ发送 Wall Time | ~10 秒 | ~80 秒 |
| 全链路同步耗时 | ~2 分钟 | ~15-20 分钟 |
| DB写入吞吐（vs逐条） | 4万条/s（+50倍） | 4万条/s（+50倍） |
| 翻页耗时（vs offset方案） | 稳定50-80ms/批 | 减少约95%耗时 |
| 幂等重跑 | 支持任意次数 | 支持任意次数 |

这个项目让我对**高吞吐批处理架构、MQ 可靠性保障、幂等设计**有了比较深的实践理解。"

---

## 二、面试问答

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

为什么核心=最大=20：我们有 20 个领域，让每个领域同时有一个线程在跑，充分并行。设置成相同值是为了避免线程数在 core 和 max 之间频繁扩缩，减少线程创建销毁开销。

为什么 `ArrayBlockingQueue` 而不是 `LinkedBlockingQueue`：`LinkedBlockingQueue` 默认无界，队列永远不会满，意味着任务会无限堆积在队列里，可能导致 OOM。`ArrayBlockingQueue(100)` 有上限，是有界队列。

为什么选 `CallerRunsPolicy`：当线程池满且队列也满时，该策略让**提交任务的线程自己来执行这个任务**。对我们的场景，提交方是 `FaultDataSyncJob.process()` 的主线程，主线程帮忙执行任务时，会暂停继续提交新任务，相当于对上游产生了**自然的背压**——不需要额外的流控代码，系统自动降速。如果用 `AbortPolicy`（抛异常）或 `DiscardPolicy`（丢任务），任务会直接失败，是不可接受的。

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

## 三、面试官可能的追问 & 压力问题

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

## 四、一句话速记（面试前复习用）

| 知识点 | 一句话 |
|--------|--------|
| rank 游标 vs offset | offset 深翻页全表扫，游标每次走索引，耗时恒定 |
| MQ 解耦价值 | 拉取和写库速率不同，MQ 做缓冲，各自跑满速 |
| INSERT IGNORE 作用 | 唯一索引冲突时静默跳过，不抛异常，MQ重投幂等 |
| CallerRunsPolicy | 队列满时调用方线程自己跑，实现自然背压 |
| DLQ | 超过最大重试次数的消息进死信队列，用于告警兜底 |
| 联合唯一索引三作用 | 翻页走索引+去重保障+幂等兜底 |
| incrementCompletedBatch 无锁 | MySQL UPDATE 行锁天然串行，SET x=x+1 原子 |
| CompletableFuture 优于 Future | 支持非阻塞组合、链式操作、统一异常处理 |
| 批量INSERT为什么快 | 减少网络往返 + 减少事务提交次数 + rewriteBatchedStatements |
| 幂等三层 | delete全量覆盖（重跑幂等）→ INSERT IGNORE（重投幂等）→ status机器（状态幂等） |
