# manifest.yaml 字段

只存推导不出来的值。三个字段，其中 `relation` 和 `prototype.ref` 是仅有的两个无法从目录结构反推的信息。

```yaml
relation: null
prototype:
  ref: null
  sha256: null
```

| 字段 | 含义 | 写入条件 |
|---|---|---|
| `relation` | 本轮与基准的关系：`independent` / `derive` | `prd-spec-iteration` 创建目录时问定。答「还不确定」留 `null`，由 `prd-spec` 补判 |
| `prototype.ref` | 本轮基线原型的实际位置：`./prototype.html` 或 `../0001-xxx/prototype.html` | 两种情况：`derive` 复制原型时由 `prd-spec-iteration` 写 `./prototype.html`；其余由 `prd-spec` 确定基线原型时写 |
| `prototype.sha256` | 该文件的 SHA-256，64 位小写十六进制 | `prd-spec` 生成时现场算 |

## `relation`

| 值 | 含义 | 对流程的影响 |
|---|---|---|
| `derive` | 本轮是基准的演进 | 读基准 PRD 全文判断影响面，但只写本轮改动；修改/废止的条目写前身与双来源；`FR-xxx` 沿用基准编号 |
| `independent` | 独立需求，最多复用登录与角色 | 只读基准的「用户与角色」「术语」两节；PRD 从零写；原型不得指向其他需求目录 |

没有基准目录时是 `independent`，写入后不再变——中途改变关系等于开新需求。

## `prototype`

三种取值，用 `ref` 区分：

| `ref` | 含义 |
|---|---|
| `./prototype.html` | 本目录存着基线原型。`derive` 时 `prd-spec-iteration` 已从基准复制一份，讨论期就在这份上改 |
| `../0001-xxx/prototype.html` | 本目录没有文件，沿用基准那份 |
| `null` | 还没填。判断原型在不在，只看文件在不在 |

`ref` 指向其他目录时，`sha256` 仍要写一份。这不是重复校验——基准目录的 manifest 负责「防篡改」，本目录这份负责「记下本轮用的是哪一份」，基准目录被移走或归档后它还在。

**约定优于配置**：`ref` 缺失或为 `null` 时，默认就是 `./prototype.html`。判断原型在不在，只看文件在不在，不要因为字段没填就认为没有原型——早期目录（`prototype_sha256` 那一版）根本没有 `ref` 这个字段，但文件一直在。

## 刻意不存的字段

| 不存 | 原因 |
|---|---|
| `iteration` | 从目录名 `0001-订单批量导出` 的前四位直接读出 |
| `base_iteration` | 可推导：小于当前编号的最大已存在目录 |
| `directory` / `workspace` | 目录本身即记录 |
| `prd` / `spec` / `facts` / `change_summary` | 全是固定文件名，约定优于配置 |
| `feature` | 目录名 `0001-` 之后的部分即需求名称，不必另存；改名称改 PRD 标题，目录名不动 |
| 原型文件的大小、修改时间、标题 | 哈希已经唯一确定内容 |

`relation` 与 `base_iteration` 容易被看成一类，其实相反：前者**必须存**（推不出来），后者**不能存**（一定推得出来）。判断标准只有一条——能否从目录名和已有目录推出来。

## `prototype.sha256` 的作用

只回答一个问题：**本轮确认的是不是当初那一份。**

生成时现场算，之后哈希不符就说明被改动过——历史目录不可改写，应报告并要求新建迭代。

**它不决定要不要复制。** 复制与否只看目标目录里有没有文件：`derive` 时 `prd-spec-iteration` 已经复制了一份，`prd-spec` 只需确认并锁定；没有文件时才问产品是沿用基准还是本轮无原型。

它也不承担「输入封口」职责。本流程允许生成时读取当前聊天上下文，那部分无法哈希，所以不做输入不可变的承诺——只对原型这一份客观输入负责。
