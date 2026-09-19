# `:relay:memory`

端上账本：写入、状态字段、召回。宿主只应碰 `MemoryRuntime`。

Maven：`io.github.tonexue1:relay-memory:0.1.0`（AAR，minSdk 28）

设计合同（评审用，更细）：[docs/README.md](docs/README.md)

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`MemoryRuntime`](src/main/kotlin/relay/memory/api/MemoryRuntime.kt) | `capture` / `commit` / `recall` / `getStates` / `addMemory` / `searchMemories` … |
| [`SqliteLedgerRuntime`](src/main/kotlin/relay/memory/engine/SqliteLedgerRuntime.kt) | Room + bundled SQLite 实现 |
| [`ensureSpace`](src/main/kotlin/relay/memory/Spaces.kt) | 注册 space + 时钟域 |
| [`recalling`](src/main/kotlin/relay/memory/agent/Remembering.kt) | 做成 agent-core 的 `ContextAugmenter` |
| `HashedEmbedder` | 无云时的占位向量（换模型会让旧向量失效） |

```kotlin
val memory: MemoryRuntime = SqliteLedgerRuntime(context, file)
memory.ensureSpace("assistant")
agent 里注入 memory.recalling(spaceId = "...", ownerId = "...")
```

抽取器只出 Proposal，不能自己 `commit`。字段别名、`USER_LOCK`、小说时钟等规则见 [docs/api.md](docs/api.md)。

## 能力边界

- **做**：端上私有账本、状态/情节/反思、词法+向量召回、给 Agent 垫上下文。
- **不做**：自己跑 LLM 抽取、自动 Reflection 写入、合规脱敏。
- **不做**内置 `profile.*` 字段；助手/小说的种子字段由宿主注册。
- 图查询、跨设备同步不是当前主线。
- `putEmbedding.modelId` 变了，旧向量等于孤儿。

## 依赖了什么

- **Relay**：[`agent-core`](../agent-core/README.md)（`ContextAugmenter`）；[`llm`](../llm/README.md)（`Message`、`TextEmbedder`）。
- **Android**：Room 3、`androidx.sqlite:sqlite-bundled`。
- 被谁用：`samples/assistant` 作跨会话事实；playground 的 memory 屏。
