---
name: implement-spec
description: "用代码实现 /to-spec 和 /to-tickets 的产出。"
disable-model-invocation: true
---

你已经拿到了一个 spec。这个 spec 应该已经关联了若干 ticket，用于描述如何实现该 spec。

目标是在**单一集成分支**上完成整个 spec 的实现，并按照 issue tracker 关闭工作的方式解决每一个 ticket。

这些 ticket 不是一串步骤，而是一张带有阻塞关系的**任务图**。

与子 agent 之间的沟通应当尽量简洁。优先通过**上下文指针**沟通：指向 spec、ticket、调研笔记和之前的提交。不要重复指针已经能拿到的信息。

**实现型子 agent** 应尽可能放到后台运行，以获得最大并发度。

## 步骤

1. 阅读 spec 和 ticket，理解任务图。

2. （可选）使用一个**探索型子 agent** 完成 ticket 所需的任何调研——相关代码文件或外部文档。它应把 markdown 笔记保存到 `.scratch/<feature>/research/`，供后续所有子 agent 访问。这样可以让**实现型子 agent** 专注于实现而非调研。

3. 使用**实现型子 agent** 实现每个 ticket，如果任务不冲突，就派遣多个子 agent 并行实现。

4. 所有 ticket 完成后，在主 agent 上调用 `code-review` 这个 Skill。由单个**实现型子 agent** 修复 code review 提出的所有问题。

5. 清理：删除 `.scratch/<feature>/research/` 目录。笔记是过程产物，不是交付物；需要保留的结论应在关闭 ticket 时写进对应的 `## Comments`。
