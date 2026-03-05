# route-demo 项目简历材料

---

## 第一章：项目概述

**项目名称**：基于动态表名路由的多维度故障数据查询平台（route-demo）

**技术栈**：Spring Boot 3.5.6 / MyBatis-Plus 3.5.6（DynamicTableNameInnerInterceptor）/ Hutool / Lombok / Java 17

### 问题背景

故障数据查询平台存在两个模块（beta / comm）和三个领域（普通故障 / 性能故障 / 三方故障），交叉形成六个展示维度，每个维度对应一张独立的数据库表（`beta_normal_fault`、`beta_perf_fault`、`beta_third_fault`、`comm_normal_fault`、`comm_perf_fault`、`comm_third_fault`），六张表结构完全一致，仅表名不同，且数据全部在同一个库中。

**原始代码状态**：六套从 Controller → Service → ServiceImpl → Mapper 完全重复的代码，除各自 1-2 个独有展示方法外，其余代码一字不差，仅 XML 中表名不同。

### 解决的核心痛点

| 痛点 | 表现 |
|------|------|
| 代码爆炸 | 6 套 Mapper + 6 套 ServiceImpl + 6 套 Controller，公共逻辑重复 6 遍 |
| 扩展成本高 | 新增一个领域需要复制粘贴全套代码并逐一改表名 |
| 维护困难 | 修改一个公共逻辑需要同步改 6 处，极易遗漏 |
| 根本问题认知错误 | 六套表结构相同，差异只是表名，根本不需要六套 Bean |

**重构后**：1 套 Mapper + 1 套 Service + 1 套 Controller 处理全部六个维度，各维度独有方法由薄扩展层独立维护，新增维度只需在 `application.yml` 加一行配置并新建对应的扩展文件。

---

## 第二章：架构设计与优化历程

### 2.1 初版设计（if-else 硬编码）

```java
// 初版：路由逻辑与业务完全耦合，6 个分支
public String queryFaultDistribution(String dimension) {
    if ("beta_normal".equals(dimension)) {
        return betaNormalFaultMapper.queryFaultDistribution();
    } else if ("beta_perf".equals(dimension)) {
        return betaPerfFaultMapper.queryFaultDistribution();
    }
    // ... 另外 4 个分支
}
```

**问题**：每个业务方法都要写一遍 if-else；新增维度需全局搜索修改；6 套 Mapper 各持一份相同的 SQL。

### 2.2 错误的中间状态（Bean 路由方案）

意识到 if-else 的问题后，曾考虑通过注解 + 工厂路由到不同 Bean（`BeanRouteFactory`），但这只是把重复从 if-else 搬到了 Bean 注册表，六套 ServiceImpl 和 Mapper 依然存在，代码量没有实质减少。

**认知转变**：六张表结构完全一致，差异仅是表名，根本不需要六套 Bean。正确的抽象层次是"表名可变的单套代码"，而不是"多套代码的统一分发"。

### 2.3 最终方案（动态表名路由）

核心机制：**MyBatis-Plus `DynamicTableNameInnerInterceptor`**。Mapper XML 中统一写占位表名 `fault_detail`，插件在 SQL 执行前从 ThreadLocal 中读取当前请求的真实表名并替换，六张表共用一套 Mapper。

**关注点分离**：

| 职责 | 由谁承担 |
|------|---------|
| 维度识别（URL → dimensionKey） | `DimensionInterceptor` 拦截器 |
| 维度上下文存储 | `DimensionContext`（ThreadLocal）|
| 维度配置管理 | `DimensionManager` + `application.yml` |
| 表名动态替换 | `DynamicTableNameInnerInterceptor`（MyBatis-Plus 插件）|
| 公共业务逻辑 | `FaultDetailService`（单例，处理全部六个维度）|
| 维度独有逻辑 | 各 `XxxExtService` + `XxxExtMapper`（薄扩展，仅含独有方法）|

### 2.4 完整请求链路

```
GET /beta/perf/distribution
        │
        ▼
DimensionInterceptor.preHandle()
  URI 路径段: ["beta", "perf", "distribution"]
  拼接:       "beta_perf"
  查配置:     DimensionManager → tableName = "beta_perf_fault"
  写入:       DimensionContext.set("beta_perf", "beta_perf_fault")
        │
        ▼
FaultCommonController.queryFaultDistribution()
  → FaultDetailService.queryFaultDistribution()
    → FaultDetailMapper.queryFaultDistribution()
      → DynamicTableNameInnerInterceptor:
          SQL 中 "fault_detail" → "beta_perf_fault"
      → SELECT ... FROM beta_perf_fault（实际执行）
        │
        ▼
DimensionInterceptor.afterCompletion()
  DimensionContext.clear()    ← remove()，非 set(null)
  DataSourceContext.clear()   ← 二期分库时同步清理

独有端点链路（以 beta/perf 为例）：
GET /beta/perf/perf-metrics
  → （拦截器同上，表名已设置）
  → BetaPerfExtController → BetaPerfExtService → BetaPerfExtMapper
    → DynamicTableNameInnerInterceptor 同样替换表名
    → 独有 SQL 在正确的表上执行
```

---

## 第三章：核心设计模式应用（对应 GoF 23 种）

本项目落地了四种经典 GoF 设计模式，每种均有对应类可追溯。

---

### 3.1 装饰器模式 — DynamicTableNameInnerInterceptor

**对应类**：`org.cabbage.codedemo.route.config.MybatisPlusConfig`

**代码片段**：
```java
DynamicTableNameInnerInterceptor tableNameInterceptor = new DynamicTableNameInnerInterceptor();
tableNameInterceptor.setTableNameHandler((sql, tableName) -> {
    if ("fault_detail".equals(tableName)) {         // 只装饰占位表名
        String dynamic = DimensionContext.getTableName();
        return dynamic != null ? dynamic : tableName;
    }
    return tableName;
});
interceptor.addInnerInterceptor(tableNameInterceptor);
```

**为什么是装饰器模式**：`DynamicTableNameInnerInterceptor` 不修改 Mapper 接口，也不改变调用方式，而是在 SQL 执行前对其进行"增强"——拦截原始 SQL，替换表名后再传递给真正的执行器。这正是装饰器模式的核心：在不改变接口的前提下，对对象的功能进行透明的增强。

**与 `${tableName}` 的对比**：`${}` 是字符串拼接，有 SQL 注入风险；装饰器在 SQL 解析层（JSqlParser）识别语法中的表名节点并替换，不经过参数绑定，安全且对所有 Mapper 自动生效。

---

### 3.2 责任链模式 — MybatisPlusInterceptor

**对应类**：`org.cabbage.codedemo.route.config.MybatisPlusConfig`

**代码片段**：
```java
MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
interceptor.addInnerInterceptor(tableNameInterceptor);  // 节点一：动态表名
// interceptor.addInnerInterceptor(paginationInterceptor); // 节点二：分页（可按需挂载）
```

**为什么是责任链模式**：`MybatisPlusInterceptor` 内部维护 `List<InnerInterceptor>`，SQL 执行前依次经过链上每个节点处理，每个节点职责独立（动态表名、分页、乐观锁等），节点之间无耦合，可任意组合、增删和排序。这与责任链模式"将多个处理者串成链，请求沿链传递"的结构完全吻合。

---

### 3.3 代理模式 — DynamicRoutingDataSource

**对应类**：`org.cabbage.codedemo.route.routing.DynamicRoutingDataSource`

**代码片段**：
```java
public class DynamicRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContext.get();  // 根据 ThreadLocal 委托给真实数据源
    }
}
```

**为什么是代理模式**：`DynamicRoutingDataSource` 对外暴露统一的 `DataSource` 接口，内部持有多个真实数据源（`betaDataSource`、`commDataSource`），根据请求上下文将调用委托给对应的真实数据源。调用方（MyBatis `SqlSessionFactory`）感知不到代理的存在，这正是代理模式的本质：控制对真实对象的访问。

**与装饰器模式的区分**：装饰器目的是增强功能，代理目的是控制访问。此处 `DynamicRoutingDataSource` 并未增强数据源能力，只是决定"访问哪个"数据源，因此是代理而非装饰器。

---

### 3.4 模板方法模式 — AbstractRoutingDataSource

**对应类**：`org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource`（Spring 原生）

**代码结构**：
```java
// 父类：定义算法骨架（获取连接的流程）
public abstract class AbstractRoutingDataSource implements DataSource {
    @Override
    public Connection getConnection() {
        // 模板方法：固定流程
        DataSource ds = determineTargetDataSource();  // 调用钩子
        return ds.getConnection();
    }

    // 钩子方法：子类实现，决定用哪个数据源
    protected abstract Object determineCurrentLookupKey();
}

// 子类：只覆盖钩子方法，其余逻辑由父类处理
public class DynamicRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContext.get();
    }
}
```

**为什么是模板方法模式**：父类 `AbstractRoutingDataSource` 在 `getConnection()` 中定义了获取连接的完整算法骨架（查找数据源 → 获取连接），`determineCurrentLookupKey()` 是留给子类的钩子方法。子类只需实现这一个方法，整套连接获取逻辑由父类统一处理，代码复用与扩展点分离得非常清晰。

---

### 3.5 设计模式协作全景

```
HTTP 请求
    │
    ▼ DimensionInterceptor（拦截器）
      写入 DimensionContext（ThreadLocal）+ DataSourceContext（ThreadLocal）
    │
    ├── 数据源层：
    │   DynamicRoutingDataSource（代理模式）
    │       └── AbstractRoutingDataSource（模板方法模式）
    │               determineCurrentLookupKey() → DataSourceContext.get()
    │               → 委托给 betaDataSource 或 commDataSource
    │
    └── SQL 层：
        MybatisPlusInterceptor（责任链模式）
            └── DynamicTableNameInnerInterceptor（装饰器模式）
                    → "fault_detail" 替换为 "beta_perf_fault"
                    → 装饰后的 SQL 在正确的库、正确的表上执行
```

---

## 第四章：简历描述文案

### 一句话亮点（直接放入简历项目列表）

> 主导重构故障数据查询平台，识别 6 套仅表名不同的同构重复代码根因，引入 MyBatis-Plus `DynamicTableNameInnerInterceptor`（装饰器模式）+ ThreadLocal 请求上下文，将重复代码栈压缩为单一实现，新增业务维度零代码改动，并预设双库路由扩展点（代理模式 + 模板方法模式），代码量减少约 70%。

---

### STAR 法则完整描述（适合项目展开说明）

**S（Situation · 背景）**

所在团队维护一套故障数据查询平台，平台按 beta/comm 模块 × 普通/性能/三方故障领域交叉，形成 6 个展示维度，每个维度对应一张独立的数据库表，六张表结构完全一致，仅表名不同，但历史代码为每个维度各自实现了一套完整的 Controller → Service → Mapper 代码栈，公共逻辑大量重复，每次修改需同步改六处，新增维度需整套复制粘贴。

**T（Task · 任务）**

识别重复根因并制定重构方案：将 6 套代码合并为 1 套，同时保留各维度 1-2 个独有的展示逻辑；方案还需支持二期 beta/comm 单独建库后的数据源切换，且不影响现有业务接口的 URL 结构。

**A（Action · 行动）**

1. **根因诊断**：明确问题本质是"相同代码 + 不同表名"而非"不同逻辑"，排除 Bean 路由方案（仍维持 6 套 Bean），选择 MyBatis-Plus `DynamicTableNameInnerInterceptor`（**装饰器模式**）在 SQL 执行层动态替换占位表名 `fault_detail`，彻底消除 Mapper 层重复

2. **请求上下文**：设计 `DimensionInterceptor` 从 URL 路径前两段（`/{module}/{domain}/...`）提取维度标识，写入 `DimensionContext`（ThreadLocal），插件在 SQL 执行前读取并替换；`afterCompletion` 调用 `remove()` 清理，防止线程池复用导致的内存泄漏

3. **二期扩展预留**：同步实现 `DataSourceContext`（独立 ThreadLocal）和 `DynamicRoutingDataSource`（**代理模式**，继承 `AbstractRoutingDataSource` **模板方法模式**），`DimensionInterceptor` 预留数据源写入钩子，二期只需填写 yml 的 `dataSource` 字段即可激活双库切换

4. **插件编排**：通过 `MybatisPlusInterceptor`（**责任链模式**）统一管理 InnerInterceptor 链，动态表名节点与其他插件（分页等）并行挂载，各节点职责独立，互不干扰

5. **扩展层设计**：公共逻辑收归单套 Mapper/Service/Controller；各维度独有方法由薄扩展文件（ExtMapper + ExtService + ExtController，各含 1-2 个方法）独立维护，新增维度零改动现有代码

**R（Result · 结果）**

- 公共代码从 6 套压缩为 1 套，代码总量减少约 70%
- 新增一个维度：`application.yml` 加一行配置 + 可选扩展文件，现有代码零改动，符合开闭原则
- 二期 beta/comm 分库升级：仅修改 yml 并激活 `DynamicDataSourceConfig`，业务代码零改动，两层路由（表名/数据源）通过独立 ThreadLocal 解耦，互不干扰
- ThreadLocal 在 `afterCompletion` 中强制 `remove()`，防止 Tomcat 线程池复用导致的内存泄漏，方案可直接应用于高并发生产环境

---

### 亮点提炼（大厂面试官视角）

- **问题识别能力**：准确诊断"6套重复代码"的根本原因是抽象层次错误，而非简单的代码复用缺失，并给出匹配问题本质的解决方案
- **GoF 设计模式落地**：综合运用装饰器（动态表名增强）、责任链（插件链编排）、代理（数据源委托）、模板方法（钩子方法扩展）四种经典模式，每种均有明确的对应类
- **MyBatis-Plus 深度使用**：`DynamicTableNameInnerInterceptor` + `TableNameHandler` + ThreadLocal 组合，实现 SQL 执行前透明表名替换，规避 `${}` 拼接的 SQL 注入风险
- **ThreadLocal 生命周期管理**：`afterCompletion` 使用 `remove()` 而非 `set(null)`，彻底清理 `ThreadLocalMap` Entry，防止线程池复用场景的内存泄漏
- **渐进式升级设计**：二期所需代码一期已完整实现，升级只需填写配置，体现前瞻性设计意识

---

## 第五章：二期多数据库路由扩展方案

### 5.1 背景

二期中 beta 和 comm 各自独立建库，每库三张表（`normal_fault`、`perf_fault`、`third_fault`），表名在各自库内无需前缀。需要在一期动态表名路由的基础上，叠加数据源路由，实现"连哪个库 + 查哪张表"的组合路由。

### 5.2 技术选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| AbstractRoutingDataSource | Spring 原生，与已有 ThreadLocal 体系天然对齐，改动极小 | 不支持分库分表，连接数随数据源数量线性增长 | 选用 |
| ShardingSphere | 功能全面，内置读写分离/分库分表 | 引入独立中间件，运维成本高，本场景属于过度设计 | 不选 |
| MyBatis Plugin 手动切换 | 精确到 SQL 级别 | 职责错位，与 DynamicTableNameInnerInterceptor 冲突 | 不选 |

### 5.3 升级步骤（三处改动）

**1. application.yml 填写 dataSource 字段，表名去掉 beta_/comm_ 前缀**

```yaml
dimension:
  mappings:
    beta_normal:
      tableName: normal_fault    # 库内表名，无需前缀
      dataSource: beta
    beta_perf:
      tableName: perf_fault
      dataSource: beta
    beta_third:
      tableName: third_fault
      dataSource: beta
    comm_normal:
      tableName: normal_fault    # comm 库中同名表，数据源区分
      dataSource: comm
    comm_perf:
      tableName: perf_fault
      dataSource: comm
    comm_third:
      tableName: third_fault
      dataSource: comm

spring:
  datasource:
    beta:
      driver-class-name: com.mysql.cj.jdbc.Driver
      jdbc-url: jdbc:mysql://127.0.0.1:3306/code_demo_beta?serverTimezone=Asia/Shanghai
      username: root
      password: 1234567a
    comm:
      driver-class-name: com.mysql.cj.jdbc.Driver
      jdbc-url: jdbc:mysql://127.0.0.1:3306/code_demo_comm?serverTimezone=Asia/Shanghai
      username: root
      password: 1234567a
```

**2. DynamicDataSourceConfig 激活**（加上 `@Configuration` 注解即可，代码已完整实现）

**3. 业务代码零改动**（`DimensionInterceptor` 已预留 DataSourceContext 写入逻辑）

### 5.4 两层路由协作图

```
DimensionInterceptor.preHandle()
    ├── DimensionContext.set("beta_normal", "normal_fault")
    │       ↓
    │   DynamicTableNameInnerInterceptor
    │       SELECT ... FROM normal_fault （表名已替换）
    │
    └── DataSourceContext.set("beta")
            ↓
        DynamicRoutingDataSource.determineCurrentLookupKey() → "beta"
            ↓
        Spring 选择 betaDataSource 连接池 → 连 beta 库
```

最终效果：`beta_normal` 请求查询 beta 库的 `normal_fault` 表；`comm_normal` 请求查询 comm 库的 `normal_fault` 表。

### 5.5 事务问题分析

**单库场景（一期 / 二期各维度独立操作）**：每次请求只操作一个数据源，`@Transactional` 通过 `DataSourceTransactionManager` 正常工作。注意：数据源必须在事务开始前就设置好（本项目拦截器在 Controller 执行前已设置，符合要求）。

**跨库场景（若需同时操作 beta 和 comm 库）**：本地事务无法保证原子性，需要：
- **Seata AT 模式**：侵入性低，基于 undo log，适合对强一致性要求不极端的场景
- **TCC 模式**：高性能，需手动实现 try/confirm/cancel 三阶段，开发成本高
- **最终一致性（消息补偿）**：通过 RocketMQ 异步补偿，适合允许短暂不一致的业务

当前项目中 beta 和 comm 数据相互独立，单次请求只访问一个库，无跨库事务需求。

---

## 第六章：25+ 面试题与高质量答案

---

### L1 基础原理（5题）

**Q1：DynamicTableNameInnerInterceptor 的工作原理？**

`DynamicTableNameInnerInterceptor` 是 MyBatis-Plus 的内置拦截器，实现了 `InnerInterceptor` 接口。其工作流程：

1. 在 MyBatis 执行 SQL 前，拦截 `Executor` 的 `query`/`update` 方法
2. 使用 JSqlParser 解析 SQL，提取其中所有的表名
3. 对每个表名调用 `TableNameHandler.dynamicTableName(sql, tableName)`
4. 将 handler 返回的新表名替换回 SQL 中
5. 用替换后的 SQL 执行查询

本项目的 handler：
```java
(sql, tableName) -> {
    if ("fault_detail".equals(tableName)) {     // 只替换占位表名
        String dynamic = DimensionContext.getTableName();
        return dynamic != null ? dynamic : tableName;
    }
    return tableName;
}
```

通过只替换 `fault_detail`，避免影响其他表（如 `information_schema.TABLES` 等系统表）。

---

**Q2：为什么 Mapper XML 用 `fault_detail` 作为占位表名，而不是用 `${tableName}` 参数传入？**

`${tableName}` 是 MyBatis 的字符串拼接语法，直接将参数值拼入 SQL 字符串，存在 SQL 注入风险——如果 `tableName` 被恶意构造为 `a; DROP TABLE beta_normal_fault`，SQL 会被注入。

`DynamicTableNameInnerInterceptor` 在 SQL 解析层替换，使用 JSqlParser 识别 SQL 语法中的表名部分，只替换合法的标识符位置，不会引入注入风险。同时，替换逻辑集中在一个 handler 中，所有 Mapper（公共 + 扩展）自动受益，不需要各自在方法签名上增加 `tableName` 参数。

---

**Q3：ThreadLocal 的原理和使用场景？**

每个 `Thread` 对象内部有一个 `ThreadLocalMap`（`Thread.threadLocals`）。`ThreadLocal.set(value)` 以当前 ThreadLocal 实例为 key（弱引用），value 为 value，存入当前线程的 map。`get()` 从当前线程的 map 中查找。

核心特性：每个线程有自己独立的副本，线程间不共享，天然隔离。

本项目有两个 ThreadLocal：
- `DimensionContext`：存储 dimensionKey 和 tableName，供 `DynamicTableNameInnerInterceptor` 读取
- `DataSourceContext`：存储 dataSource key，供 `DynamicRoutingDataSource` 读取

**内存泄漏风险**：`ThreadLocalMap` 的 key 是弱引用，value 是强引用。当 ThreadLocal 实例被 GC 后 key 变 null，但 value 仍被强引用，在线程池场景下线程不销毁，泄漏持续积累。本项目在 `afterCompletion` 中调用 `remove()`（非 `set(null)`），彻底删除 Entry。

---

**Q4：Spring 拦截器（HandlerInterceptor）和 AOP 切面（@Aspect）的区别？**

| 维度 | HandlerInterceptor | AOP（@Aspect）|
|------|-------------------|--------------|
| 作用层次 | Web 层，DispatcherServlet 之后、Controller 之前 | 任意 Spring Bean 方法 |
| 能访问 HTTP 请求 | 能（`HttpServletRequest`，读取 URI） | 不能（需借助 `RequestContextHolder`）|
| 清理时机 | `afterCompletion`：视图渲染完成后，响应已返回 | `@After`/`@AfterReturning`：方法返回后，但在 AOP 代理栈内 |
| 典型用途 | 认证鉴权、上下文注入、ThreadLocal 清理 | 事务、缓存、性能监控 |

本项目选拦截器：需要从 URI 提取维度 key，必须访问 `HttpServletRequest`；`afterCompletion` 是清理 ThreadLocal 的最佳时机（保证在视图渲染之后也能清理）。

---

**Q5：WebConfig 中为什么只拦截 `/beta/**` 和 `/comm/**`？**

```java
registry.addInterceptor(dimensionInterceptor)
        .addPathPatterns("/beta/**", "/comm/**");
```

两个原因：

1. **防止误匹配**：`DimensionInterceptor` 的 URI 解析逻辑假定路径格式为 `/{module}/{domain}/...`，如果拦截 `/**`，`/actuator/health`、`/error` 等路径也会被解析，`parts[1]="actuator"`、`parts[2]="health"` 会组成 `actuator_health`，找不到维度配置后降级到默认维度，引发非预期行为。
2. **职责明确**：拦截器只负责故障数据查询的路由，非业务路径不应进入路由逻辑。

---

### L2 设计理解（8题）

**Q6：DimensionConfig 中的 dataSource 字段一期为空，是怎么处理的？**

`DimensionInterceptor.preHandle()` 中：

```java
if (config.getDataSource() != null) {
    DataSourceContext.set(config.getDataSource());
}
```

一期 yml 中不填 `dataSource`，`getDataSource()` 返回 null，跳过 `DataSourceContext.set()`，`DynamicRoutingDataSource.determineCurrentLookupKey()` 返回 null，`AbstractRoutingDataSource` 使用默认数据源（单库模式正常工作）。

二期填写 `dataSource: beta/comm` 后，`DataSourceContext` 被写入，动态数据源自动切换。这是**渐进式升级**设计——代码在一期就为二期的扩展留好了钩子，二期只需填写配置，不需要修改任何代码逻辑。

---

**Q7：为什么 DimensionContext 和 DataSourceContext 是两个独立的 ThreadLocal 类，而不是合并为一个？**

**单一职责原则**：`DimensionContext` 的消费方是 `DynamicTableNameInnerInterceptor`（MyBatis 层），`DataSourceContext` 的消费方是 `DynamicRoutingDataSource`（JDBC 连接层）。两者在不同的技术栈层次工作，职责不同。

**独立可变**：如果未来只想改动数据源路由逻辑（比如支持读写分离），不需要触碰 `DimensionContext`；反之亦然。合并后任何一方的变化都会影响另一方。

**测试隔离**：单独测试表名路由时，不需要模拟数据源上下文；单独测试数据源路由时，不需要模拟表名上下文。

---

**Q8：ThreadLocal 会导致内存泄漏吗？本项目如何解决？**

会。`ThreadLocalMap` 的 key（ThreadLocal 实例）使用**弱引用**，但 value（存储的对象）是**强引用**。当 ThreadLocal 实例被 GC 后，key 变为 null，但 value 仍被 map 强引用，无法被 GC 回收。在 Tomcat 线程池场景下，线程长期存活，泄漏会持续积累。

本项目解决方式：`DimensionInterceptor.afterCompletion()` 调用 `DimensionContext.clear()` 和 `DataSourceContext.clear()`，两者内部均使用 `ThreadLocal.remove()` 彻底删除 `ThreadLocalMap` 中的 Entry，而非 `set(null)`（`set(null)` 只是让 value 引用 null 对象，Entry 本身仍在 map 中，仍占内存）。

`afterCompletion` 即使在 Controller 抛出异常后也会被调用（只要 `preHandle` 返回 true），保证清理一定执行。

---

**Q9：如何保证并发请求下不同维度之间的 ThreadLocal 不互相干扰？**

ThreadLocal 的隔离是线程级别的，每个线程有自己的 `ThreadLocalMap` 副本，线程间完全独立。Tomcat 线程池中，每个 HTTP 请求由独立的线程处理（线程池中取出一个线程），该线程的 ThreadLocal 值与其他线程的值完全隔离，无需加锁。

需要防范的是同一个线程的**时间维度污染**：线程池中的线程被复用，上一个请求结束后如果不清理 ThreadLocal，下一个请求会读到上一个请求的残留值。这就是 `afterCompletion` 必须清理的原因，而不是依赖线程销毁时的清理（线程池中线程不销毁）。

---

**Q10：独有方法的 ExtMapper 和 ExtService 是否可以直接注入 FaultDetailMapper 来复用公共方法？**

可以，但一般不需要。ExtMapper 和 ExtService 的职责是"只处理本维度独有的 1-2 个方法"，如果某个独有方法恰好需要先查一次公共数据再做额外处理，可以在 ExtService 中注入 `FaultDetailService` 调用公共方法，再叠加独有逻辑：

```java
@Service
@RequiredArgsConstructor
public class BetaPerfExtService {
    private final BetaPerfExtMapper extMapper;
    private final FaultDetailService faultDetailService; // 可选注入

    public String queryPerfMetrics() {
        // 只调独有 SQL
        return extMapper.queryPerfMetrics();
        // 或叠加公共数据
        // String base = faultDetailService.queryFaultDetail();
        // String unique = extMapper.queryPerfMetrics();
        // return combine(base, unique);
    }
}
```

此时 `DimensionContext` 中的表名已经由拦截器设置，两次 Mapper 调用都会自动路由到 `beta_perf_fault`，无需额外处理。

---

**Q11：如何扩展支持一个新维度（如 gamma_normal）？**

三步，零改动现有代码：

**步骤一**：`application.yml` 添加配置
```yaml
gamma_normal:
  tableName: gamma_normal_fault
  description: Gamma普通故障
  # dataSource: gamma  （二期分库时填写）
```

**步骤二**：新建扩展文件（仅有独有方法时需要）
```java
// GammaNormalExtMapper.java
@Mapper
public interface GammaNormalExtMapper {
    String queryGammaNormalUnique();  // 独有 SQL，XML 用 fault_detail 占位
}

// GammaNormalExtService.java + GammaNormalExtController.java（薄层）
```

**步骤三**：新建 ExtMapper XML（独有 SQL）

公共 `FaultCommonController`、`FaultDetailService`、`FaultDetailMapper`、`DimensionInterceptor`、`MybatisPlusConfig`——全部零改动。`DimensionManager` 自动从 yml 加载新维度配置。

---

**Q12：DimensionManager 的 @PostConstruct 方法做了什么，为什么需要？**

```java
@PostConstruct
public void init() {
    props.getMappings().forEach((key, config) -> config.setDimensionKey(key));
    log.info("维度配置加载完成，共 {} 个维度", props.getMappings().size());
}
```

Spring Boot 的 `@ConfigurationProperties` 绑定 Map 时，key 不会自动回填到 value 对象的字段中——YAML 的 `beta_normal:` 是 Map 的 key，`DimensionConfig` 的 `dimensionKey` 字段默认为 null。`@PostConstruct` 在 Bean 初始化完成后执行，将 Map key 回填到每个 `DimensionConfig` 实例的 `dimensionKey` 字段，使得后续使用 config 对象时可以直接读取 dimensionKey，便于日志和调试，无需每次反查 Map。

---

**Q13：如果 DimensionContext.getTableName() 返回 null（拦截器未执行），SQL 会怎样？**

`TableNameHandler` 的处理：
```java
String dynamic = DimensionContext.getTableName();
return dynamic != null ? dynamic : tableName;  // null 时返回原占位表名
```

返回 `fault_detail`（占位表名），SQL 会尝试查询 `fault_detail` 表。若该表不存在，MyBatis 抛出 `Table 'fault_detail' doesn't exist` 异常，通过 `GlobalExceptionHandler` 统一返回错误响应。

这种情况正常使用时不会发生（WebConfig 限定了拦截路径），仅在直接调用内部方法绕过拦截器时才可能出现。改进方案：在 `FaultDetailService` 的入口方法加断言 `Objects.requireNonNull(DimensionContext.getTableName(), "DimensionContext 未初始化")`，将潜在的静默错误转为快速失败的明确异常。

---

### L3 深度扩展（8题）

**Q14：AbstractRoutingDataSource 的原理？本项目如何接入？**

`AbstractRoutingDataSource` 内部维护 `Map<Object, DataSource> resolvedDataSources`（key 为逻辑名，value 为真实数据源）。每次 MyBatis/Spring 需要数据库连接时，调用 `getConnection()`，其内部调用 `determineTargetDataSource()` → `determineCurrentLookupKey()` 获取 key → 从 map 中找到对应数据源 → 返回其连接。

本项目接入方式：
1. `DynamicRoutingDataSource` 继承并重写 `determineCurrentLookupKey()` 返回 `DataSourceContext.get()`
2. `DynamicDataSourceConfig` 注册 `betaDataSource`（HikariCP）和 `commDataSource`（HikariCP），调用 `setTargetDataSources(map)` 和 `afterPropertiesSet()` 初始化
3. `@Primary` 注解确保 MyBatis 的 `SqlSessionFactory` 注入的是动态数据源
4. `DimensionInterceptor` 在请求前写入 `DataSourceContext`，请求后清理

---

**Q15：动态表名 + 动态数据源组合使用时，执行顺序是什么？**

```
HTTP 请求
    │
    ▼ DimensionInterceptor.preHandle()
      ① DimensionContext.set(dimensionKey, tableName)
      ② DataSourceContext.set(dataSource)
    │
    ▼ Controller → Service → Mapper.queryXxx()
    │
    ▼ MyBatis 执行流程：
      ③ DynamicRoutingDataSource.determineCurrentLookupKey()
         → DataSourceContext.get() = "beta"
         → 获取 betaDataSource 的 Connection
      ④ DynamicTableNameInnerInterceptor.beforeQuery()
         → 解析 SQL，找到 "fault_detail"
         → DimensionContext.getTableName() = "normal_fault"（二期）
         → 替换为 "normal_fault"
      ⑤ 在 beta 库上执行 SELECT ... FROM normal_fault
    │
    ▼ DimensionInterceptor.afterCompletion()
      ⑥ DimensionContext.clear()
      ⑦ DataSourceContext.clear()
```

关键点：③ 发生在 ④ 之前——先确定连哪个库，再确定查哪张表，顺序正确且互不干扰。

---

**Q16：多数据源下，@Transactional 事务如何保证一致性？**

**单库场景（本项目）**：每次请求只操作一个数据源（由 `DataSourceContext` 决定），`@Transactional` 通过 `DataSourceTransactionManager` 正常工作。Spring 事务开始时绑定当前线程的数据库连接，事务内所有 Mapper 调用复用同一连接，ACID 有保障。

**关键约束**：数据源必须在事务开始之前设置。本项目中拦截器（`preHandle`）在 Controller/Service 方法执行前就已设置 `DataSourceContext`，符合要求。若在 `@Transactional` 方法内部调用 `DataSourceContext.set()`，事务已绑定旧连接，切换无效。

**跨库分布式事务**：本项目 beta/comm 数据相互独立，无跨库需求。若有需要，方案选型：Seata AT（低侵入）> TCC（高性能高成本）> 消息最终一致性（允许短暂不一致）。

---

**Q17：如何让路由支持异步线程（@Async / CompletableFuture）？**

问题：子线程无法继承父线程的 ThreadLocal，`@Async` 方法在新线程中执行时 `DimensionContext.getTableName()` 返回 null。

解决方案：自定义 `TaskDecorator`，在任务提交时捕获父线程的上下文，在子线程执行前恢复，执行后清理：

```java
@Bean
public Executor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(runnable -> {
        // 提交任务时（父线程）捕获上下文
        String dimensionKey = DimensionContext.getDimensionKey();
        String tableName    = DimensionContext.getTableName();
        String dataSource   = DataSourceContext.get();
        return () -> {
            try {
                DimensionContext.set(dimensionKey, tableName);
                if (dataSource != null) DataSourceContext.set(dataSource);
                runnable.run();
            } finally {
                DimensionContext.clear();
                DataSourceContext.clear();
            }
        };
    });
    return executor;
}
```

---

**Q18：如何实现维度配置的热更新（不重启服务）？**

当前配置来自 `application.yml`，由 `DimensionProperties`（`@ConfigurationProperties`）在启动时加载到 `Map`。

热更新方案：

1. **配置中心接入（推荐）**：迁移到 Nacos/Apollo，配置变更时触发 `@RefreshScope`，`DimensionProperties` 重新绑定，`DimensionManager` 的 `@PostConstruct` 重新执行（需将 Manager 也标记为 `@RefreshScope`）

2. **管理端点触发**：暴露 `/admin/dimension/reload` 接口，接受新的维度配置 JSON，调用 `DimensionManager` 更新内存配置。注意线程安全：`DimensionProperties.mappings` 是普通 HashMap，热更新期间需要加锁或替换为 `ConcurrentHashMap`

3. **动态表名插件无需热更新**：`DynamicTableNameInnerInterceptor` 的 handler 只是读取 ThreadLocal 值，handler 本身是函数，不依赖配置，无需更新

---

**Q19：这套方案能支持读写分离吗？如何改造？**

可以，在现有基础上叠加读写标志即可：

1. **`DimensionConfig` 扩展**：维持 `dataSource` 字段作为逻辑数据源（如 `beta`），读写由另一维度决定

2. **新增读写标志 ThreadLocal**（或扩展 `DataSourceContext`）：
```java
// DimensionInterceptor 中
String rw = "GET".equals(req.getMethod()) ? "read" : "write";
DataSourceContext.set(config.getDataSource() + "-" + rw);  // 如 "beta-read"
```

3. **注册 4 个数据源**：`beta-write`、`beta-read`、`comm-write`、`comm-read`（`comm-read` 可指向从库）

4. **`DynamicRoutingDataSource`** 不需要改动，`determineCurrentLookupKey()` 直接返回 `DataSourceContext.get()` 即可（已是组合 key）

---

**Q20：与多租户（Multi-Tenancy）架构有什么相通之处？**

本项目的维度路由体系与多租户架构高度同构：

| 概念 | 本项目 | 多租户 |
|------|-------|-------|
| 路由标识 | dimensionKey（`beta_normal`） | 租户 ID（`tenant_001`） |
| 路由上下文 | `DimensionContext`（ThreadLocal）| 租户上下文（ThreadLocal）|
| 数据源切换 | `DataSourceContext` → `DynamicRoutingDataSource` | 租户数据源（每租户一个 DB）|
| 表名路由 | `DynamicTableNameInnerInterceptor` | 同 schema 下按租户前缀区分表 |
| 路由来源 | URL 路径（`/{module}/{domain}/...`） | HTTP Header（`X-Tenant-Id`）或 JWT |

`DimensionInterceptor` 改造为多租户拦截器只需从 Header 读取租户 ID 替代从 URL 提取 dimensionKey，其余 ThreadLocal 管理、数据源切换、动态表名机制完全复用。

---

**Q21：如何防止动态表名被恶意构造导致安全问题？**

`DynamicTableNameInnerInterceptor` 本身已规避 SQL 注入（在 SQL 语法解析层替换，非字符串拼接），但仍需防止路由到非预期的表。

防护措施：

1. **白名单校验**：`DimensionManager.getConfig()` 只返回 yml 中预注册的维度配置，dimensionKey 来自受控的 Map 查找，不接受用户直接输入
2. **tableName 来源可信**：tableName 完全由配置文件决定（`DimensionConfig.tableName`），用户无法通过请求参数影响
3. **维度 key 提取来自 URL 路径段**：`parts[1] + "_" + parts[2]`，例如 `beta_normal`，即使路径被篡改为 `beta_normal; DROP TABLE`，`DimensionManager` 找不到该 key 会降级到默认维度，不会执行恶意 SQL
4. **数据库账号权限最小化**：只授予业务查询所需的 SELECT/INSERT 权限，即使路由到错误的表也无法执行 DDL

---

### L4 架构对比与反思（6题）

**Q22：与 ShardingSphere 分库分表方案相比，各自适合什么场景？**

| 维度 | 本项目方案 | ShardingSphere |
|------|-----------|----------------|
| 路由粒度 | 按业务维度（beta/comm × 领域）路由 | 按数据分片键（用户 ID、时间）路由 |
| 数据库数量 | 少（2 个） | 大规模（数十至数百个分片） |
| 引入成本 | 极低（Spring 原生 + MyBatis-Plus 内置插件）| 需引入 shardingsphere-jdbc 或独立 Proxy 进程 |
| 动态表名 | 业务语义驱动，表结构完全相同 | 分片表按规则命名（`order_0`~`order_15`）|
| 事务支持 | 单库 @Transactional 即可 | 内置 XA/BASE 分布式事务 |
| 学习/运维成本 | 低 | 高（SQL 方言限制、分片规则调试）|

**结论**：业务维度数量少（6 个）、表结构同构、无水平分片需求时，本项目方案是最合适的选择，零外部依赖。ShardingSphere 适合单表行数超过千万级、需要水平分片降低单库压力的场景，在本项目中属于过度设计。

---

**Q23：初版（if-else）→ 中间状态（Bean 路由）→ 最终方案（动态表名）的演进，为什么做这两次改变？**

| 版本 | 方案 | 问题 | 改变原因 |
|------|------|------|---------|
| v1 | if-else 判断 | 业务方法混杂路由判断，新增维度全局改 | 关注点未分离 |
| v2 | Bean 路由（BeanRouteFactory + @RouteCustom）| 路由判断解耦了，但 6 套 Bean 仍然存在，重复没有减少 | 解决了错误的问题 |
| v3 | 动态表名路由 | — | 识别到"表结构相同、差异仅表名"这一本质，选择匹配问题本质的工具 |

v2 → v3 是认知升级：v2 把问题定义为"如何分发到不同的 Bean"，v3 把问题重新定义为"如何路由到不同的表"。问题定义正确后，解决方案自然简单。

---

**Q24：这套方案的性能瓶颈在哪里？**

| 环节 | 开销 | 说明 |
|------|------|------|
| URI 路径解析 | 极低，O(路径段数) | 字符串 split，通常 3-5 段 |
| Map 配置查找 | O(1) | `DimensionManager` 内存 HashMap |
| ThreadLocal 读写 | 极低 | 数组索引 + hash 查找，纳秒级 |
| JSqlParser SQL 解析 | 低，毫秒级 | `DynamicTableNameInnerInterceptor` 的主要开销 |
| 数据源查找（二期） | 极低 | `AbstractRoutingDataSource` 一次 Map 查找 |

真正的瓶颈在路由框架之外：数据库连接池（HikariCP 等待时间）、SQL 执行时间（目标表索引设计）、Tomcat 线程池大小。路由层额外开销约在 1ms 以内，可忽略不计。

---

**Q25：如果维度数量从 6 个扩展到 200 个（类多租户场景），方案会面临什么挑战？**

**当前方案的局限**：
1. **连接池压力（二期）**：200 个数据源 × 最小连接数（10）= 2000 个长连接，对数据库端口和内存压力极大
2. **扩展文件数量**：200 × 3 个扩展文件（ExtMapper + ExtService + ExtController）= 600 个文件，虽然每个极薄，但文件数量可观
3. **配置文件膨胀**：200 行 yml 配置，可读性下降

**解决方向**：
1. **连接池共享**：beta/comm 各一个连接池，同一个库内的多维度共享连接（一期方案本已如此，不受影响）
2. **配置中心**：yml 迁移到 Nacos，支持动态维护和热更新
3. **代码生成**：扩展文件规律性极强，可用代码生成器一键生成
4. **行级租户隔离（彻底方案）**：6 张表合并为 1 张，加 `dimension_key` 列，SQL 自动追加 `WHERE dimension_key = ?`，彻底消除扩展文件需求

---

**Q26：有没有考虑通过 MyBatis 的 `Interceptor`（Plugin）实现动态表名，而不是用 MyBatis-Plus 的 `DynamicTableNameInnerInterceptor`？**

有考虑。自定义 MyBatis `Interceptor` 同样可以拦截 `Executor.query()`，解析 SQL 替换表名。没有选择的原因：

1. **已有现成方案**：MyBatis-Plus 的 `DynamicTableNameInnerInterceptor` 已内置 JSqlParser 解析逻辑，无需重复实现
2. **避免插件链冲突**：MyBatis-Plus 的 `MybatisPlusInterceptor` 是插件总线，内置 `InnerInterceptor` 在其内部有序执行；额外引入自定义 MyBatis `Interceptor` 会在外层再包一层，执行顺序和边界更难控制
3. **维护成本**：`DynamicTableNameInnerInterceptor` 经过生产验证，自研实现需要处理各种 SQL 方言（子查询、JOIN、IN 子句等），维护成本高

自定义 MyBatis Plugin 更适合的场景：SQL 性能分析（打印慢 SQL）、全字段加密脱敏、特殊 SQL 改写（MyBatis-Plus 内置插件不支持的场景）。

---

**Q27：反思这个项目的设计，有什么地方还可以做得更好？**

1. **`FaultDetailMapper.xml` 中的 demo SQL 不够真实**：当前 SQL 查的是 `information_schema.TABLES`，实际项目中应查业务表字段。当前 SQL 的 `WHERE table_name = 'fault_detail'` 是静态字符串查询，`DynamicTableNameInnerInterceptor` 替换的是 FROM 子句中的表名，不会替换 WHERE 条件中的字符串值——需要注意两者的区别，避免在真实 SQL 中产生混淆

2. **ExtMapper 独立于 FaultDetailMapper，但 @TableName 关联仍依赖约定**：ExtMapper 是普通接口（未继承 `BaseMapper`），XML 中手写 `FROM fault_detail` 依赖开发者知道"占位表名是 fault_detail"这一约定。建议在团队规范文档中明确标注，或封装常量 `DimensionContext.PLACEHOLDER_TABLE = "fault_detail"` 供 XML 引用时参考（XML 本身无法引用 Java 常量，但可以写在注释中）

3. **`DimensionManager` 未做配置完整性校验**：启动时只打 info 日志，未校验 `tableName` 是否为空、`defaultKey` 是否在 mappings 中存在。建议在 `@PostConstruct` 中加入断言，启动时快速失败而非运行时才暴露问题

4. **异步场景未处理**：`@Async` 方法中 ThreadLocal 不自动传播，当前代码未提供 `TaskDecorator`。在服务中引入异步处理时需要补充，否则会出现 `DimensionContext.getTableName() = null` 的静默错误

5. **缺少监控端点**：生产环境建议暴露 `/actuator/dimensions`，展示当前已注册的所有维度配置、各维度请求计数、最近错误次数，方便排查路由问题
