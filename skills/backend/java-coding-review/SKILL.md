---
name: java-coding-review
description: "Java 代码验证与审查。编译检查 + 基于设计文档和编码规范的 diff 审查。纯 review 请求默认报告；开发自检、跑通编译或用户要求修复时处理本次任务相关问题。TRIGGER when: Java 代码变更完成后需要验证，或用户主动要求审查代码。SKIP: 纯阅读分析、不涉及代码变更的任务。"
---

# Java 代码验证与审查

对当前 Java 相关变更做编译检查和 diff 审查。纯 review 请求按 code review 方式输出 findings；执行型请求修复本次任务相关问题。

---

## 模式判断

- 审查模式：用户说“review / 审查 / 看看问题 / 验证一下”，只报告 findings 和验证结果
- 修复模式：用户说“修复 / 跑通 / 编译通过 / 开发自检”，修复依据明确的本次任务相关问题

---

## 执行流程

### Step 1：收集上下文

1. 运行 `git status --short`，区分本次任务相关改动和无关改动
2. 运行 `git diff HEAD`，审查新增和修改内容
3. 如果用户提供设计文档目录，读取主设计、切片和 `reports/dev-progress.md`（如存在）
4. 调用 `java-coding-standards` 加载编码规范

### Step 2：工具检查

运行：

```bash
mvn clean compile
```

编译失败是 blocker。审查模式只报告；修复模式下可以修复依据明确的编译问题。连续 3 次无法修复时停止并说明阻塞原因。

### Step 3：审查重点

只审查本次任务相关的 Java、XML、DTO diff；无关改动只记录风险，不碰文件。

重点关注：

- 实现是否符合设计文档和切片验收标准
- Controller / Service / Mapper 分层是否清晰
- 参数校验、权限校验、状态校验和异常路径是否遗漏
- DTO、实体、MyBatis-Plus、CommResp 等用法是否符合项目习惯
- 是否引入重复实现、不必要抽象或破坏既有兼容性
- 测试覆盖是否与变更风险匹配

### Step 4：处理 findings

默认模式：

- 输出 blocker / major / minor findings
- 不修改代码
- Spec 灰区直接向用户确认

开发自检或修复模式：

- 只修复本次任务相关 finding
- 每个修复都要有设计文档、编码规范、编译错误或用户确认作为依据
- 修复后运行必要验证命令

复杂 story 或用户要求留档时，把编译结果和关键 findings 追加到 `docs/plans/<story>/reports/verification.md` 的 `## Code Review` 部分；否则最终回复即可。

---

## 输出

最终回复简要列出审查范围、编译结果、关键 findings、已修复内容（如有）和剩余风险。
