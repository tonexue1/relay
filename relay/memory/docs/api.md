# 对外接口

模块 `:relay:memory`。接入方默认只碰 `MemoryRuntime` 三个时机。图合同和工具是下一层。

内部怎么存、怎么召回，见 [internals.md](./internals.md)。

## 默认路径

```kotlin
val store = SqliteMemoryStore(RoomMemoryDb.file(context), FileArtifactStore(dir))
val memory = MemoryRuntime(
    store = store,
    extractor = CloudTripleExtractor(provider),
    consolidator = AgentConsolidator(provider, store),
)
val recall = RecallContext(
    sessionId = sessionId,
    taskScopeId = taskId,
)

val agent = Agent(
    provider, config,
    tools = memory.dayTools(graphId, recall),
    contextAugmenters = listOf(memory.recalling(graphId, recall)),
)

store.capture(RawTurn(graphId, "user", input, sessionId, taskScopeId = taskId))
val reply = agent.run(input).text.orEmpty()
store.capture(RawTurn(graphId, "assistant", reply, sessionId, taskScopeId = taskId))

memory.learn(graphId, sessionId) // Episode 阈值 / 空闲 / 会话结束，不要每轮调
memory.consolidate(graphId)      // 空闲或阈值，不要每轮
```

> TODO(UIKit)：这里暂以 `AgentResult.text` 作为助手输出。UIKit 落地后，UI tool call
> 可能就是用户可见结论；届时应 capture 完整 visible turn 的文本投影（文本 +
> `WidgetSpec.summary()`），不要只记录最终 reply、tool ack 或原始 spec JSON。

`Agent` 不接收 `MemoryStore`。`Provider` 只进抽取器和整理器。

## MemoryRuntime

| 调用 | 时机 | 返回 |
|---|---|---|
| `recalling(graphId, RecallContext, pin, budgetChars, querySelector)` | 每次进模型前 | `ContextAugmenter` |
| `dayTools(graphId, RecallContext)` | 白天 Agent | scoped query / facts |
| `nightTools(graphId)` | 夜间 Agent | recent / neighborhood / merge / facts / ingest |
| `learn(graphId)` | 处理最早的未消费 session | `LearnReport` |
| `learn(graphId, sessionId)` | 同一会话积成 Episode 后 | `LearnReport` |
| `learnBatch(batch)` | 宿主自己规划批次时 | `LearnReport` |
| `consolidate(graphId, since)` | 空闲 / 阈值 | `ConsolidationReport` |

`pin` 会无条件垫进召回块（小说 logline 用）。`budgetChars` 默认 2000。
`querySelector` 默认选择最后一条 user 消息；宿主可传 `RecallQuerySelector` 排除合成消息或绑定任务查询。

`RecallContext(sessionId, taskScopeId)` 将候选限制为：已确认的 `PROFILE`、当前
`TASK`、当前 `SESSION`。默认 `allowCrossTask=false`；只有宿主明确创建
`RecallContext(..., allowCrossTask=true)` 时，白天工具才可跨任务读取，模型参数本身
不能打开该能力。无 context 的旧重载继续保留以兼容现有调用；新助手接入应传 context。

`LearnReport`：`runId`、`sessionId`、`eventIds`、`claims`、`drafts`、`outcome`、抽取/入库错误。无未消费原文时为空报告。

`CloudTripleExtractor` 双输出：注册关系能忠实表达的事实进 `drafts`；复杂但耐久的信息进开放 `claims`。合法纯闲聊是 `SUCCESS_EMPTY` 并消费原文。用户轮看起来像个人事实但两边都空时，运行时改判 `LOW_YIELD`，**不消费**，下次还可以重抽。解析失败、截断、拒答也不消费原文。

没有挂 consolidator 时，`consolidate` 返回空报告。

## 造库

```kotlin
SqliteMemoryStore(context)                 // 内存 Room
SqliteMemoryStore(context, file)           // 文件 + 同目录附件
SqliteMemoryStore(RoomMemoryDb.file(ctx), FileArtifactStore(dir))
```

`RoomMemoryDb.inMemory(context)` / `file(context, name|File)`。测试用 `InMemoryMemoryStore(context)`。

## 图合同（MemoryStore）

每笔读写带 `graphId`。引擎不理解中文、不抽三元组、不 DELETE 边（只过期）。

| 调用 | 语义 |
|---|---|
| `capture(turn)` | 原文入队，未消费 |
| `ingest(drafts)` | 按字写入。坏 `p` / 空字段进 `errors` |
| `ingestClaims` / `queryClaims` / `claims` | 开放 Claim 写入、FTS 查询、枚举 |
| `query(graphId, text[, RecallContext])` | 收紧后的字面 FTS + 一跳 |
| `facts(graphId[, RecallContext])` / `recent` / `neighborhood` | 活边 / 近系统钟 / 邻居 |
| `mergeNodes(keep, drop)` | 并节点，只动系统钟 |
| `unconsumed` / `markConsumed` | 抽取队列 |
| `forget` / `pendingReview` / `resolveReview` | 剪边、功能覆盖审核 |
| `rebuildFromFactLog` | 丢掉活图再重放 |

稿：`RawTurn`、`ClaimDraft`、`TripleDraft`（可 `retract`、`validAt` /
`invalidAt`、`scope` / `state` / `scopeId`）。命中：`OpenClaim` / `MemoryHit` /
`Fact`，均可读取作用域元数据。作用域为 `PROFILE` / `TASK` / `SESSION`，状态为
`CANDIDATE` / `CONFIRMED`。`GRAPH_ASSISTANT`、`PREDICATES` 在根包。

## 模型侧零件

需要自己拼、不走 Runtime 时：

| 符号 | 包 | 用途 |
|---|---|---|
| `MemoryStore.recalling` | `relay.memory.agent` | 与 Runtime 同一 augmenter |
| `graphTools` / `dayTools` / `nightTools` | 同上 | `graphId` 绑死，模型不能 hop 图 |
| `MemoryExtractor` / `CloudTripleExtractor` | `relay.memory.extract` | 对话 → `TripleDraft` |
| `MemoryConsolidator` / `AgentConsolidator` | `relay.memory.dream` | 夜间整理 |
| `DREAM_SYSTEM` | 同上 | 夜班 system，一般不必自己挂 |

召回必须是 augmenter，不要塞进 `transformContext`。后者只投影正常对话；窗口裁剪由 Agent 统一做。

事实垫成临时 user 消息。不要垫 system：`Agent.withSystem` 会丢掉。
