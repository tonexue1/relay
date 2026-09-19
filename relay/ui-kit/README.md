# `:relay:ui-kit`

模型出 JSON spec，端上 Compose 原生画进对话。不要把 HTML 塞进 WebView 当主路径。

Maven：`io.github.tonexue1:relay-ui-kit:0.1.0`（AAR，minSdk 28，Compose）

设计笔记：[docs/ui-kit.md](../../docs/ui-kit.md)

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`WidgetSpec`](src/main/kotlin/relay/uikit/WidgetSpec.kt) | 可序列化控件：markdown / kv / table / card / choice_form / chart / graph / list / image / file |
| [`WidgetParser`](src/main/kotlin/relay/uikit/WidgetParser.kt) | JSON → spec；坏数据变成 Fallback，不抛给聊天 |
| [`WidgetHost`](src/main/kotlin/relay/uikit/WidgetHost.kt) | `@Composable` 渲染 |
| [`uiArtifactTools`](src/main/kotlin/relay/uikit/UiArtifactTools.kt) | 给 Agent 的 `render_*` / `write_*` / `read_artifact` / `revise_artifact` |
| `OrderedTurnReducer` | 把流式 `AgentEvent` 收成可画的轮次 |
| `MarkdownRenderer` / `ChoiceFormWidget` / `GraphWidget` / `HtmlArtifactPreview` | 具体控件 |

`UiToolNames.renderers` vs `writers`：前者只出 spec 事件（`ToolOutput.eventData` 不进模型 transcript），后者写入 [`ArtifactRepository`](../artifacts/README.md)。

选择表：`onChoiceFormSubmit` 把选项交回宿主，通常再 `Agent.continueRun()`。

## 能力边界

- **做**：受控 spec、原生渲染、可测、与模型无关。
- **不做**：任意 HTML 当一等公民（HTML 预览是 artifact 旁路）。
- **不做**聊天列表/会话存储；宿主自己排消息。
- 未知 `type` / 错误 version → Fallback 卡片，而不是崩溃。
- Chart 走 Vico；能力是常见柱/线，不是完整 BI。

## 依赖了什么

- **Relay**：[`agent-core`](../agent-core/README.md)（`Tool` / `FunTool`）；[`artifacts`](../artifacts/README.md)（仓库与校验）。
- **Android**：Compose Material3、WebKit（HTML 预览）、CommonMark、Vico。
- 被谁用：`samples/assistant`、playground。
