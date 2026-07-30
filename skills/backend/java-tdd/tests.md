# 项目行为测试示例

以下示例都通过公共方法观察业务行为。测试应能在内部协作者结构发生行为等价的重构后继续成立。

## 目录

- [推荐结构](#推荐结构)
- [验证业务异常](#验证业务异常)
- [验证写入边界的业务数据](#验证写入边界的业务数据)
- [不推荐：启动完整环境](#不推荐启动完整环境)
- [不推荐：只验证内部调用](#不推荐只验证内部调用)
- [不推荐：横向批量编写测试](#不推荐横向批量编写测试)
- [不推荐：复制生产算法](#不推荐复制生产算法)

## 推荐结构

使用 JUnit 5 和 Mockito，直接测试 Service 或 Manager 的公共方法。下面的类型名均为占位符，落地时替换为当前项目已有类型：

```java
@ExtendWith(MockitoExtension.class)
class ItemStatisticsManagerTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemStatisticsManager itemStatisticsManager;

    @Test
    void 符合条件的条目应计入统计() {
        Item eligibleItem = new Item().setEligible(true);
        Item excludedItem = new Item().setEligible(false);
        when(itemRepository.findAll(any())).thenReturn(List.of(eligibleItem, excludedItem));

        ItemStatistic result = itemStatisticsManager.statistics();

        assertEquals(1, result.getEligibleCount());
    }
}
```

示例中的类和方法名表达测试形状；落地时复用仓库真实接口，不为匹配示例新增抽象。

## 验证业务异常

```java
@Test
void 已结束资源不应被刷新() {
    Resource endedResource = new Resource()
            .setEndTime(LocalDateTime.of(2026, 7, 1, 0, 0));
    when(resourceRepository.findById(100L)).thenReturn(endedResource);

    assertThrows(BusinessException.class,
            () -> resourceService.refresh(100L, false));
}
```

`BusinessException` 仅表示项目定义的业务异常类型。若异常包含稳定的业务码，同时断言业务码；不要依赖容易变化的完整异常文案。

## 验证写入边界的业务数据

当方法没有返回值，持久化结果就是可观察行为时，使用 `ArgumentCaptor`：

```java
@Test
void 取消后应将资源状态更新为已取消() {
    when(resourceRepository.findById(100L)).thenReturn(activeResource());

    resourceService.cancel(100L);

    ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
    verify(resourceRepository).save(captor.capture());
    assertEquals(ResourceStatus.CANCELLED, captor.getValue().getStatus());
}
```

这里断言的是最终业务状态，不是单纯断言 `updateById` 被调用。

## 不推荐：启动完整环境

```java
@SpringBootTest // 或当前框架对应的完整应用上下文测试注解
class ItemStatisticsIntegrationTest {

    @Test
    void testStatistics() {
        Long itemId = 1232L;
        System.out.println(itemService.statistics(itemId));
    }
}
```

问题：依赖现有数据，没有明确期望值，失败原因可能来自数据库、缓存、配置中心或外部服务，无法形成快速红绿循环。

## 不推荐：只验证内部调用

```java
@Test
void testDelete() {
    itemService.delete(100L);
    verify(itemRepository).deleteById(100L);
}
```

除非删除调用本身就是完整契约，否则还应验证权限、允许删除的状态、关联清理或业务异常等可观察结果。

## 不推荐：横向批量编写测试

不要先为完整功能一次性写出所有测试，再统一实现生产代码。每次选择一个最小业务场景，完成一次红、绿和局部整理，再根据本轮结果决定下一个场景，避免测试固化尚未验证的设计假设。

## 不推荐：复制生产算法

```java
int expected = items.stream()
        .filter(Item::isEligible)
        .filter(item -> item.getStatus() == 1)
        .toList()
        .size();
assertEquals(expected, result.getUnassignedCount());
```

使用人工推导的明确常量代替：

```java
assertEquals(2, result.getUnassignedCount());
```

测试数据应小到可以人工确认结果。
