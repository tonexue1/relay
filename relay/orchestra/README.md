# `:relay:orchestra`

多 Agent 拓扑。公开名字是 Pipeline / Supervisor / GroupChat / Director；Call / Yield 是内部原语。

Maven：`io.github.tonexue1:relay-orchestra:0.1.0`（jar）

拓扑对照：[docs/sample-ideas.md](../../docs/sample-ideas.md)

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`Pipeline`](src/main/kotlin/relay/orchestra/Pipeline.kt) | 写死顺序，无 lead LLM |
| [`Supervisor`](src/main/kotlin/relay/orchestra/Supervisor.kt) | lead 的 tool = 一次性工人 |
| [`GroupChat`](src/main/kotlin/relay/orchestra/GroupChat.kt) | 圆桌 Yield；默认 round-robin |
| [`Director`](src/main/kotlin/relay/orchestra/Director.kt) | 由 `DirectorPolicy` 点下一位或旁白 |
| [`WorkerSpec`](src/main/kotlin/relay/orchestra/WorkerSpec.kt) | 如何 spawn 一个工人 `Agent` |
| [`TeamEvent`](src/main/kotlin/relay/orchestra/TeamEvent.kt) | 团队级事件（包一层 `AgentEvent`） |
| [`TeamLedger`](src/main/kotlin/relay/orchestra/TeamLedger.kt) | 一次 run 的交接账 |
| `ArtifactStore` / `InMemoryArtifactStore` | **本模块内**工人产物（`artifact://run/name`），不是 [`artifacts`](../artifacts/README.md) 那套版本库 |

入口都是 `prompt(...): Flow<TeamEvent>`。工人并行复用 agent-core 的 `ToolExecutionMode.Parallel`，orchestra 自己不开线程池。

## 能力边界

- **做**：具名拓扑、把工人结果收成事件带。
- **不做**：v1 公开面里的 Network / MagenticOne / Mailbox。
- **不做**公共工具市场；sample 里的 echo/notes 只是道具。
- Call / `AgentTool` / `Stage` 不要当 App 菜单项。
- 这里的 `ArtifactStore` 和 `relay.artifacts.ArtifactRepository` 不是同一个东西：前者是一次编排的便签，后者是可版本、可反馈的文件生成物。

## 依赖了什么

- **Relay**：[`agent-core`](../agent-core/README.md)（`Agent`、`Tool`、`AgentEvent`）。传递依赖 [`llm`](../llm/README.md)。
- **不依赖** memory / ui-kit / ondevice。
- 被谁用：`samples/werewolf`、novelist、playground 的 agent 屏。
