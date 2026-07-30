# 项目行为测试示例

## 推荐结构

使用 JUnit 5 和 Mockito，直接测试 Service 或 Manager 的公共方法：

```java
@ExtendWith(MockitoExtension.class)
class OrderStatisticsManagerTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderStatisticsManager orderStatisticsManager;

    @Test
    void 无导师的正常服务单应计入待分配统计() {
        Order unassignedOrder = new Order()
                .setOrderStatus(OrderStatus.NORMAL.getCode())
                .setTeacherId(null);
        Order assignedOrder = new Order()
                .setOrderStatus(OrderStatus.NORMAL.getCode())
                .setTeacherId(10L);
        when(orderMapper.selectList(any())).thenReturn(List.of(unassignedOrder, assignedOrder));

        OrderStatisticResp result = orderStatisticsManager.statistics(100L);

        assertEquals(1, result.getUnassignedCount());
    }
}
```

示例中的类和方法名表达测试形状；落地时复用仓库真实接口，不为匹配示例新增抽象。

## 验证业务异常

```java
@Test
void 已结束排期不应被日常刷新() {
    Schedule endedSchedule = new Schedule()
            .setEndTime(LocalDateTime.of(2026, 7, 1, 0, 0));
    when(scheduleMapper.selectById(100L)).thenReturn(endedSchedule);

    assertThrows(WebBaseException.class,
            () -> scheduleService.refresh(100L, false));
}
```

若异常包含稳定的业务码，同时断言业务码；不要依赖容易变化的完整异常文案。

## 验证写入边界的业务数据

当方法没有返回值，持久化结果就是可观察行为时，使用 `ArgumentCaptor`：

```java
@Test
void 退款后应将服务单状态更新为已退款() {
    when(orderMapper.selectById(100L)).thenReturn(normalOrder());

    orderService.refund(100L);

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMapper).updateById(captor.capture());
    assertEquals(OrderStatus.REFUNDED.getCode(), captor.getValue().getOrderStatus());
}
```

这里断言的是最终业务状态，不是单纯断言 `updateById` 被调用。

## 不推荐：启动完整环境

```java
@SpringBootTest
class ScheduleStatisticsTest {

    @Test
    void testStatistics() {
        Long scheduleId = 1232L;
        System.out.println(statisticsService.statistics(scheduleId));
    }
}
```

问题：依赖现有数据，没有明确期望值，失败原因可能来自数据库、Redis、Nacos 或外部服务，无法形成快速红绿循环。

## 不推荐：只验证调用

```java
@Test
void testDelete() {
    scheduleService.delete(100L);
    verify(scheduleMapper).deleteById(100L);
}
```

除非删除调用本身就是完整契约，否则还应验证权限、允许删除的状态、关联清理或业务异常等可观察结果。

## 不推荐：复制生产算法

```java
int expected = orders.stream()
        .filter(order -> order.getTeacherId() == null)
        .filter(order -> order.getOrderStatus() == 1)
        .toList()
        .size();
assertEquals(expected, result.getUnassignedCount());
```

使用人工推导的明确常量代替：

```java
assertEquals(2, result.getUnassignedCount());
```

测试数据应小到可以人工确认结果。
