# route-demo 项目简历材料

---

## 第一章：项目概述

**项目名称**：基于注解驱动的动态路由框架（route-demo）

**技术栈**：Spring Boot 3.5.6 / MyBatis-Plus 3.5.6 / Hutool / Lombok / Java 17

### 问题背景

在多环境故障数据查询场景下，系统需要根据请求来源（beta 环境、comm 环境）动态路由到不同的 Mapper 和 Service 实现。最初的做法是在业务方法中通过 `if-else` 判断路由标识，再手动调用对应实现，导致以下问题：

1. **耦合严重**：Controller/Service 层混杂路由判断逻辑，每次新增环境都要改多处代码
2. **扩展性差**：新增 gamma、stress 等环境时，需要在所有业务方法里加分支
3. **代码重复**：不同环境的 Service 实现高度相似，模板代码泛滥
4. **可测试性差**：路由逻辑嵌在业务中，无法单独测试

### 解决的核心痛点

将路由判断从业务代码中彻底剥离，通过 **注解 + 工厂 + 拦截器** 三层机制，让业务代码对路由细节完全无感知，新增一个环境只需新增一个 `ServiceImpl` 类并打上 `@RouteCustom` 注解，零改动现有代码。

---

## 第二章：架构设计与优化历程

### 2.1 初版设计（if-else 硬编码方案）

```java
// 初版：路由逻辑与业务完全耦合
public String queryFaultDistribution(String env) {
    if ("beta".equals(env)) {
        return betaFaultDetailMapper.queryFaultDistribution();
    } else if ("comm".equals(env)) {
        return commFaultDetailMapper.queryFaultDistribution();
    }
    throw new IllegalArgumentException("unknown env: " + env);
}
```

**问题**：每个业务方法都要写一遍 if-else；新增环境需全局搜索修改；单元测试要覆盖所有分支。

### 2.2 优化思路

核心思想：**关注点分离（Separation of Concerns）**

| 职责 | 由谁承担 |
|------|---------|
| 路由规则解析 | `RouteInterceptor` 拦截器 |
| 路由上下文存储 | `RouteContext`（ThreadLocal）|
| Bean 实例查找 | `BeanRouteFactory` 工厂 |
| 路由配置管理 | `RouteConfigManager` + `application.yml` |
| 业务实现分发 | 各 `ServiceImpl`（打 `@RouteCustom` 注解）|
| 统一入口 | `FaultDetailFacadeService` 外观服务 |

### 2.3 核心组件职责图（请求链路）

```
HTTP 请求
    │
    ▼
RouteInterceptor.preHandle()
    │  1. 从 URL 路径提取 routeKey（如 beta_fault）
    │  2. 查询 RouteConfigManager 得到 RouteConfig{module, suffix, dataSource}
    │  3. 写入 RouteContext（ThreadLocal）
    │  4. 写入 DataSourceContext（ThreadLocal）—— 多数据源扩展
    │
    ▼
FaultController（所有环境共用同一个 Controller 方法）
    │
    ▼
FaultDetailFacadeService.queryFaultDetail()
    │  调用 BeanRouteFactory.getServiceFromContext()
    │
    ▼
BeanRouteFactory
    │  从 RouteContext 取 module + suffix
    │  在 serviceRouteMap[module][suffix] 查找对应 Bean
    │
    ▼
BetaFaultDetailServiceImpl / CommFaultDetailServiceImpl（真正执行业务）
    │
    ▼
RouteInterceptor.afterCompletion()
    └── RouteContext.clear() + DataSourceContext.clear()
```

### 2.4 完整请求链路说明

以 `GET /fault/distribution/beta_fault` 为例：

1. 请求进入，`RouteInterceptor.preHandle()` 被触发
2. 从 URI `/fault/distribution/beta_fault` 提取 `beta_fault`
3. `RouteConfigManager.getRouteConfig("beta_fault")` 返回 `{module=faultDetail, suffix=/betaSuffix, dataSource=beta}`
4. `RouteContext.setRouteInfo(...)` 将上述信息存入 ThreadLocal
5. `DataSourceContext.set("beta")` 切换数据源（多数据源模式）
6. Controller 调用 `FaultDetailFacadeService.queryFaultDetail()`
7. FacadeService 调用 `BeanRouteFactory.getServiceFromContext(IBaseFaultDetailService.class)`
8. Factory 从 ThreadLocal 读取 `module=faultDetail, suffix=/betaSuffix`
9. 在 `serviceRouteMap["faultDetail"]["/betaSuffix"]` 找到 `BetaFaultDetailServiceImpl`
10. 执行 `BetaFaultDetailServiceImpl.queryFaultDetail()`，返回结果
11. 请求结束，`afterCompletion` 清理两个 ThreadLocal

---

## 第三章：核心设计模式应用

### 3.1 工厂模式 — BeanRouteFactory

**对应类**：`org.cabbage.codedemo.route.factory.BeanRouteFactory`

**代码片段**：
```java
// 二层 Map 结构：module -> suffix -> Bean 实例
private final Map<String, ConcurrentHashMap<String, Object>> serviceRouteMap = new ConcurrentHashMap<>();

public <T> T getServiceFromContext(Class<T> serviceType) {
    String moduleName = RouteContext.getModuleName();
    String routeSuffix = RouteContext.getRouteSuffix();
    return getService(moduleName, routeSuffix, serviceType);
}
```

**为什么用工厂模式**：调用方只关心"给我一个当前路由对应的 Service"，不需要知道具体是哪个实现类。工厂封装了查找逻辑，调用方与实现类完全解耦。

**替代方案**：Spring `ApplicationContext.getBean()` 直接查找，但每次都要传类名字符串，调用方必须知道实现类名称，仍有耦合。

---

### 3.2 策略模式 — 多 ServiceImpl 实现

**对应类**：`BetaFaultDetailServiceImpl`、`CommFaultDetailServiceImpl` 等

**代码片段**：
```java
// 统一接口
public interface IBaseFaultDetailService<T> {
    String queryFaultDistribution();
    String queryFaultDetail();
    String countFaults();
}

// beta 策略实现
@RouteCustom(suffix = "/betaSuffix", moduleName = "faultDetail")
@Service
public class BetaFaultDetailServiceImpl
        extends BaseFaultDetailServiceImpl<BetaFaultDetailRouterMapper, BetaFaultDetailEntity>
        implements IBetaFaultDetailService { ... }
```

**为什么用策略模式**：将"针对 beta 数据库查询"和"针对 comm 数据库查询"封装为两个独立策略，运行时动态选择。新增环境只需新增一个策略类，开闭原则（对扩展开放，对修改封闭）。

---

### 3.3 模板方法模式 — BaseFaultDetailServiceImpl

**对应类**：`org.cabbage.codedemo.route.service.impl.BaseFaultDetailServiceImpl`

**代码片段**：
```java
// 抽象基类：定义算法骨架
public abstract class BaseFaultDetailServiceImpl<M extends BaseMapper<T>, T>
        extends ServiceImpl<M, T> implements IBaseFaultDetailService<T> {

    // 模板方法：通用实现，复用于所有子类
    @Override
    public String queryFaultDistribution() {
        BaseFaultDetailRouterMapper routerMapper = getRouterMapper();
        return routerMapper.queryFaultDistribution();
    }
}
```

**为什么用模板方法**：各环境的 Service 大多数方法逻辑相同（都调 Mapper 的同名方法），差异仅在 Mapper 实现。基类提供通用骨架，子类可按需 Override 特有方法（如 `BetaFaultDetailServiceImpl.queryBetaUnique()`）。

---

### 3.4 外观模式 — FaultDetailFacadeService

**对应类**：`org.cabbage.codedemo.route.service.impl.FaultDetailFacadeService`

**代码片段**：
```java
@Service
public class FaultDetailFacadeService {
    private final BeanRouteFactory beanRouteFactory;

    // Controller 只调这一个方法，不用知道路由细节
    public String queryFaultDetail() {
        IBaseFaultDetailService<?> service = beanRouteFactory.getServiceFromContext(IBaseFaultDetailService.class);
        return service.queryFaultDetail();
    }
}
```

**为什么用外观模式**：Controller 层不需要依赖 `BeanRouteFactory`，也不需要知道有多少个 ServiceImpl。FacadeService 提供统一入口，隐藏路由选择的复杂性，简化了 Controller 的依赖关系。

---

### 3.5 拦截器模式 — RouteInterceptor

**对应类**：`org.cabbage.codedemo.route.interceptor.RouteInterceptor`

**核心价值**：将路由上下文的设置和清理收敛到请求生命周期的两个切面点（`preHandle` / `afterCompletion`），保证业务代码执行时上下文已就绪，请求结束后上下文被清理，防止内存泄漏和线程污染。

---

### 3.6 上下文对象模式（ThreadLocal）— RouteContext / DataSourceContext

**对应类**：`RouteContext`、`DataSourceContext`

**代码片段**：
```java
public class RouteContext {
    private static final ThreadLocal<String> ROUTE_KEY    = new ThreadLocal<>();
    private static final ThreadLocal<String> ROUTE_SUFFIX = new ThreadLocal<>();
    private static final ThreadLocal<String> MODULE_NAME  = new ThreadLocal<>();

    public static void clear() {
        ROUTE_KEY.remove();
        ROUTE_SUFFIX.remove();
        MODULE_NAME.remove();
    }
}
```

**为什么用 ThreadLocal**：HTTP 请求在 Servlet 容器中由线程池中的单一线程处理，ThreadLocal 提供了线程隔离的上下文存储，无需在方法参数中传递路由信息，调用链路上任意层次均可读取，且天然线程安全。必须配合 `remove()` 防止内存泄漏。

---

## 第四章：简历描述文案

### 精简版（约150字，适合简历主体）

**动态业务路由框架**（Spring Boot 3.5.6 / MyBatis-Plus）

设计并实现了基于注解驱动的动态路由框架，解决多环境（beta/comm）下故障数据查询的路由分发问题。核心设计：自定义 `@RouteCustom` 注解标记各环境 Service 实现；`BeanRouteFactory` 在启动时扫描并建立二层 ConcurrentHashMap 路由表（module → suffix → Bean）；`RouteInterceptor` 拦截请求，从 URL 提取路由 key 并写入 ThreadLocal 上下文；`FaultDetailFacadeService` 作为统一外观，屏蔽路由细节。新增业务环境只需新增一个 ServiceImpl 并打注解，零改动现有代码，完全符合开闭原则。扩展支持多数据源透明切换（`AbstractRoutingDataSource`），实现数据库级别的路由隔离。

---

### 详细版（约500字，适合项目展开说明）

**项目背景**

在故障数据查询平台中，同一套业务逻辑需要对接 beta、comm 两个独立环境的数据库，且未来需扩展至更多环境。初版使用 if-else 硬编码判断环境，导致每次新增环境需要修改多处业务代码，维护成本极高。

**核心设计**

重构后采用"注解 + 工厂 + 拦截器"三层解耦架构：

1. **路由注解层**：自定义 `@RouteCustom(suffix, moduleName)` 注解，打在各 ServiceImpl/Mapper 上，声明该 Bean 所属的模块和路由后缀
2. **启动注册层**：`BeanRouteFactory` 实现 `ApplicationContextAware`，启动时扫描所有带 `@RouteCustom` 的 Bean，建立二层 ConcurrentHashMap 路由表：`serviceRouteMap[module][suffix] = beanInstance`
3. **请求路由层**：`RouteInterceptor` 在 `preHandle` 阶段从 URL 路径提取 routeKey，查询 `RouteConfigManager`（配置来自 `application.yml`），将 module/suffix 信息写入 `RouteContext`（ThreadLocal）；`afterCompletion` 强制清理，防止内存泄漏
4. **业务调用层**：`FaultDetailFacadeService` 作为外观，调用 `BeanRouteFactory.getServiceFromContext()` 动态获取当前路由对应的 Service 实现，Controller 无需关心路由细节

**设计模式应用**

综合运用了工厂模式（BeanRouteFactory）、策略模式（多 ServiceImpl）、模板方法模式（BaseFaultDetailServiceImpl）、外观模式（FaultDetailFacadeService）、拦截器模式和 ThreadLocal 上下文对象模式。

**多数据源扩展**

在路由框架基础上，引入 `AbstractRoutingDataSource` 实现数据库级路由：`RouteConfig` 新增 `dataSource` 字段，拦截器同时向 `DataSourceContext`（独立 ThreadLocal）写入数据源 key，`DynamicRoutingDataSource.determineCurrentLookupKey()` 返回该 key，MyBatis 自动选择对应数据库连接，对业务代码完全透明。

**核心收益**

- 新增一个路由环境：只需新增一个 ServiceImpl + `application.yml` 一行配置，零改动现有代码
- 严格模式/非严格模式可配置：`route.strict-mode=true` 时路由不存在直接抛异常；`false` 时降级到默认路由
- 线程安全：ConcurrentHashMap 路由表 + ThreadLocal 上下文，支持高并发场景

---

### 亮点提炼（大厂面试官视角）

- **开闭原则落地**：通过注解扫描 + 工厂模式，新增路由环境对现有代码零侵入，工程化扩展能力强
- **ThreadLocal 生命周期管理**：在拦截器的 `afterCompletion` 强制 `remove()`，而非 `set(null)`，彻底解决线程池复用场景下的内存泄漏问题
- **多层路由体系**：URL 层（routeKey）→ 配置层（module/suffix）→ Bean 层（ServiceImpl 实例），三层解耦，各层可独立变更
- **数据库透明切换**：`AbstractRoutingDataSource` 接入 ThreadLocal，实现请求粒度的数据源切换，对 MyBatis/业务层完全透明
- **设计反思能力**：能指出初版 if-else 的痛点，能分析现版方案的性能边界（启动时建表 O(1) 查找），能对比 ShardingSphere 等方案的适用场景

---

## 第五章：多数据库路由扩展方案

### 5.1 痛点与动机

同一套 Service/Mapper 路由体系，beta 环境和 comm 环境的数据存放在**不同数据库**（`code_demo_beta` / `code_demo_comm`）。如果在每个 Mapper 实现中配置不同的数据源，配置散乱且难以管理。目标是：路由 key 不仅决定"调哪个 Service 实现"，还决定"连哪个数据库"，对业务代码完全透明。

### 5.2 技术方案选型

| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|---------|
| AbstractRoutingDataSource | Spring 原生，侵入小，与现有路由框架天然对齐 | 不支持分库分表，单库事务 | 数据库实例少、路由规则简单 |
| MyBatis Plugin（Interceptor）| SQL 级别拦截，精确控制 | 实现复杂，维护成本高 | 需要 SQL 改写、动态表名 |
| ShardingSphere Proxy | 功能全面，支持分库分表 | 引入独立中间件，运维成本高 | 大规模分库分表场景 |

**本项目选择 AbstractRoutingDataSource**：路由逻辑已有完整的 ThreadLocal 体系，只需在 `determineCurrentLookupKey()` 中返回 ThreadLocal 的值即可接入，改动最小、对现有架构侵入最低。

### 5.3 核心设计

**组件关系**：

```
RouteInterceptor.preHandle()
    ├── RouteContext.set(module, suffix)      ← 决定调哪个 ServiceImpl
    └── DataSourceContext.set("beta"/"comm")  ← 决定连哪个数据库

DynamicRoutingDataSource.determineCurrentLookupKey()
    └── return DataSourceContext.get()        ← Spring 据此选择数据源

MyBatis / HikariCP
    └── 从 DynamicRoutingDataSource 获取对应数据库连接
```

**RouteConfig 扩展**：新增 `dataSource` 字段，将路由 key 和数据源 key 在配置层绑定，而非在代码中硬编码：
```yaml
beta_fault:
  module: faultDetail
  suffix: /betaSuffix
  dataSource: beta     # 对应 DynamicRoutingDataSource 中注册的 key
```

### 5.4 配置层（application.yml 多数据源配置）

```yaml
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

### 5.5 完整代码

**DataSourceContext.java**
```java
package org.cabbage.codedemo.route.context;

public class DataSourceContext {
    private static final ThreadLocal<String> DATA_SOURCE_KEY = new ThreadLocal<>();

    public static void set(String key)  { DATA_SOURCE_KEY.set(key);    }
    public static String get()          { return DATA_SOURCE_KEY.get(); }
    public static void clear()          { DATA_SOURCE_KEY.remove();     }
}
```

**DynamicRoutingDataSource.java**
```java
package org.cabbage.codedemo.route.routing;

import org.cabbage.codedemo.route.context.DataSourceContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class DynamicRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContext.get();
    }
}
```

**DynamicDataSourceConfig.java**
```java
package org.cabbage.codedemo.route.config;

import com.zaxxer.hikari.HikariDataSource;
import org.cabbage.codedemo.route.routing.DynamicRoutingDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DynamicDataSourceConfig {

    @Bean("betaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.beta")
    public DataSource betaDataSource() {
        return new HikariDataSource();
    }

    @Bean("commDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.comm")
    public DataSource commDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DynamicRoutingDataSource dynamicDataSource() {
        DynamicRoutingDataSource dynamic = new DynamicRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("beta", betaDataSource());
        targetDataSources.put("comm", commDataSource());

        dynamic.setTargetDataSources(targetDataSources);
        dynamic.setDefaultTargetDataSource(betaDataSource());
        dynamic.afterPropertiesSet();
        return dynamic;
    }
}
```

**RouteConfig.java（新增字段）**
```java
/**
 * 对应的数据源 key，与 DynamicRoutingDataSource 中注册的 key 一致
 * 例如：beta / comm，为空时不切换数据源
 */
private String dataSource;
```

**RouteInterceptor.java（扩展 preHandle 和 afterCompletion）**
```java
// preHandle 中：
RouteContext.setRouteInfo(routeKey, config.getSuffix(), config.getModule());
if (config.getDataSource() != null) {
    DataSourceContext.set(config.getDataSource());
}

// afterCompletion 中：
RouteContext.clear();
DataSourceContext.clear();
```

### 5.6 类图（文字版）

```
HandlerInterceptor
    └── RouteInterceptor
            ├── RouteContext (ThreadLocal: routeKey, suffix, module)
            └── DataSourceContext (ThreadLocal: dataSourceKey)

AbstractRoutingDataSource
    └── DynamicRoutingDataSource
            └── determineCurrentLookupKey() → DataSourceContext.get()

RouteConfigManager
    └── Map<routeKey, RouteConfig{module, suffix, dataSource, enabled}>

BeanRouteFactory (ApplicationContextAware)
    ├── serviceRouteMap: Map<module, Map<suffix, ServiceBean>>
    └── mapperRouteMap:  Map<module, Map<suffix, MapperBean>>
```

### 5.7 事务问题分析

**单库事务（当前场景）**：每次请求只切换到一个数据源，`@Transactional` 工作正常，Spring 的 `DataSourceTransactionManager` 自动绑定当前线程的数据源连接。

**多库分布式事务（跨库场景）**：若一个请求中需要同时操作 beta 库和 comm 库，本地事务无法保证原子性，需要：
- **Seata AT 模式**：侵入性低，适合对强一致性要求不极端的场景
- **TCC 模式**：性能好，适合高并发，但实现复杂，需手动实现 try/confirm/cancel 逻辑
- **最终一致性**：通过消息队列（RocketMQ）异步补偿，适合允许短暂不一致的业务

当前项目场景：beta 和 comm 环境数据相互独立，单次请求只访问一个库，无跨库事务问题。

### 5.8 与 ShardingSphere 方案对比

| 维度 | 本项目方案 | ShardingSphere |
|------|-----------|----------------|
| 适用场景 | 少量数据库实例，路由规则简单（按环境） | 大规模分库分表，单表亿级数据 |
| 引入成本 | 极低（Spring 原生 AbstractRoutingDataSource） | 引入独立 Proxy 或 JDBC 依赖，配置复杂 |
| 路由规则灵活性 | 完全自定义（URL 路径、Header、业务字段均可） | 主要支持分片键规则 |
| 读写分离 | 需自行实现 | 内置读写分离支持 |
| 分布式事务 | 需接入 Seata | 内置 XA 事务支持 |
| 运维复杂度 | 低（无独立进程） | 高（Proxy 模式需独立部署） |

**结论**：本项目的路由需求是"按业务环境选择数据库"，数据库数量少（2-10 个），业务规则简单，`AbstractRoutingDataSource` 是最合适的选择。ShardingSphere 适合数据量极大、需要水平分片的场景，在本项目中属于过度设计。

---

## 第六章：25+ 面试题与高质量答案

---

### L1 基础原理（5题）

**Q1：@RouteCustom 注解如何被扫描和注册？**

`BeanRouteFactory` 实现了 `ApplicationContextAware` 接口。在 Spring 容器初始化完成后，`setApplicationContext()` 被调用。此时调用 `applicationContext.getBeansWithAnnotation(RouteCustom.class)` 获取所有带该注解的 Bean，遍历后通过 `AopUtils.getTargetClass(bean)` 获取代理后的真实类，再读取注解上的 `moduleName` 和 `suffix`，存入二层 ConcurrentHashMap 路由表。

---

**Q2：ThreadLocal 的原理和使用场景？**

每个 `Thread` 对象内部有一个 `ThreadLocalMap`（`Thread.threadLocals`）。`ThreadLocal.set(value)` 以当前 ThreadLocal 实例为 key、value 为 value，存入当前线程的 `ThreadLocalMap`。`get()` 同理从当前线程的 map 中取值。

核心特性：每个线程有自己独立的副本，线程间不共享。

适用场景：
- 请求上下文传递（本项目：路由 key、数据源 key）
- 数据库连接/Session 管理
- 用户登录信息传递（`SecurityContextHolder`）

注意：使用线程池时，线程会被复用，必须在请求结束后调用 `remove()`，否则旧的值会"污染"下一个请求。

---

**Q3：AopUtils.getTargetClass() 为什么要用？**

Spring AOP 默认通过 JDK 动态代理或 CGLIB 为 Bean 创建代理对象。`applicationContext.getBeansWithAnnotation()` 返回的 Bean 实际上是代理对象，直接对代理对象调用 `getClass()` 得到的是代理类（如 `$Proxy32` 或 `BetaFaultDetailServiceImpl$$SpringCGLIB$$0`），代理类上不一定有 `@RouteCustom` 注解。

`AopUtils.getTargetClass(bean)` 能穿透代理，返回被代理的原始目标类（`BetaFaultDetailServiceImpl`），从而正确读取注解。

---

**Q4：ConcurrentHashMap 为什么比 HashMap 更适合这个场景？**

`BeanRouteFactory` 中的路由表 `serviceRouteMap` 在应用启动时由单线程写入，此后只有读操作（每个 HTTP 请求都会读）。

选 ConcurrentHashMap 的原因：
1. **线程安全的读**：高并发下多线程同时读，ConcurrentHashMap 保证可见性，HashMap 的内部结构变更可能导致读到不一致状态（尽管 JDK8+ 的 HashMap 读在大多数情况下是安全的，但这依赖实现细节，不是规范保证）
2. **防御性编程**：如果未来加入动态路由更新（热更新），写操作会并发发生，ConcurrentHashMap 天然支持

---

**Q5：Spring 拦截器和 AOP 切面的区别？**

| 维度 | HandlerInterceptor | AOP（@Aspect）|
|------|-------------------|--------------|
| 作用层次 | Web 层（DispatcherServlet 之后，Controller 之前） | 任意 Spring Bean 方法 |
| 能访问 HTTP 请求/响应 | 能（`HttpServletRequest`） | 不能（需注入 `RequestContextHolder`）|
| 粒度 | 请求级别 | 方法级别（可精确到参数、返回值）|
| 典型用途 | 认证鉴权、路由上下文注入、日志 | 事务、缓存、性能监控 |

本项目选拦截器：路由设置是 HTTP 请求级别的操作，需要访问 URI，且需要在响应返回后（`afterCompletion`）清理上下文，拦截器的生命周期钩子天然匹配。

---

### L2 设计理解（8题）

**Q6：为什么用二层 Map 结构而不是拼接字符串做 key？**

字符串拼接方案（如 `faultDetail:/betaSuffix`）的问题：
1. 分隔符的选择需要保证 module 和 suffix 中不包含该分隔符，有隐患
2. 查找时每次都要做字符串拼接，有轻微的性能开销
3. 无法做到"按 module 枚举所有路由"等操作，扩展性差

二层 Map 的优点：
1. 结构清晰，层次语义明确（第一层按模块聚合，第二层按环境聚合）
2. 可以快速查询某个 module 下有哪些 suffix，便于路由管理和校验
3. `computeIfAbsent` 可以懒初始化内层 Map，代码简洁

---

**Q7：严格模式 vs 非严格模式的设计权衡？**

`route.strict-mode` 配置项：
- **严格模式（true）**：路由 key 不存在直接抛 RuntimeException，适合生产环境，快速暴露配置问题，防止静默走错数据源
- **非严格模式（false，默认）**：路由不存在时降级到默认路由，适合开发/测试环境，容错性高，不会因配置遗漏导致功能完全不可用

权衡点：严格模式更"快速失败"（Fail Fast），符合生产环境对正确性的要求；非严格模式更"宽容"，适合开发阶段。通过配置分离，两种行为都能覆盖，而不是硬编码某一种。

---

**Q8：ThreadLocal 会导致内存泄漏吗？如何在本项目中解决？**

会。原因：`ThreadLocalMap` 的 key（ThreadLocal 实例）使用弱引用，但 value（存储的上下文对象）是强引用。当 ThreadLocal 实例被 GC 后，key 变为 null，但 value 仍被 `ThreadLocalMap` 强引用，无法被回收，形成内存泄漏。在线程池场景下，线程不会销毁，泄漏会持续积累。

本项目的解决方式：`RouteInterceptor.afterCompletion()` 中调用 `RouteContext.clear()` 和 `DataSourceContext.clear()`，其内部使用 `ThreadLocal.remove()` 彻底删除 Entry，而不是 `set(null)`（set null 只是将 value 置为 null，Entry 仍在 map 中）。

---

**Q9：外观模式在这里的价值是什么？**

`FaultDetailFacadeService` 作为外观层，将 `BeanRouteFactory` 的调用封装起来。Controller 只需依赖 `FaultDetailFacadeService`，不需要知道：
1. `BeanRouteFactory` 的存在
2. `IBaseFaultDetailService` 接口
3. 路由是通过 ThreadLocal 上下文传递的

价值：**降低 Controller 的认知负担和依赖范围**。如果未来路由机制改变（比如从 ThreadLocal 改为从 Header 读取），只需改 FacadeService 和 Factory，Controller 零改动。这也体现了**迪米特法则（最少知道原则）**：Controller 只需要知道"有这么一个 Facade 能查故障详情"，其余细节全部隐藏。

---

**Q10：为什么用拦截器而不是过滤器或 AOP 来注入路由上下文？**

| 方案 | 问题 |
|------|------|
| Servlet Filter | 在 DispatcherServlet 之前执行，此时 Spring MVC 的 URL 映射尚未解析，无法方便地获取到 PathVariable、HandlerMethod 等信息 |
| AOP @Around | 需要选定切入点（如 `@Controller`），但执行时机在方法调用层，afterReturning 无法像 `afterCompletion` 一样在请求最终完成后才清理 |
| HandlerInterceptor | 在 DispatcherServlet 之后，Controller 执行之前，能访问完整的 `HttpServletRequest`，且 `afterCompletion` 保证在视图渲染之后调用，是清理 ThreadLocal 的最佳时机 |

选拦截器是因为其生命周期与 HTTP 请求完全对齐，且能直接访问 Web 层信息（URI 路径），无需通过 `RequestContextHolder` 间接获取。

---

**Q11：如何扩展支持一个新的业务环境（如 gamma）？**

三步操作，零改动现有代码：

1. **新建 ServiceImpl**（打注解即可自动注册）：
```java
@RouteCustom(suffix = "/gammaSuffix", moduleName = "faultDetail")
@Service
public class GammaFaultDetailServiceImpl
        extends BaseFaultDetailServiceImpl<GammaFaultDetailRouterMapper, GammaFaultDetailEntity>
        implements IGammaFaultDetailService { }
```

2. **新建 Mapper 和 Entity**（仅改表名）：
```java
@RouteCustom(suffix = "/gammaSuffix", moduleName = "faultDetail")
@Mapper
public interface GammaFaultDetailRouterMapper extends BaseFaultDetailRouterMapper { }
```

3. **在 application.yml 添加路由配置**：
```yaml
gamma_fault:
  module: faultDetail
  suffix: /gammaSuffix
  description: Gamma-env
  enabled: true
  dataSource: gamma
```

Controller 不用改，Service 逻辑不用改，工厂不用改——完全符合开闭原则。

---

**Q12：ApplicationContextAware 在 BeanRouteFactory 中的作用？**

`ApplicationContextAware` 是 Spring 的 Aware 接口，实现该接口的 Bean 在容器初始化完成后，会由 Spring 自动调用 `setApplicationContext(ctx)` 方法，将 `ApplicationContext` 注入。

在 `BeanRouteFactory` 中，利用这个时机调用 `ctx.getBeansWithAnnotation(RouteCustom.class)` 扫描所有带注解的 Bean，此时所有 Bean 都已完成初始化（包括 AOP 代理的创建），可以安全地遍历和注册。

如果改用 `@PostConstruct`，此时注入的 ApplicationContext 可能尚未完全就绪；如果改用监听 `ContextRefreshedEvent`，则需要处理事件可能多次触发的问题（父子容器各触发一次）。`ApplicationContextAware` 的触发时机恰好是容器刷新完成、所有单例 Bean 初始化后，最为合适。

---

**Q13：如果同一个 module+suffix 注册了两个 Bean，会发生什么？**

在 `BeanRouteFactory.registerBean()` 方法中：
```java
if (routeMap.containsKey(suffix)) {
    log.warn("路由已存在，将被覆盖: ...");
}
routeMap.put(suffix, bean);  // 后注册的覆盖先注册的
```

当前行为：**后注册的 Bean 覆盖先注册的**，并打 warn 日志。这意味着路由结果取决于 Spring Bean 扫描顺序，具有不确定性。

改进方案：在 `validateConfig()` 阶段检测重复注册，直接抛出 `BeanDefinitionStoreException`，在启动时快速失败，而不是运行时静默覆盖。

---

### L3 深度扩展（8题）

**Q14：AbstractRoutingDataSource 的原理？本项目如何接入？**

`AbstractRoutingDataSource` 是 Spring 提供的抽象数据源，内部维护一个 `Map<Object, DataSource> targetDataSources`（key 为逻辑名，value 为真实数据源）。每次需要连接时，调用 `determineCurrentLookupKey()` 获取 key，再从 map 中找到对应数据源，返回其 `Connection`。

本项目接入方式：
1. `DynamicRoutingDataSource` 继承 `AbstractRoutingDataSource`，重写 `determineCurrentLookupKey()` 返回 `DataSourceContext.get()`（从 ThreadLocal 取 key）
2. `DynamicDataSourceConfig` 中注册 `beta` 和 `comm` 两个真实数据源，调用 `dynamic.setTargetDataSources(map)` 和 `dynamic.afterPropertiesSet()` 完成初始化
3. `@Primary` 确保 MyBatis 的 `SqlSessionFactory` 注入的是这个动态数据源
4. `RouteInterceptor` 在请求前向 `DataSourceContext` 写入数据源 key，请求后清理

---

**Q15：多数据源下，@Transactional 事务如何保证一致性？**

**单库场景（本项目）**：每次请求只操作一个数据源（由 ThreadLocal 决定），`@Transactional` 通过 `DataSourceTransactionManager` 正常工作。Spring 在事务开始时绑定当前线程的数据库连接，事务内的所有操作复用同一连接，ACID 有保障。

**注意事项**：`@Transactional` 方法内不能切换数据源（`DataSourceContext.set()` 对事务中已绑定的连接无效），必须在事务开始之前就设置好数据源（本项目拦截器在 Controller 方法执行前已设置，符合要求）。

**跨库场景**：需要分布式事务。选项：
- Seata AT：基于 undo log，对代码侵入小，性能较好
- XA 协议：强一致，但性能差，锁时间长
- TCC：高性能，但需手动实现 try/confirm/cancel 三个方法，开发成本高
- 最终一致性（消息补偿）：适合允许短暂不一致的业务

---

**Q16：如何让路由支持异步线程（@Async / CompletableFuture）？**

问题：ThreadLocal 不会自动传递给子线程，`@Async` 方法在新线程中执行时，`RouteContext.getModuleName()` 返回 null。

解决方案：

**方案一**：自定义 `TaskDecorator`，在提交任务时捕获父线程的 ThreadLocal 值，在子线程执行时设置，执行完成后清理：
```java
@Bean
public Executor asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(runnable -> {
        String module = RouteContext.getModuleName();
        String suffix = RouteContext.getRouteSuffix();
        return () -> {
            try {
                RouteContext.setRouteInfo(null, suffix, module);
                runnable.run();
            } finally {
                RouteContext.clear();
            }
        };
    });
    return executor;
}
```

**方案二**：将路由信息作为参数显式传递给异步方法，而不是依赖 ThreadLocal，适合对路由隔离要求严格的场景。

---

**Q17：如何实现路由配置的热更新（不重启服务）？**

当前配置来自 `application.yml`，启动时由 `RouteConfigManager.initRouteConfig()` 加载到 `ConcurrentHashMap`。

热更新方案：

1. **配置中心接入**（推荐）：将路由配置迁移到 Nacos/Apollo，监听配置变更事件，在回调中调用 `RouteConfigManager.clearAllRoutes()` + 重新加载，线程安全（ConcurrentHashMap 的写入是安全的）

2. **接口触发刷新**：暴露 `/route/reload` 管理端点，接受新的路由配置 JSON，调用 `RouteConfigManager` 的更新方法。需要鉴权保护。

3. **`BeanRouteFactory` 的 Bean 路由表无需热更新**：Bean 路由表（serviceRouteMap）的内容是 Spring Bean，Bean 的添加需要重启，热更新的配置只影响 key → `{module, suffix}` 的映射关系。

---

**Q18：如何设计路由监控大盘（指标上报）？**

需要监控的关键指标：

| 指标 | 采集方式 |
|------|---------|
| 路由分发次数（按 routeKey） | 在 `RouteInterceptor.preHandle` 中 `Counter.increment(routeKey)` |
| 路由未命中次数（降级到默认路由） | 在 `RouteConfigManager.getRouteConfig` 中计数 |
| 各路由平均响应时间 | 在 `preHandle` 记录开始时间（写入 ThreadLocal），`afterCompletion` 计算耗时 |
| 路由错误次数 | 在 `afterCompletion` 检查 `ex != null` 时计数 |

技术选型：Micrometer（Spring Boot Actuator 内置）+ Prometheus 采集 + Grafana 展示。在 `RouteInterceptor` 注入 `MeterRegistry`，使用 `Counter` 和 `Timer` 记录指标，通过 `/actuator/prometheus` 暴露。

---

**Q19：如果要支持读写分离，路由体系如何改造？**

在现有数据源路由体系上叠加读写路由：

1. **`DataSourceContext` 扩展**：从单一 key（如 `beta`）扩展为组合 key（如 `beta-write`、`beta-read`）
2. **`DynamicDataSourceConfig` 扩展**：注册 4 个数据源：`beta-write`、`beta-read`、`comm-write`、`comm-read`
3. **读写判断**：在 `RouteInterceptor` 中根据 HTTP Method（GET → read，POST/PUT/DELETE → write）或 AOP 切面分析 `@Transactional(readOnly=true)` 注解，决定向 `DataSourceContext` 写入 `xxx-read` 还是 `xxx-write`

或者：将读写判断下沉到 `DynamicRoutingDataSource.determineCurrentLookupKey()` 中，读取一个额外的"读写标志" ThreadLocal，组合出最终数据源 key，对上层路由逻辑透明。

---

**Q20：与多租户（Multi-Tenancy）架构的相通之处？**

本项目的路由体系与多租户架构高度同构：

| 概念 | 本项目 | 多租户 |
|------|-------|-------|
| 路由 key | `beta_fault`、`comm_fault` | 租户 ID（如 `tenant_001`）|
| 路由上下文 | `RouteContext`（ThreadLocal）| 租户上下文（ThreadLocal）|
| 数据源切换 | `DataSourceContext` | 租户数据源（每租户一个 DB）|
| 路由规则来源 | URL 路径 | HTTP Header（`X-Tenant-Id`）、JWT token |

本项目的 `RouteInterceptor` 改造为多租户拦截器只需：从 Header 中提取租户 ID 替代从 URL 提取 routeKey；其余 ThreadLocal 管理、数据源切换的机制完全复用。

---

**Q21：如何防止路由错误（比如误用了生产数据源）？**

多层防护策略：

1. **配置层校验**：`RouteConfigManager.validateConfig()` 启动时检查路由配置完整性；严格模式下路由 key 不存在直接抛异常
2. **数据源 key 白名单**：在 `DynamicDataSourceConfig` 中明确注册 key，`determineCurrentLookupKey()` 返回未知 key 时，`AbstractRoutingDataSource` 会使用默认数据源并打日志
3. **环境隔离**：通过 Spring Profile（`application-prod.yml`、`application-test.yml`）管理不同环境的数据源 URL，防止测试配置文件中出现生产 URL
4. **权限控制**：生产数据库账号仅有 SELECT 权限，即使路由到生产数据源也无法写入
5. **Canary 测试**：新增路由配置后，先在测试环境验证，并通过接口测试断言返回数据来源

---

### L4 架构对比与反思（6题）

**Q22：与 ShardingSphere 分库分表方案相比，各自适合什么场景？**

**本项目方案**适合：
- 数据库实例数量少（2-10 个），每个实例包含完整业务数据
- 路由规则基于业务语义（环境、租户），而非数据分片键
- 团队希望保留对路由逻辑的完全控制，避免引入大型中间件
- 无分布式事务需求

**ShardingSphere**适合：
- 单表数据量超过千万级，需要水平分片降低单库压力
- 路由规则基于数据特征（用户 ID 取模、时间范围）
- 需要内置读写分离、SQL 解析、数据加密等企业级功能
- 团队有 DBA 资源维护分片配置

两者可以组合：用本项目的路由体系做**业务路由**（选数据库实例），用 ShardingSphere 在实例内部做**数据路由**（选分片表）。

---

**Q23：初版设计（if-else）和现版设计的对比，为什么做出改变？**

| 维度 | if-else 方案 | 当前方案 |
|------|------------|---------|
| 扩展新环境 | 修改所有业务方法 | 只新增一个类 + 配置一行 |
| 代码行数 | 随环境数量线性增长 | 不增长 |
| 测试难度 | 需覆盖所有分支 | 路由逻辑和业务逻辑可独立测试 |
| 配置灵活性 | 需改代码并重新部署 | 修改 yml 即可（可接入配置中心实现热更新）|
| 可读性 | 业务方法中混杂路由判断 | 各层职责单一，清晰 |

改变的驱动力：项目初期只有 beta/comm 两个环境，if-else 可以接受。当需求变为"支持任意数量的业务环境，且不同环境的接入频率可能很高"时，if-else 的维护成本呈现出明显的增长趋势，重构的 ROI 为正。

---

**Q24：这套方案的性能瓶颈在哪里？如何评估 QPS 上限？**

性能分析：

| 环节 | 开销 | 备注 |
|------|------|------|
| URL 路径解析 | 极低，O(路径段数) | 字符串 split，通常 3-5 段 |
| ConcurrentHashMap 查找 | O(1) | 两次 map.get() |
| ThreadLocal 读写 | 极低 | 本质是数组索引 + hash 查找 |
| 数据源获取（多数据源） | 低 | AbstractRoutingDataSource 一次 map 查找 |

**瓶颈不在路由框架本身**，路由层的额外开销单次请求约在微秒级。真正的 QPS 瓶颈在于：
- **数据库连接池**：HikariCP 连接数、等待超时配置
- **SQL 执行时间**：目标表的索引设计
- **线程池大小**：Tomcat 的 `server.tomcat.threads.max`

评估方式：用 JMeter / k6 对 `/fault/distribution/beta_fault` 施压，观察 P99 响应时间和 TPS，对比有/无路由拦截器时的差异（通常 < 0.1ms，可忽略）。

---

**Q25：如果数据量从 4 个环境扩展到 200 个租户，方案会面临什么挑战？**

**Bean 路由表规模**：200 个 ServiceImpl × 200 个 MapperImpl，启动时扫描和注册耗时增加，但总量仍在可接受范围（几百个 Bean）。

**数据源连接池压力**：200 个数据源 × 每个数据源最少 10 个连接 = 2000 个数据库连接，对数据库端口和内存都是极大压力。

**解决方案**：
1. **连接池共享**：同一数据库实例的多个租户共享连接池，在 SQL 层通过 schema 切换（`USE tenant_xxx`）或行级租户隔离（每张表加 `tenant_id` 列）
2. **懒加载数据源**：按需初始化数据源，而非启动时全部创建
3. **ShardingSphere 接管**：此时 ShardingSphere 的多租户方案更成熟，本项目方案属于过度自研

**本质问题**：本方案的"一个路由对应一个数据源"模式，在租户数量级别需要转向"多租户共享数据源 + 行级隔离"或"连接池复用 + Schema 切换"。

---

**Q26：有没有考虑用 MyBatis Plugin（Interceptor）方案替代？为什么没有选？**

MyBatis Plugin 方案的思路：在 `Executor`/`StatementHandler` 的 `intercept()` 中，根据当前上下文动态修改 `MappedStatement` 的 SQL 或动态切换数据源。

**没有选的原因**：

1. **职责错位**：MyBatis Plugin 的设计目标是 SQL 层面的增强（分页、性能分析），用来做路由是职责错位，代码难以理解和维护
2. **耦合过深**：需要在 MyBatis 内部解析业务路由逻辑，与框架实现细节深度耦合
3. **灵活性差**：路由规则需要在 SQL 执行层判断，而路由 key 来自 HTTP 请求，需要通过 ThreadLocal 传递，并没有比现有方案简单
4. **调试困难**：Plugin 链的执行顺序和异常处理比 Spring 拦截器复杂得多
5. **现有方案已经足够**：`AbstractRoutingDataSource` 在 JDBC 连接层切换数据源，已是足够底层的解决方案，不需要再深入到 SQL 层

MyBatis Plugin 更适合的场景：动态表名（分表场景下将 `fault_detail` 改为 `fault_detail_202501`）、SQL 性能分析、数据脱敏。

---

**Q27：反思这个项目的设计，有什么地方还可以做得更好？**

1. **路由表重复注册检测不够严格**：当前是 warn 日志 + 覆盖，应该在严格模式下抛出启动异常，而不是运行时静默覆盖，因为重复注册几乎肯定是配置错误

2. **`BeanRouteFactory` 耦合了具体接口类型**：`determineBeanType()` 方法中硬编码了 `IBaseFaultDetailService` 和 `BaseFaultDetailRouterMapper`，未来新增业务模块需要修改工厂。改进：用接口标记（如 `IRoutable` 接口）替代 `instanceof` 判断，工厂对具体业务类型无感知

3. **`BeanRouteFactory` 的 `routeConfigManager` 字段未注入**：代码中声明了 `private RouteConfigManager routeConfigManager`，但没有通过构造器注入，实际使用 `getMapperByRoute` 会 NPE。这是一个需要修复的 bug（`getServiceFromContext` / `getMapperFromContext` 路径不受影响）

4. **`RouteConfigProperties` 的 `enabled` 字段处理有 bug**：`RouteConfigManager.getRouteConfig()` 中 `Objects.requireNonNull(config).getEnabled()` 当 `enabled=true` 时 `getEnabled()` 返回 `true`，但 `if (!config.getEnabled())` 的判断不会进入，逻辑正确；但当 `enabled` 字段未配置（null）时，`isEnabled()` 返回 false，会误判为禁用，而 `!Objects.requireNonNull(config).getEnabled()` 会 NPE，存在防御不足

5. **缺少路由健康检查端点**：生产环境应该暴露 `/actuator/route-health`，展示当前已注册的所有路由、对应的 Bean 实例、最近路由命中次数，方便运维排查问题

6. **异步场景未处理**：ThreadLocal 不自动传播到子线程，`@Async` 方法会丢失路由上下文，需要通过 `TaskDecorator` 解决（见 Q16）
