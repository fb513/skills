---
name: implement
description: "Implement a piece of work based on a spec or set of tickets."
disable-model-invocation: true
---

按用户给的 spec 或 ticket 实现工作。开始前判断处于哪种模式：

- **单票模式** —— 用户指定了一张具体的票，或你是被驱动模式派出的子 agent。只做这一张，做完汇报，不要去取下一张。
- **驱动模式** —— 用户给的是 feature slug 或一组票。按下面的 frontier 循环逐张跑完。

Java 改动遵守 `/java-coding-standards`，测试走 `/tdd`，碰表/字段/索引走 `/db-change`。

## 先确认项目约定

本 skill 不假设票据存在哪里、用什么构建工具。开跑前落实三件事：

- **票据与状态机制** —— 读 `docs/agents/issue-tracker.md`（缺失则先跑 `/setup-matt-pocock-skills`）。它决定票怎么读、状态怎么记：本地 Markdown 是文件里的 `Status:` 行，真实 tracker 则是标签或状态字段。状态取值见 `docs/agents/triage-labels.md`。
- **构建与测试命令** —— 从构建文件判断（`pom.xml` → Maven，`build.gradle` → Gradle，有 wrapper 优先用 wrapper）。具体命令见下面"验证"一节。
- **项目自有的收尾要求** —— 代码索引/图谱工具、文档同步、lint 等。项目的 agent 约定文档（`CLAUDE.md` / `AGENTS.md`）里通常写明了，先读一遍，收尾时按它执行。

---

# 驱动模式

一次只跑一张票，**不并行**。并行的前提是每张票都有独立的数据库和外部依赖，而这在多数项目里不成立：各环境常共用同一个数据库实例，测试集也常不是 hermetic。开跑前确认一次，共用就串行 —— 并行会让实库核验和测试互相污染，且失败原因极难归因。

## 开跑前确认分支

驱动模式一张票一个提交，会连续产生多个提交。如果当前分支就是主干（`master` / `main` 或项目约定的主干），先停下问用户是要建 `feat/<slug>` 分支还是确实要提交到主干 —— 不要默认往主干连着提交。确认后再进入循环。

## frontier 循环

1. 按 `issue-tracker.md` 的约定取出这一组票，解析每张的**状态**和**blocker**。本地 Markdown 的状态行两种写法都要认（裸 `Status: x` 和加粗 `**Status:** x`）；`Blocked by: None` 或 `None — can start immediately.` 均视为无 blocker。
2. **frontier** = 状态为 `ready-for-agent` 且所有 blocker 都已 `resolved` 的票。取编号最小的一张。
3. 置状态为 `claimed` 并**持久化**（本地文件存盘 / tracker 上改标签），**再**开始工作。这一步先做，是为了让中断的运行看得出停在哪张票。
4. 派一个**全新子 agent** 只做这一张票，brief 见下。
5. 子 agent 返回后：把摘要按 tracker 约定追加为评论，置状态为 `resolved`。
6. 回到第 2 步，直到 frontier 为空。
7. 跑一次项目要求的全局收尾动作（代码索引/图谱更新等，见"先确认项目约定"）。这类操作通常幂等且有缓存，每票重跑只是重复劳动，收尾统一跑一次即可。
8. 只读累积的摘要做一次跨票检查：有没有重复实现、命名不一致、该抽没抽的共性。不重读全量 diff，成本低但能捞到逐票评审看不见的东西。
9. 汇总所有摘要写发布前记录（位置见 `issue-tracker.md`，本地 tracker 通常与 PRD 同目录），含待执行 DDL 清单。

驱动者**自己不写业务代码**，只算 frontier、派活、收摘要、改 Status。这样驱动者的上下文只有票标题和摘要，不会被实现细节撑爆；每张票也拿到干净的上下文窗口，正好对上 `to-tickets` 的切片尺寸约定。

## 派给子 agent 的 brief

包含：票的全文（本地文件给路径，tracker 给编号或 URL）、所属 PRD/spec 的位置、相关 ADR 路径、以及本项目的编译与定向测试命令。要求它：

> 只实现这一张票。遵守 `java-coding-standards`，按 `tdd` 的红绿循环推进。用编译和定向测试验证；测试集不是 hermetic 时不要跑全量。完成后按 Conventional Commits 提交到当前分支。
>
> **动手写代码之前先判一次这张票要不要碰 schema**（表、字段、索引、唯一约束）。要碰就先调 `db-change` 再动 Entity —— 它的同步点清单和安全顺序必须在写代码前就位，写到一半才想起等于漏掉逐项确认。只写 DDL 脚本，不在真实环境执行。判不准就当作要碰，多调一次的成本远低于漏调。
>
> 项目有代码索引/图谱工具时，注意它可能滞后于同一次驱动中更早完成的票。查本 feature 内新增的类型或方法时直接读文件或 `git log`，不要只凭索引查不到就断定它不存在 —— 那会导致重复实现一个已有的深模块。
>
> 返回一段摘要，必须包含：改了哪些文件、新增/修改了哪些测试、**红灯时的失败信息原文**（证明测试是因目标行为未实现而失败，不是因框架/网络/配置）、跑了哪些测试命令及结果、本票是否需要 DDL、有没有未覆盖的边界风险。

红灯证据这一条不能省。它是驱动模式下唯一能事后核验 TDD 纪律是否真的执行的凭据。

## 必须中断的三种情况

中断时保留已 `resolved` 的票，把当前票退回 `ready-for-agent` 并按 tracker 约定记下原因，然后停下报告。修完后对同一组票重新进入驱动模式会从 frontier 续跑。

- **测试重试后仍不通过** —— 不允许修改测试迁就实现，也不允许把断言放宽。
- **编译失败且不是本票引入的** —— 说明上一张票留了坑，先解决再继续。
- **子 agent 报告的改动超出票的范围** —— scope 漂移，停下让用户判断。

每张票单独提交，所以每张票都是一个可 revert 的断点。

## DDL 由用户执行

DDL 的**编写在流程内，执行在流程外**。子 agent 照常改 Entity、Mapper XML 和 Convertor，并按 `db-change` 的约定把 DDL 写成脚本；`db-change` 的隔离核验可以做。**不在共享环境执行 DDL，不改业务 schema。**

需要 DDL 的票照常标 `resolved` —— 代码侧确实完成了，且下游票靠 Mock Mapper 就能开工，不必等真库。DDL 待执行是独立维度，记在发布前汇总的 **待执行 DDL** 一节：脚本路径、涉及的表和列、执行顺序、是否需要回填。收尾时把这份清单明确交给用户。

---

# 每张票的验证与收尾

以下适用于**做单张票的那个 agent** —— 单票模式下是你自己，驱动模式下是每个子 agent。驱动者不执行这些，它只在全部票跑完后汇总发布前记录。

## 验证

Java 没有独立的 typecheck 步骤，用编译代替 —— 编译是最快的结构性反馈，改完就跑，不要攒到最后。

Maven：

```bash
mvn -q -DskipTests clean compile          # 频繁运行
mvn test -Dtest=<TestClass>               # 单个测试类，频繁运行
mvn test "-Dtest=<TestClass>#<method>"    # 单个方法
```

Gradle：

```bash
./gradlew compileJava -q                          # 频繁运行
./gradlew test --tests '<TestClass>'              # 单个测试类
./gradlew test --tests '<TestClass>.<method>'     # 单个方法
```

项目有自己的封装命令（Makefile、脚本、agent 约定文档里写明的命令）时优先用它。

**跑全量测试前先确认测试集是否 hermetic。**很多项目的测试集混着会连真实数据库、缓存、第三方服务或公网的用例，甚至会创建和删除外部资源 —— 这种情况下不要跑全量，只运行与改动相关的确定性测试集合（Maven 用逗号分隔多个类，Gradle 用多个 `--tests`），并如实说明运行了哪些、为什么没跑全量。项目的 agent 约定文档通常已经写明属于哪种情况，先读它；没写明就先抽查几个测试的实现再决定。

## 收尾

1. 用 `/code-review` 评审本票改动。它每票都要跑，不能挪到最后 —— 票 01 引入的坏模式如果等到第 8 张票之后才发现，02 到 08 已经照着抄了七遍，报告会从"改一处"变成"改八处"。
2. 提交到当前分支。

`code-review` 内部 fork 的并行子 agent 有超时或中断的可能。它没产出实质报告时，不要跳过评审：自己按相同的双轴标准（Standards / Spec）复核本票 diff，并在摘要里注明是降级复核。

项目要求的代码索引/图谱更新不在这里跑，由驱动模式收尾统一跑一次。单票模式下自己跑一次。

提交信息沿用仓库既有约定 —— 先 `git log --oneline` 看前几十条实际用的是什么格式、scope 取值和正文语言，照着写。多数项目是 Conventional Commits、scope 用业务模块名：

```text
feat(<module>): 新增<能力>
fix(<module>): 修复<问题>
refactor(<module>): <重构内容>
```

提交前检查工作树有没有本次规格之外的脏改动，有则排除。除用户明确要求，不推送、不建分支、不合并。

# 上下文

单票模式下上下文将要溢出但票没做完，用 `/handoff` 交接，不要硬撑。跨票的关键事实写进票或 ADR，不靠会话记忆传递。
