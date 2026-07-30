# 项目测试替身边界

## 默认选择

| 依赖 | 日常行为测试 | 说明 |
| --- | --- | --- |
| MyBatis Mapper | Mock | 验证上层业务规则，不验证 SQL |
| RedisTemplate | Mock | 验证命中、未命中和锁分支 |
| Feign、RestTemplate、RestClient、OkHttp | 优先 Mock 仓库 adapter | 返回明确响应或抛出边界异常 |
| Nacos、配置中心 | 固定配置或 Mock 配置边界 | 不连接共享配置中心 |
| RabbitTemplate、RocketMQ Producer | Mock | 捕获消息的类型和关键业务字段 |
| OSS、微信 SDK | Mock | 不访问真实账号和公网 |
| `TextModelApi`、`EmbeddingModelApi`、`SpeechApi` | Mock 逻辑能力接口 | 不直接 Mock 供应商 SDK 的内部实现 |
| `EsSearchClient`、Rerank、RAG 外部边界 | Mock | 验证检索编排和业务分支，不验证真实索引 |
| 时间、随机数 | 固定值或可注入替身 | 保持测试确定性 |
| 纯业务 Manager、Util | 真实对象 | 避免测试内部实现结构 |
| 封装外部资源的 Manager | 可 Mock | 将它视为 Service 的外部 seam |

## Mockito 用法

优先直接构造测试对象：

```java
@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private ScheduleService scheduleService;
}
```

只设置当前场景需要的返回值。不要创建一个覆盖所有测试的庞大公共 fixture，也不要使用 `lenient()` 隐藏无效 stub。

## Manager 的判断

不要因为类型名以 `Manager` 结尾就一律 Mock：

- 承载统计口径、权限范围或状态规则：优先使用真实对象。
- 封装 Redis、OSS、微信、模型/RAG、外部 HTTP 或消息发送：在 Service 测试中可以 Mock。
- Manager 自身正在被测试：Mock 它的 Mapper 和外部资源，直接断言 Manager 的公共行为。

不要仅因为某个协作者是自有类、依赖较多或 Mock 更容易，就把它替换成测试替身。先判断它承载的是业务规则还是系统边界；Mock 前者会把测试绑定到内部编排，Mock 后者才能隔离不确定环境。

## 验证副作用

外部副作用没有返回值时，允许使用 `verify` 和 `ArgumentCaptor`，但断言业务含义：

```java
ArgumentCaptor<RewardMessage> captor = ArgumentCaptor.forClass(RewardMessage.class);
verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captor.capture());
assertEquals(orderId, captor.getValue().getOrderId());
assertEquals(RewardType.SUN, captor.getValue().getRewardType());
```

不要把调用次数、内部调用顺序或私有协作者结构作为主要断言，除非重复发送或顺序本身就是业务风险。

## 不使用真实数据

禁止在自动化行为测试中使用：

- 共享环境中已经存在的训练营、排期、服务单或教师 ID。
- 固定手机号、JWT、内部 Token 或其他敏感信息。
- 生产或开发环境 URL。
- “查询列表第一条”得到的动态 fixture。

直接构造最小实体和 DTO，并使用明显的测试值。每个测试独立拥有数据，不依赖执行顺序。

## 边界改动

Mock 只能证明调用方行为，不能证明边界实现正确。修改 MyBatis XML、Redis 操作、Nacos/Feign/RestTemplate/RestClient/OkHttp 配置、模型或 RAG adapter、消息路由、事务、动态数据源、SSE 或并发控制时：

1. 保留快速行为测试，验证上层业务语义。
2. 在专项验证中使用真实或等价环境验证边界。
3. 如果环境不可用，记录未验证项和风险，不把 Mock 测试描述为完整保障。
