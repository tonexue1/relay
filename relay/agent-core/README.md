# `:relay:agent-core`

单个 Agent 的循环：调 `Provider`、派工具、裁上下文。不管底下是云还是端。

Maven：`io.github.tonexue1:relay-agent-core:0.1.0`（jar）

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`Agent`](src/main/kotlin/relay/agent/Agent.kt) | `prompt` / `continueRun` → `Flow<AgentEvent>` |
| [`AgentConfig`](src/main/kotlin/relay/agent/AgentConfig.kt) | 模型、system、工具批次数、并行/串行 |
| [`Tool`](src/main/kotlin/relay/agent/Tool.kt) / `FunTool` | 工具合同；失败用抛异常，不要把错误写进返回字符串 |
| [`AgentEvent`](src/main/kotlin/relay/agent/AgentEvent.kt) | 生命周期（对齐 pi-agent-core） |
| [`AgentState`](src/main/kotlin/relay/agent/AgentState.kt) | 工作记忆：`messages` |
| [`ContextComposer`](src/main/kotlin/relay/agent/ContextComposer.kt) | 每次请求临时拼上下文；默认滑窗裁剪 |
| [`ContextAugmenter`](src/main/kotlin/relay/agent/ContextAugmenter.kt) | 往请求里加料（记忆垫片），不改 transcript |
| [`BeforeToolCallResult`](src/main/kotlin/relay/agent/BeforeToolCallResult.kt) | 执行前拦截/阻断 |

`Agent` **不是** `Provider`。再包一层会套娃 loop。

工具超过 `maxToolBatches`：不执行，回 `TOOL_BUDGET_EXHAUSTED`，下一轮强迫模型写正文。

`continueRun()` 要求最后一条是 `user` 或 `tool`（恢复选择表单等）。

## 能力边界

- **做**：单 agent、工具、窗口裁剪、可注入的上下文、工具预算。
- **不做**：多 agent 拓扑（见 [orchestra](../orchestra/README.md)）、持久记忆（见 [memory](../memory/README.md)）、渲染（见 [ui-kit](../ui-kit/README.md)）。
- `ContextComposer` 与旧的 `transformContext` / `contextAugmenters` 互斥。
- token 数是启发式，不是各家官方 tokenizer。

## 依赖了什么

- **Relay**：[`llm`](../llm/README.md) — `Provider`、`Message`、`ChatChunk`、`ToolDef`、`TokenCounter`。
- 被谁用：[memory](../memory/README.md) 的 `recalling()` 做成 `ContextAugmenter`；[orchestra](../orchestra/README.md) spawn `Agent`；[ui-kit](../ui-kit/README.md) 把渲染包装成 `Tool`。
