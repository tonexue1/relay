# 内部结构

Gradle 模块 `:relay:memory`。图引擎零 LLM；模型只出现在抽取器和整理器里。

对外合同见 [api.md](./api.md)。

## 包

```
relay.memory            合同 + MemoryRuntime
relay.memory.engine     Room、SqliteMemoryStore、附件、TextNorm
relay.memory.agent      recalling、recallPad、graphTools / dayTools / nightTools
relay.memory.extract    MemoryExtractor、CloudTripleExtractor
relay.memory.dream      MemoryConsolidator、AgentConsolidator、DREAM_SYSTEM
```

`MemoryStore` 不依赖 `Provider`。`MemoryRuntime` 把 store、抽取器、整理器拼在一起，不持有协程生命周期。

## 四层账

| 层 | 表 / 对象 | 纪律 |
|---|---|---|
| 原文 | `raw_event` | 只追加。`capture` 写入，`consumed=false` |
| 开放记忆 | `claim_log` / `claim_fts` | 闭集无法忠实表达的原子事实；保留 provenance，可字面召回 |
| 已采纳 | `fact_log` | 只追加。`ingest` 成功才记 |
| 活图 | `node` / `edge` / alias / FTS | 可丢。过期不 DELETE；坏了重放 `fact_log` |

附件走 `ArtifactStore`（长原文 ref），不进 FTS。
每次云抽取另记 `extraction_run`：输入事件、上下文事件、finish 状态、原始响应和错误。解析失败、截断或拒答不消费原文。

`graph_id` 是硬隔离。跨图 JOIN 禁止。助手图是 `assistant`；小说是 `novel:{id}`。
schema 7 在 `fact_log`、`claim_log`、`edge` 上持久化并索引
`scope` / `scope_id` / `state`；6→7 迁移把旧事实/边视为已确认 profile，把旧 claim
视为所属 session 的 candidate。

## 作用域与采纳

`MemoryScope`：`PROFILE`（跨会话）、`TASK`（当前任务）、`SESSION`（当前会话）。
`MemoryState`：`CANDIDATE`、`CONFIRMED`。

默认分类是确定性的保守规则：

- 用户主体的过敏、饮食、出生/教育、语言、亲属、宠物等耐久白名单，
  以及明确的 `prefers`，可成为 `PROFILE + CONFIRMED`
- `likes` / `dislikes` 等弱偏好默认为 `PROFILE + CANDIDATE`；两条独立 user 证据一致时晋升
- `plans` / `has_task` / `worked_on` / `has_component` / `uses_technology`
  默认为 `TASK + CANDIDATE`，未显式给 `scopeId` 时使用 raw turn 的 `taskScopeId`
- 其他关系保守落入当前 `SESSION + CANDIDATE`
- 开放 claim 默认为 `SESSION + CANDIDATE`
- provenance 只有 assistant turn 时，即使草稿要求 confirmed 也降为 candidate

调用方显式填写 draft 的 scope/state 可覆盖一般默认值，但 assistant-only 的降级不可覆盖。

## 边的双时钟

| 格 | 钟 | 谁动 |
|---|---|---|
| `created_at` / `expired_at` | 系统 | 图何时学会、何时从活图拿掉 |
| `valid_at` / `invalid_at` | 世界 | 事实在现实里何时为真 |

`query` / `facts` / `neighborhood` 四格都过才算当前活边。`forget` / `mergeNodes` 只打系统钟。`retract` 和功能边覆盖两套钟一起打。

谓语闭集：`PREDICATES`（助手）和 `NOVEL_PREDICATES`（小说）。非法 `p` 进 `IngestResult.errors`，同批合法稿仍写。

## 召回怎么走

`recalling(graphId, RecallContext)` 是 `ContextAugmenter`，不改 transcript。

1. 默认取最后一条真实 user 文本；宿主也可传 `RecallQuerySelector` 明确选择查询文本
2. 候选作用域：`CONFIRMED PROFILE + current TASK + current SESSION`
3. `query`：FTS5 粗召回，字面覆盖复核后取一跳活边，按置信度 × 新旧排序
4. `queryClaims`：同样经过作用域与字面覆盖复核的开放 Claim
5. `recallPad` 收成 `已知事实:` + `相关经历:`（共享 `budgetChars`）
6. 命中则垫一条临时 `Message.user`；未命中不垫

Agent 侧：augmenter → 预留 token → `transformContext` → `WindowTrim` → `system + 事实块 + 裁过的对话`。事实块不算最老消息，不会被 trim 先删。

字面召回：`query("火锅")` 只有图里真有「火锅」才中。过敏查「花生」。短 CJK
2-gram 只重合一次不算命中；需要完整实体出现在 query 中，或至少两个独立 token 覆盖。
谓语只在 query 等于完整谓语标签时触发，不再因为句中子串把整类边全部拉回。

## 学习怎么走

`learn(graphId[, sessionId])`：

```
LearnBatchPlanner(session + turn/char 上限 + 2 回合上下文)
→ extraction_run
→ extractor(claims + triples)
→ claim_log + ingest
→ markConsumed(仅本批新事件)
```

合法 JSON 的纯闲聊空稿会消费，不堵队列。用户轮像个人事实但抽取两边都空时，运行时改判 `LOW_YIELD`，不消费，可重抽；项目/架构硬事实会再落一条 claim 兜底。`PARSE_FAILED` / `TRUNCATED` / `REJECTED` 和网络异常不消费，可重试。非法图稿进 `LearnReport.errors`；同批 Claim 仍写。

默认抽取器 `CloudTripleExtractor` 调云模型。能被注册谓语忠实表达的事实输出 `TripleDraft`；复杂项目、架构和条件策略输出开放 `ClaimDraft`，禁止硬凑关系。store 只按字入库。

## 整理怎么走

`consolidate(graphId, since)` 交给可选的 `MemoryConsolidator`。默认 `AgentConsolidator`：另开 Agent，system 是 `DREAM_SYSTEM`，工具是 `nightTools`（recent / neighborhood / merge_nodes / facts / ingest）。

整理是维护，不是每轮必跑。Runtime 不自己调度。

白天 Agent 只挂 `dayTools`（query / facts）。工具绑定宿主提供的 `RecallContext`；
默认禁止跨任务，只有宿主设置 `allowCrossTask=true` 才放开 task 边。写图只来自
`learn` 的抽取，或整理器的受限工具。
