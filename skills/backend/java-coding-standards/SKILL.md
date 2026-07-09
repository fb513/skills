---
name: java-coding-standards
description: "Java 后端编码规范。TRIGGER when: 任何涉及编写或修改 Java 代码的任务（新增、修改、重构均算）。SKIP: 纯阅读/分析代码、不产生代码变更的任务。"
---

# Java 后端编码规范

Spring Boot + MyBatis-Plus 项目的编码规范。

---

# 命名约定与 Lombok

## 命名约定 (Naming Conventions)
- **变量和方法名**: 使用驼峰命名法 (camelCase)
- **类名**: 使用大驼峰命名法 (PascalCase)
- **常量**: 使用全大写字母和下划线分隔 (UPPER_SNAKE_CASE)
- **包名**: 使用小写字母和点号分隔
- **模型类**: 输入输出模型使用 `Req`/`Resp` 后缀

## Lombok 使用
- 优先使用 `@Getter`、`@Setter` 等 Lombok 注解减少样板代码
- Entity 类统一使用 `@Getter`、`@Setter`、`@ToString`、`@Accessors(chain = true)`
- 避免在 Entity 类使用 `@Data`，防止自动生成的 `equals/hashCode` 引入隐性问题

## 语言规范
- 所有模型输入、输出和代码注释必须使用中文语言

---

# 架构分层规范

## 依赖方向总则
- **主分层固定为 Controller -> Service -> Manager -> Mapper**: Controller 只调用 Service；Service 承担主要业务流程、权限校验、事务边界、数据转换和多个下层能力编排；Manager 只在确有必要抽取可复用业务能力、缓存、外部资源、跨 Service 复用协作时存在；Mapper 只访问数据库。
- **Service 可以直接访问 Mapper**: 简单 CRUD、单一业务流程、没有跨场景复用价值的逻辑直接放在 Service 中，不为了“凑层级”强行创建 Manager。
- **禁止同级业务层相互依赖**: 同一顶级业务包内的 Spring Bean 不互相依赖，例如 `service -> service`、`manager -> manager`、`api -> api` 均禁止。多个 Manager 的编排必须放到 Service；需要复用的纯逻辑抽到 `util` 或无 Spring Bean 的领域工具类。
- **禁止反向依赖**: 下层不得依赖上层，例如 `manager -> service`、`mapper -> service/manager/controller` 均禁止。
- **禁止新增额外业务层**: 不新增 `component`、`support`、`facade` 等顶级业务包来绕开同层依赖。需要复用且带基础设施协作的能力放 `manager`；纯计算、规范化、文本处理等无状态逻辑放 `util`；业务编排放 `service`。
- **允许基础横切依赖**: 各层可以依赖 `model`、`entity`、`enums`、`exception`、`util` 等基础包；`config`、`interceptor` 作为框架集成层按 Spring 配置需要依赖。
- **DTO 放置规则**: 请求/响应 DTO 必须放在 `model` 包，禁止定义在 Controller 内后被 Service/Manager 引用。

### 允许的主要依赖方向

| 来源层 | 允许依赖 |
|--------|----------|
| `controller` | `service`、`model`、`interceptor` 注解、`exception` |
| `service` | `manager`、`mapper`、`api`、`model`、`entity`、`enums`、`exception`、`util` |
| `manager` | `mapper`、`api`、`model`、`entity`、`enums`、`exception`、`util`、`config` |
| `api` | `config`、`model`、`exception`、`util` |
| `mapper` | `entity`、`model`、`enums` |
| `inner` | `service`、`model`、`interceptor` 注解、`exception` |

## Controller 层
- **职责**: 参数校验和调用 Service 层
- **禁止**: 包含业务逻辑判断
- **异常处理**: 不直接处理异常，由全局异常处理器统一处理
- **参数校验**: 使用 Jakarta Validation 注解
- **接口方法限制**: 对外接口只使用 `@GetMapping` 和 `@PostMapping`，禁止使用 `@PutMapping`、`@DeleteMapping`、`@PatchMapping`
- **接口路径风格**: 不使用标准 RESTful 风格的路径变量接口，禁止使用 `@PathVariable`；详情、删除、取消等操作使用明确动作路径和 query/body 参数，例如 `GET /xxx/detail?id=1`、`POST /xxx/cancel`

## Service 层
- **职责**: 处理主要业务逻辑、权限校验、事务边界、数据转换和多个 Manager/Mapper/API 的编排
- **异常处理**: 不做通用异常捕获（由 GlobalExceptionHandler 统一处理）
- **数据转换**: Model/DTO 转换代码放在对应类中作为静态方法

## Manager 层
- **职责**: 抽取跨 Service 复用的业务能力，封装缓存、外部资源、语音、OSS、提示词、检索、通知、同步等可复用协作能力
- **禁止**: 承担一次性接口业务流程、替代 Service 做入口编排、依赖其他 Manager
- **取舍**: 如果逻辑只被一个 Service 使用且不封装缓存/外部资源/复用协作，优先留在 Service；如果只是纯计算或文本规范化，放到 `util` 静态工具类

## Mapper 层
- **职责**: 仅专注数据库操作
- **禁止**: 包含业务逻辑

## Entity 和 Model 规范
- **Entity 类**: 只包含数据库字段映射
- **Model/DTO 类**: 正确使用 Req/Resp 后缀，转换代码放在对应类中作为静态方法
- **请求/响应参数注释**: 所有 `Req`/`Resp` 类字段必须添加中文注释，说明字段含义、单位、枚举取值和空值语义
- **Controller 参数注释**: GET query 参数、路径动作参数等没有 DTO 字段承载的接口参数，必须在 Controller 方法 JavaDoc 中用中文 `@param` 和 `@return` 说明

```java
@Data
public class UserResp {
    private Long id;
    private String name;
    private LocalDateTime createdAt;

    public static UserResp fromEntity(UserEntity entity) {
        UserResp resp = new UserResp();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        resp.setCreatedAt(entity.getCreatedAt());
        return resp;
    }
}
```

- **Model/DTO 类 时间字段**: 请求/响应类中的时间字段统一使用 Java 8 时间类型（`LocalDateTime`、`LocalDate`、`LocalTime`）。**禁止使用 String**: 时间字段禁止使用 `String` 类型，由 Jackson 拦截器自动处理序列化/反序列化

---

# 异常处理与参数校验

## 统一异常处理
- **响应格式**: 使用统一的通用响应格式 (CommResp)
- **自定义异常**: 业务异常抛出带有明确错误码的自定义异常
- **全局处理**: Controller 层异常由全局异常处理器统一处理
- **Service 层**: 不做通用异常捕获；@Async 方法例外，必须自行 catch 并通过 SseEmitter 通知客户端

## 参数校验：纵深防御原则

数据校验不能只在一层做。在数据经过的每一层都加验证，让无效数据在结构上不可能穿透：

| 层 | 职责 | 手段 |
|----|------|------|
| Controller | 拒绝明显无效输入 | Jakarta Validation（`@NotNull`、`@NotBlank`、`@Min`、`@Size` 等） |
| Service | 确保数据在业务上下文中合理 | 业务校验（存在性、状态、权限），抛自定义异常 |
| Database | 即使代码有 bug 也不允许脏数据 | NOT NULL、CHECK 约束、外键、唯一索引 |
| 日志 | 捕获其他层遗漏的异常情况 | 关键操作记录日志，异常模式监控 |

## Jakarta Validation
- **Controller 层**: 使用 Jakarta Validation 注解对请求参数进行校验
- **校验注解**: `@NotNull`、`@NotEmpty`、`@Size`、`@Pattern` 等
- **自定义校验**: 复杂校验逻辑使用自定义校验注解

## 数据库约束
- 关键字段加 `NOT NULL`、`CHECK` 约束
- 业务唯一性用唯一索引保证（不只靠 Service 层校验）
- 外键约束保护引用完整性

## 业务逻辑分离
- **Controller**: 不进行业务逻辑判断
- **Service**: 负责所有业务逻辑处理

---

# 异步规范

## 使用原则
- 异步任务统一使用 `AsyncUtils` 工具类，不直接创建线程或使用 `CompletableFuture.runAsync()`/`supplyAsync()` 的默认线程池
- `AsyncUtils` 内部使用虚拟线程执行器（`newVirtualThreadPerTaskExecutor`），无需额外配置线程池
- 多个异步任务需要等待全部完成时，使用 `AsyncUtils.waitAll()`，不直接调用 `CompletableFuture.allOf().join()`
- 简单延迟使用 `AsyncUtils.sleep()`，不直接调用 `Thread.sleep()`

## 方法说明
- `AsyncUtils.runAsync(Runnable)` — 无返回值的异步任务
- `AsyncUtils.supplyAsync(Supplier<T>)` — 有返回值的异步任务
- `AsyncUtils.waitAll(CompletableFuture<?>...)` — 等待多个异步任务全部完成
- `AsyncUtils.sleep(long)` — 安全的线程休眠（自动处理 InterruptedException）

## 典型用法

```java
// 并行执行多个异步任务并等待全部完成
var sttFuture = AsyncUtils.supplyAsync(() -> speechManager.recognize(audio));
var historyFuture = AsyncUtils.supplyAsync(() -> messageManager.getHistory(userId));
AsyncUtils.waitAll(sttFuture, historyFuture);

String text = sttFuture.join();
List<Message> history = historyFuture.join();
```

---

# JSON 序列化规范

## 使用原则
- JSON 序列化/反序列化统一使用 `JsonUtils` 工具类，禁止直接注入或 `new ObjectMapper()`
- `JsonUtils` 内部已配置好通用的序列化/反序列化策略，直接使用即可

## 方法说明
- `JsonUtils.formatObjToJson(Object)` — 对象序列化为 JSON 字符串
- `JsonUtils.parseJsonToObj(String, Class<T>)` — JSON 字符串反序列化为对象
- `JsonUtils.parseJsonToObj(String, TypeReference<T>)` — 反序列化为泛型类型（如 `Map<K,V>`）
- `JsonUtils.parseJsonToList(String, Class<T>)` — 反序列化为 `List<T>`
- `JsonUtils.parseJsonToJsonNode(String)` — 解析为 Jackson `JsonNode`

## 典型用法

```java
// 序列化
String json = JsonUtils.formatObjToJson(userResp);

// 反序列化
UserResp resp = JsonUtils.parseJsonToObj(json, UserResp.class);

// List 反序列化
List<UserResp> list = JsonUtils.parseJsonToList(json, UserResp.class);
```

---

# 日期时间规范

## 使用原则
- 日期时间格式化、解析、时间戳转换、日期差计算等通用日期操作统一收口到 `DateUtils`，业务代码不要散落 `DateTimeFormatter.ofPattern(...)`、`LocalDate.parse(...)`、`LocalDateTime.parse(...)`、`ChronoUnit` 等重复逻辑。
- 业务文案和业务状态判断留在对应业务类中；只有可复用的日期/时间展示、转换和计算逻辑放入 `DateUtils`。
- 新增日期展示口径时，优先扩展 `DateUtils` 的明确命名方法或常量，并在调用方复用。

---

# 安全规范与文档规范

## 安全规范

### 代码安全
- **输入验证**: 所有用户输入必须经过验证
- **SQL 注入防护**: 使用 MyBatis Plus 的参数化查询
- **敏感信息**: 避免在代码中硬编码敏感信息
- **异常信息**: 避免在异常信息中泄露敏感数据

## 文档规范

### 代码注释
- **语言**: 所有注释使用中文
- **类注释**: 说明类的职责和用途
- **方法注释**: 说明方法的功能、参数和返回值
- **复杂逻辑**: 对复杂的业务逻辑添加详细注释

### API 文档
- **接口描述**: 使用中文描述接口功能
- **参数说明**: 中文说明每个参数的含义和约束
- **响应说明**: 中文说明响应数据的结构和含义
- **HTTP 方法**: 对外接口只允许 GET 和 POST；查询类接口使用 GET，新增、修改、删除、取消、提交、生成等动作类接口使用 POST
- **路径约定**: 不采用标准 RESTful 的 `PUT /resource/{id}`、`DELETE /resource/{id}`、`GET /resource/{id}` 写法；使用动作化路径，如 `/resource/update`、`/resource/delete`、`/resource/detail`

---

# 数据库操作规范

## MyBatis-Plus 使用
- **查询方式**: 使用 `LambdaQueryWrapper` 保证类型安全，避免硬编码字段名

```java
new LambdaQueryWrapper<UserEntity>()
        .select(UserEntity::getId, UserEntity::getName, UserEntity::getStatus)
    .eq(UserEntity::getStatus, 1)
    .orderByDesc(UserEntity::getId);
```

- **字段选择**: 务必使用 `select` 方法明确指定要检索的字段，避免 select *

```java
mapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
    .select(OrderEntity::getId, OrderEntity::getStatus, OrderEntity::getUserId)
    .eq(OrderEntity::getId, orderId));
```

- **查询返回类型**: 返回实体类或 DTO 类，**禁止使用 `Map<String, Object>`**
- **批量新增**: 使用 MyBatis-Plus 3.5.11+ 的 `BaseMapper.insert(Collection)` 批量插入（3.5.4 新增，接受 List），不要手写 SQL

```java
mapper.insert(entityList);
```

- **批量更新**: 使用 MyBatis-Plus 3.5.11+ 的 `BaseMapper.updateById(Collection)` 批量更新（3.5.4 新增，接受 List），不要手写 SQL

```java
List<Entity> entityList = ...;
        entityList.forEach(e -> e.setStatus(1));
        mapper.updateById(entityList);
```
- **SQL 位置**: 业务 SQL 必须写在 `src/main/resources/mapper/*.xml` 中，Mapper Java 接口只保留方法签名和 `@Param` 参数声明；禁止在 Mapper 接口中使用 `@Select`、`@Insert`、`@Update`、`@Delete` 等注解编写 SQL
- **复杂查询**: 必须使用 XML 映射文件

## 性能优化
- **避免循环查询**: 避免在 for 循环中进行 SQL 操作，优先使用批量查询和更新
- **避免连表查询**: 优先使用 IN 查询 + Service 层内存映射，而非 SQL JOIN

```java
List<OrderEntity> orders = orderMapper.selectList(...);
Set<Long> userIds = orders.stream().map(OrderEntity::getUserId).collect(Collectors.toSet());
if (CollectionUtils.isEmpty(userIds)) {
        return Collections.emptyList();
}
List<UserEntity> users = userMapper.selectList(
        new LambdaQueryWrapper<UserEntity>()
                .select(UserEntity::getId, UserEntity::getName)
                .in(UserEntity::getId, userIds));
Map<Long, String> userNameMap = users.stream()
        .collect(Collectors.toMap(UserEntity::getId, UserEntity::getName));
```

- **例外：允许 JOIN 的场景** — 当关联条件本身是过滤条件的一部分时（如"查询活跃用户的待处理订单"），IN 查询会导致先查出所有数据再内存过滤，效率更差。此时允许在 XML Mapper 中写 JOIN 查询：

```java
List<OrderWithUserResp> selectPendingOrdersOfActiveUsers();
```

- **时间戳管理**: `updatedAt` 和 `createdAt` 字段由数据库自动维护，代码中不应更新
- **分页查询优化**: 优先使用 `maxId` 分页方式，避免传统 `OFFSET` 分页的性能问题

```java
LambdaQueryWrapper<ArticleEntity> wrapper = new LambdaQueryWrapper<ArticleEntity>()
        .select(ArticleEntity::getId, ArticleEntity::getTitle, ArticleEntity::getCreatedAt);
if (maxId != null && maxId > 0) {
        wrapper.lt(ArticleEntity::getId, maxId);
}
        wrapper.orderByDesc(ArticleEntity::getId).last("LIMIT " + pageSize);
```

---

# Redis 缓存规范

### Key 命名
- 格式：`业务域:实体:标识`，如 `prompt:sell:1`、`fxy:english:topic:123`
- 全小写，冒号分隔层级

### 使用原则
- 缓存操作统一放 Manager 层，Service 不直接操作 `RedisTemplate`
- 设置合理过期时间，避免缓存永不过期
- 缓存未命中时回退到数据库查询，不抛异常
- 批量操作优先用 Pipeline

---

# 事务管理规范

### 使用原则
- 优先使用 `@Transactional` 注解声明式事务
- 仅在无法使用 `@Transactional` 的场景（如 @Async 方法、跨线程操作、需要手动控制提交时机）使用 `TransactionUtils` 编程式事务
- 事务边界放在 Service 层，Controller 和 Mapper 不管理事务

### @Transactional 注意事项
- 只加在需要事务的方法上，不要加在类级别
- 只读操作使用 `@Transactional(readOnly = true)`
- 避免在事务内做耗时操作（外部 API 调用、文件上传等）
