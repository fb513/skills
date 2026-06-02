---
name: api-test-generation
description: "基于 java-writing-plans 生成的设计文档，整理接口测试场景并生成 pytest 测试或测试骨架（api-tests/）。TRIGGER when: 用户指定设计文档目录并要求生成接口测试、测试设计或测试骨架。SKIP: 单个接口的简单测试、不涉及设计文档的任务。"
---

# 基于设计文档的接口测试生成

读取设计文档目录，把验收标准转换成可运行或可收集的 pytest 接口测试。复杂 story 或用户要求时，将测试设计写入 `reports/verification.md` 的“测试设计”部分；小任务在最终回复中概述即可。

---

## 输入

用户提供设计文档目录路径（如 `docs/plans/20260501-xxx/`）。如果用户未指定，主动询问。

---

## 约束

1. **使用现有测试框架** — 优先使用 `core/client.py`、`core/assertions.py` 和现有 fixture，不轻易引入新依赖
2. **测试数据自给自足** — 前置数据通过 fixture 或接口创建，不依赖数据库预置数据
3. **不修改业务代码** — 本技能只生成测试设计和测试代码
4. **场景来自验收标准** — 覆盖正常路径、错误场景、权限、边界和状态流转中实际存在的风险点，不机械凑全
5. **特殊接口显式标记** — SSE / AI 接口用 `@pytest.mark.requires_ai`，破坏性接口用 `@pytest.mark.destructive`

---

## 执行流程

### Phase 1：读取上下文

1. 读取 `00-main-design.md` 和所有切片文件，理解接口、依赖和验收标准
2. 读取现有测试代码学习项目约定：
   - `api-tests/conftest.py`
   - `api-tests/tests/conftest.py`
   - `api-tests/core/assertions.py`
   - `api-tests/core/client.py`
   - 1-2 个现有测试文件

### Phase 2：整理测试场景

形成轻量测试设计，至少包含：

- 覆盖哪些接口和验收标准
- 每个接口的关键场景、前置数据、操作和期望结果
- fixture 依赖链和清理策略
- 需要跳过、标记或人工确认的场景

复杂 story、跨多个切片或用户要求留档时，把以上内容写入 `docs/plans/<story>/reports/verification.md` 的 `## 测试设计` 部分。

### Phase 3：生成测试代码

输出位置：`api-tests/tests/test_NN_<module>.py`（编号接续现有最大编号）。

生成规则：

1. 代码已实现时，生成可直接运行的测试
2. 代码尚未实现时，生成可收集的 skeleton，并用 `skip` / `xfail` 写明未运行原因
3. 不只写 happy path；优先覆盖最可能出错的权限、异常、边界和状态流转
4. 如需共享 fixture，追加到 `api-tests/tests/conftest.py`，避免重复造数据

### Phase 4：验证

优先运行：

```bash
cd api-tests && python -m pytest --collect-only
```

如果环境缺依赖或服务不可用，至少做语法检查并说明阻塞原因。

---

## 输出

最终回复简要列出：

- 生成或修改的测试文件
- 覆盖的核心场景
- 跳过 / xfail / 人工确认项
- 已运行的验证命令和结果

---

## 下一步

如果业务代码尚未实现，调用 `/java-development`。如果业务代码已经实现，调用 `/api-test-verify`。
