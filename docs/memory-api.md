# memory

给调用方看的合同。设计备忘见 [memory-engine.md](./memory-engine.md)。

默认接入走 `MemoryRuntime` 三个时机：

| 时机 | 调用 | 做什么 |
|---|---|---|
| 进模型前 | `recalling(graphId)` → `Agent.contextAugmenters` | 字面召回，垫一块有预算的事实，不写回 transcript |
| 4 回合 / 60s / 会话 flush | `learn(graphId, sessionId)` | 有界 Episode 抽取；Claim + Triple 原子提交 |
| 空闲 / 阈值 | `consolidate(graphId)` | 夜间整理近义节点 |

`MemoryStore` 仍是零 LLM 的图。抽取器和整理器由 Runtime 组合，不进 Store。

引擎只做确定性存储：**开放 Claim + 闭集关系 + SQLite 表 + 硬原语**。不理解中文、不改写三元组、不补边、不 `DELETE` 边或节点（只过期）。

调用方交规范 `s / p / o`。非法 `p`、空字段进 `IngestResult.errors`，同批合法稿仍写入。

## 图

| `graph_id` | 用途 |
|---|---|
| `assistant` | 用户档案 |
| `novel:{id}` | 一本书一张图 |

每条读写必带 `graph_id`。跨图 JOIN 禁止。

## 关系

助手闭集见 `PREDICATES`（`allergic_to`、`likes`、`plans`、`child_of` …）。小说闭集见 `NOVEL_PREDICATES`（`is_a`、`related_to`、`foreshadow` …）。重叠只有 `named`、`located_in`。

**功能边**（同一 `s+p` 只留一个活 `o`）：助手 `lives_in` `work_location` `born_in` `works_at` `works_as` `spouse_of` `diet` `work_years`；小说 `located_in` `status` `is_a` `wants`。其余是集合边，可并存。

中文标签（`PREDICATE_ZH`）只用于显示和 `query` 字面匹配标签，不是同义词表。

词表现在在代码里。以后迁到按 `graph_id` 隔离的 `relation` 表（`p`、`label_zh`、`functional`、`scope`），`ingest` 仍只接受已登记的 `p`；新增走 `defineRelation`，抽取不得顺手 INSERT。关系不是节点。

## 双时钟

每条边四格：

| 格 | 钟 | 含义 |
|---|---|---|
| `created_at` | 系统 | 这条编码写入图的时间 |
| `expired_at` | 系统 | 这条编码从活图撤下 |
| `valid_at` | 世界 | 事实开始为真 |
| `invalid_at` | 世界 | 事实不再为真 |

活边（`facts` / `query` / `neighborhood`，时刻 `at`）：

```
created_at <= at
AND (expired_at IS NULL OR expired_at > at)
AND valid_at <= at
AND (invalid_at IS NULL OR invalid_at > at)
```

谁动哪格：

| 操作 | `expired_at` | `invalid_at` | 行 |
|---|---|---|---|
| `ingest` 新边 | 空 | 空（或稿上自带） | INSERT |
| 功能边覆盖 | 旧边现在 | 旧边现在 | 旧行保留 |
| `retract=true` | 现在 | 现在 | 旧行保留 |
| `forget` | 现在 | 不动 | 旧行保留 |
| `mergeNodes` | drop 活边现在 | 不动 | drop 行保留；keep 缺则 INSERT（世界钟拷贝，`created_at=now`） |

没有 `DELETE`。`rebuildFromFactLog` 除外：它丢掉物化视图再重放日志。

## 原语

| 调用 | 语义 |
|---|---|
| `capture(turn)` | 原文入 `raw_event`，未消费 |
| `ingestClaims` / `queryClaims` | 闭集无法忠实表达的原子记忆写入与 FTS 召回 |
| `ingest(drafts) → IngestResult` | 按字写入。`retract=true` 撤匹配边。坏稿进 `errors` |
| `query(graphId, text, budget, at)` | 字面 FTS + 一跳 + 谓语中文标签。空串空结果 |
| `facts(graphId, at, p?, node?)` | 活边；可选按关系或端点名过滤 |
| `recent(graphId, since)` | `created_at` / `updated_at` / `expired_at` ≥ since（只系统钟，含刚过期） |
| `neighborhood(graphId, nodeNames)` | 仍活、碰到这些节点的边 |
| `mergeNodes(graphId, keep, drop)` | 系统过期 drop 活边，缺则在 keep 上 INSERT，alias drop→keep |
| `rebuildFromFactLog(graphId)` | 重放本图 `fact_log` |
| `unconsumed` / `markConsumed` | 原文队列：未抽 / 抽完打勾 |
| `forget(graphId, now)` | 低置信且过旧的活边只打 `expired_at` |
| `pendingReview` / `resolveReview` | 功能覆盖进审；reject 过期，accept 留下 |

夜里工作集 = `recent`（含刚过期）+ `neighborhood`（仍活邻居）。整理走 `MemoryRuntime.consolidate`，不是 Store 原语。

模型侧：`recalling` 是 `ContextAugmenter`。白天工具 `dayTools`（query / facts）；夜里 `nightTools`（recent / neighborhood / merge / facts / ingest）。全量 `graphTools` 留给要自己切子集的人。

`query("火锅")` 只有图里真有「火锅」这个节点才会中。过敏查 `花生` 或 `facts(p="allergic_to")`。
