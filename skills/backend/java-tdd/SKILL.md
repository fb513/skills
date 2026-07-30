---
name: tdd
description: 面向本仓库 Java 21 / Spring Boot 业务代码的轻量测试驱动开发。用于新增功能、修复缺陷、重构业务规则或补充回归测试；默认在 Service、Manager、工具类或模型转换的公共方法 seam 上使用 JUnit 5 和 Mockito 编写快速行为测试，绕过 Spring Context、Controller、数据库、Redis、MQ 和外部接口。只有改动触及这些边界本身时，才要求记录并执行额外的专项验证。
---

# 轻量 TDD

以快速、确定的行为测试驱动业务代码，不把数据库和外部环境带入日常红绿循环。

开始前读取 `CONTEXT.md` 和相关 ADR，使用项目领域语言命名测试。存在 PRD 或 ticket 时，以其验收标准作为期望值来源。编写 Java 代码时同时遵守 `java-coding-standards`。

## 默认测试 seam

优先从以下公共方法观察行为：

- Service：业务流程、权限、状态转换、事务前的决策和下层编排。
- Manager：跨 Service 复用的业务规则，或对缓存、外部资源的封装行为。
- Util：纯计算、规范化和日期处理。
- Model/DTO：静态转换和输出语义。

默认直接实例化被测对象，使用 JUnit 5 与 Mockito，不使用 `@SpringBootTest`。简要说明本次选择的 seam；只有多个 seam 会显著改变测试成本或保障范围时才询问用户。

## 边界判断

先判断改动是否修改了边界本身：

| 改动 | 日常 TDD | 额外验证 |
| --- | --- | --- |
| Service/Manager 业务规则 | 快速行为测试 | 通常不需要 |
| Util、Model/DTO 转换 | 普通 JUnit 测试 | 通常不需要 |
| Mapper XML、复杂 SQL、表结构 | Mock Mapper 验证上层行为 | 真实数据库专项验证 |
| Controller、认证、参数校验、JSON | Service 行为测试 | MockMvc 或 API 验证 |
| Redis Key、TTL、锁、缓存一致性 | Mock Redis 验证业务分支 | Redis 专项验证 |
| Feign、RestTemplate、OSS、微信 SDK | Mock 外部 adapter | WireMock 或测试环境验证 |
| RabbitMQ/RocketMQ 路由、ACK、重试 | 直接测试 MessageResolver 行为 | Broker 专项验证 |
| 事务、动态数据源、异步并发 | 测试同步业务决策 | 集成或并发专项验证 |

不要为了满足 TDD 临时建设完整集成环境。边界未被修改时，使用测试替身并保持内循环快速；边界被修改但当前环境无法验证时，明确记录未覆盖风险和所需验证，不能用行为测试冒充集成保障。

## 红绿循环

一次只实现一个可观察行为：

1. 从验收标准选择一个最小业务场景。
2. 使用领域语言编写一个 JUnit 5 测试。
3. 构造最小输入，并为 Mapper、缓存或外部接口设置确定返回值。
4. 运行单个测试，确认它因目标行为尚未实现而失败，而不是因 Spring、网络或数据配置失败。
5. 编写使测试通过的最小生产代码。
6. 重新运行单个测试，确认变绿。
7. 只对当前切片做行为等价的小范围整理，并持续保持测试为绿。
8. 继续下一个行为。

从仓库根目录优先运行：

```bash
mvn test -Dtest=<TestClass>
mvn test
```

如果全量测试包含已知的人工脚本或外部依赖测试，先运行与改动相关的确定性测试，并在交付中如实说明未运行项及原因。

## 断言业务结果

优先断言：

- 返回值和统计结果。
- `WebBaseException` 及其业务语义。
- 状态转换和关键字段。
- 是否执行或跳过一项对外业务动作。
- 传递到持久化或外部边界的业务数据。

允许使用 `ArgumentCaptor` 检查待保存实体或待发送消息的关键字段。除非“是否调用”本身就是可观察契约，否则不要只写 `verify(...)`。

期望值必须来自 PRD、领域规则或人工推导的明确常量，不能在测试中复制生产算法重新计算。

## 测试替身

默认 Mock Mapper、Redis、Feign、MQ、OSS、微信 SDK、时间和随机数。纯业务 Manager、Util 和转换逻辑优先使用真实对象；封装外部资源的 Manager 可以作为边界 Mock。

现有代码使用字段注入时，优先使用 `@InjectMocks`；只有 Mockito 无法构造且改造生产代码超出当前任务时，才最小化使用 `ReflectionTestUtils`。不要仅为了测试方便扩大生产代码改动。

完整示例见 [tests.md](tests.md)，替身边界见 [mocking.md](mocking.md)。

## 禁止事项

- 不在默认行为测试中使用 `@SpringBootTest`。
- 不连接共享开发、测试或生产数据库。
- 不访问真实 Redis、Nacos、MQ 或公网接口。
- 不硬编码真实业务 ID、Token、手机号或其他敏感数据。
- 不用 `System.out` 代替断言。
- 不测试 private 方法，不通过反射绕过公共 seam。
- 不把数据导出、批量修复和线上调用脚本伪装成自动化测试。
- 不为所有未来场景一次性编写大量测试；坚持一条行为一个循环。

## 与开发流程衔接

- `to-spec`：在 Testing Decisions 中记录主要行为 seam 和边界风险。
- `to-tickets`：每个纵向切片至少声明一个可独立验证的业务行为。
- `implement`：先运行本技能的红绿循环，再编写对应生产代码。
- `diagnosing-bugs`：将最小复现转成同一 seam 上的回归测试。
- `code-review`：分别检查业务行为是否覆盖，以及边界改动是否有专项验证；不要用其中一项代替另一项。

## 完成标准

交付前确认：

- [ ] 单个测试曾因目标行为缺失而失败。
- [ ] 最小实现后单个测试通过。
- [ ] 相关确定性测试通过。
- [ ] 测试没有访问真实基础设施或敏感数据。
- [ ] 边界改动的额外验证已执行，或未覆盖风险已明确记录。
