# ContextComposer：Agent 请求上下文构建方案

> 状态：提案。范围仅为 `relay:agent-core` 的单 Agent 请求上下文；不引入持久记忆、向量检索或多 Agent 语义。

## 1. 背景与目标

当前 `Agent` 在一次调用 Provider 前自行串联多项上下文处理：

- `transformContext` 投影 transcript；
- `ContextAugmenter` 插入临时消息；
- `WindowTrim` 处理超窗；
- `withSystem` 加 system prompt；
- `reservedTokens` 为 system prompt、工具 schema 和追加内容预留 token。

这些能力都在回答同一个问题：**本轮请求应让模型看到什么？**

提案把这个问题收敛到 `ContextComposer`。`Agent` 仍持有完整、原始的 `AgentState.messages`，只授权 Composer 以只读方式构造本轮的临时请求视图。

```text
AgentState.messages                         唯一真源，不被 Composer 改写
         │
         ▼
ContextComposer.compose(input)              临时加工
         │
         ▼
ContextView.messages                         仅用于本次 ChatRequest
```

### 目标

1. 将上下文处理从 Agent loop 中移出，令 `Agent` 专注消息追加、LLM 调用、工具调度和事件。
2. 保持当前默认语义：超窗时采用 `WindowTrim`，不额外调用模型。
3. 允许宿主选择裁剪、自动摘要或自定义缩减策略。
4. 让 system prompt、tools、追加内容与输出预留使用同一 token 预算。
5. 在单测中直接验证“给定 transcript，本轮请求实际发送什么”。

### 非目标

- 不把 transcript 变成持久记忆或跨会话状态；
- 不加入 embedding、RAG 数据库、用户画像或 TeamLedger；
- 不让 Composer 执行工具、改变 Agent 控制流或写入业务数据；
- 不允许缩减策略留下不合法的 tool-call / tool-result 序列。

## 2. 总体结构

Composer 使用**固定流水线**，而不是允许任意 middleware 改写任何阶段。固定顺序是协议正确性和 token 可解释性的前提。

```text
原始 transcript
    │
    ├─ 1. Projection       选择 / 改写正常历史的请求视图
    ├─ 2. Augmentation     生成临时追加上下文
    ├─ 3. Budgeting        计算可用于 transcript 的 token 预算
    ├─ 4. Reduction        在预算内保留、裁剪或摘要历史
    ├─ 5. Assembly         system + additions + reduced transcript
    └─ 6. Validation       校验 token 与消息协议不变量
                              │
                              ▼
                         ContextView
```

`Agent` 在每轮调用 LLM 前调用 Composer；模型返回的 assistant message 与工具结果仍只由 `Agent.append()` 写入原始 transcript。

## 3. 公共 API 草案

```kotlin
fun interface ContextComposer {
    suspend fun compose(input: ContextInput): ContextView
}

data class ContextInput(
    val transcript: List<Message>,
    val systemPrompt: String,
    val tools: List<ToolDef>,
    val model: String,
    val modelInfo: ModelInfo?,
    val maxOutputTokens: Int?,
)

data class ContextView(
    val messages: List<Message>,
    val usage: ContextTokenUsage,
    val decisions: List<ContextDecision> = emptyList(),
)

data class ContextTokenUsage(
    val contextWindow: Int,
    val outputReserved: Int,
    val systemTokens: Int,
    val toolTokens: Int,
    val additionTokens: Int,
    val transcriptBudget: Int,
    val requestTokens: Int,
)
```

`ContextView` 是值对象。它不暴露对 `AgentState.messages` 的可变引用，也不承担下一轮状态。

## 4. 阶段接口与设计模式

### 4.1 `DefaultContextComposer`：Template Method + Facade

`DefaultContextComposer` 固定 `compose()` 的六步顺序，对 Agent 暴露一个单一入口。它同时是对原有分散能力的 Facade。

```kotlin
class DefaultContextComposer(
    private val projector: ContextProjector = IdentityProjector,
    private val augmenters: List<ContextAugmenter> = emptyList(),
    private val reducer: ContextReducer = WindowTrimReducer(),
    private val validator: ContextValidator = DefaultContextValidator(),
    private val tokenCounter: TokenCounter = HeuristicTokenCounter(),
) : ContextComposer
```

这里使用 Template Method 的思想，但不需要开放继承：`compose()` 由类固定，变化点通过组合对象注入。这样不会出现“追加内容在裁剪之后才算 token”之类的顺序漂移。

### 4.2 `ContextProjector`：Strategy

```kotlin
fun interface ContextProjector {
    suspend fun project(transcript: List<Message>): List<Message>
}
```

它对应现有 `transformContext`。职责仅是将正常 transcript 投影为请求视图，例如过滤宿主自定义消息；不负责检索、摘要、token 预算或持久化。

默认实现为恒等投影。迁移期用 lambda adapter 包装现有 `transformContext`。

### 4.3 `ContextAugmenter`：Composite

保留已有接口。Composer 顺序调用多个 augmenter，拼接其临时 `messages`。

```kotlin
fun interface ContextAugmenter {
    suspend fun augment(messages: List<Message>): ContextAugmentation
}
```

这是 Composite 模式：调用方可组合记忆召回、当前环境、业务 ledger 等多个来源，但这些消息不写回 transcript。Composer 统一统计它们的 token，避免某个 augmenter 挤占输出空间。

### 4.4 `ContextReducer`：Strategy

宿主决定长上下文策略；core 不默认绑定摘要。

```kotlin
fun interface ContextReducer {
    suspend fun reduce(input: ContextReductionInput): ContextReduction
}

data class ContextReductionInput(
    val transcript: List<Message>,
    val transcriptBudgetTokens: Int,
    val model: String,
    val tokenCounter: TokenCounter,
)

data class ContextReduction(
    val messages: List<Message>,
    val decisions: List<ContextDecision> = emptyList(),
)
```

内置实现：

- `WindowTrimReducer`：包装现有 `WindowTrim`；默认策略，零额外 LLM 调用。
- `SummaryReducer`：宿主注入 `Summarizer`，将较早消息压缩为 handoff，保留近期原文。
- 自定义 reducer：业务可定义固定消息 pin、只摘要大 tool result 等策略。

`SummaryReducer` 的实例必须是单个 Agent 独占，或自行保证状态隔离和并发安全。它不共享两个 Agent 的摘要 checkpoint。

### 4.5 `ContextValidator`：Specification

```kotlin
fun interface ContextValidator {
    fun validate(view: ContextView): ContextView
}
```

它集中表达并验证不变量：

- 最终请求不超 token 预算；
- `SYSTEM` 只由 `systemPrompt` 注入一次；
- assistant tool call 不与其连续的 tool result 分离；
- 最终消息 role 符合 Provider 协议；
- reducer 返回的结构无效或仍超窗时，回退到 `WindowTrimReducer`，或抛出清晰错误。

## 5. Token 预算

预算必须在缩减历史前一次性计算：

```text
transcriptBudget
  = contextWindow
  - reservedOutputTokens
  - systemPromptTokens
  - toolSchemaTokens
  - augmentationTokens
```

其中：

- `reservedOutputTokens`：优先 `AgentConfig.maxTokens`，否则模型声明的最大输出；
- `systemPromptTokens`：非空 `state.systemPrompt`；
- `toolSchemaTokens`：所有 `ToolDef`；
- `augmentationTokens`：所有 augmenter 本轮返回消息。

这沿用现有 `WindowTrim` 的预算含义，只是把计算放入 Composer，供所有 reducer 共用。

## 6. 自动摘要策略

`SummaryReducer` 仅在宿主安装时启用，建议参数：

```kotlin
data class SummaryReducerConfig(
    val triggerRatio: Double = 0.85,
    val keepRecentTokens: Int = 20_000,
    val maxSummaryTokens: Int = 3_000,
)
```

执行原则：

1. 历史未超过 `transcriptBudgetTokens * triggerRatio` 时原样返回；
2. 从尾部向前保留 `keepRecentTokens`；
3. 切点只能在完整 turn 边界，不能截开 assistant 的 tool call 及连续 tool result；
4. 将早期历史与上次 handoff 交给宿主 `Summarizer`；
5. 返回 `handoff + 最近原文` 的临时视图；
6. 摘要失败、超时、内容为空或仍超预算时，使用 `WindowTrimReducer` 兜底。

摘要的最低内容契约：目标与约束、已确认事实、已完成动作、未完成事项、重要文件或资源引用。大原文应存为 artifact 或由业务层管理，不能指望摘要保存精确内容。

## 7. 事件与可观测性

`ContextView.decisions` 记录上下文构建决定，不记录敏感正文。例如：

```kotlin
sealed interface ContextDecision {
    data class Added(val source: String, val tokens: Int) : ContextDecision
    data class Trimmed(val removedMessages: Int, val removedTokens: Int) : ContextDecision
    data class Summarized(val sourceMessages: Int, val summaryTokens: Int) : ContextDecision
    data class Fallback(val reason: String) : ContextDecision
}
```

Agent 可选择将它映射为 `AgentEvent.ContextComposed`，供 UI、日志与评估使用。不要把该事件与 `AgentState.messages` 混在一起。

## 8. 与当前 API 的兼容迁移

第一阶段不移除任何构造参数：

```kotlin
class Agent(
    // existing arguments ...
    transformContext: (suspend (List<Message>) -> List<Message>)? = null,
    contextAugmenters: List<ContextAugmenter> = emptyList(),
    contextComposer: ContextComposer? = null,
)
```

规则：

1. 若传入 `contextComposer`，优先使用它；
2. 否则构造 `DefaultContextComposer`：projector 为 `transformContext` adapter、augmenters 为既有列表、reducer 为 `WindowTrimReducer`；
3. 现有行为与测试应保持不变；
4. 下一大版本才 deprecate `transformContext`，引导调用方改传 `ContextProjector` 或完整 Composer。

不允许同时传非默认 `contextComposer` 与 `transformContext` / `contextAugmenters`，以免调用顺序不明确；构造时直接 `require`。

## 9. 测试计划

1. **Default parity**：未安装 Composer 时，现有 `AgentTest` 与 `WindowTrimTest` 结果不变。
2. **预算**：system、tools、augmentations、输出预留都会减少 transcript budget。
3. **投影/追加顺序**：augmenter 读取原始 transcript；reducer 读取 projected transcript；最终 additions 位于 transcript 前。
4. **工具完整性**：裁剪或摘要切点不分开 tool call 与 tool result。
5. **自定义 reducer**：宿主 reducer 可替换默认裁剪，并能在 `ContextView` 看到 decisions。
6. **摘要 fallback**：摘要器抛错、超时、空结果、返回超预算时稳定回退到 WindowTrim。
7. **并发**：同一 Agent 已有 `isRunning` 保护；额外验证 SummaryReducer 不可被多个 Agent 共用状态。

## 10. 后续但不纳入 v1

- `ContextSanitizer`：用于每个外部模型调用前的脱敏；同时需要独立的 transcript / log 存储策略，不能仅靠 Composer。
- `ContextPriority` / pin：按优先级淘汰 RAG 与临时内容。
- Provider prompt caching hint。
- 将 `ContextView` 和组成数据用于离线评估：上下文长度、摘要损失与任务成功率。

## 11. 结论

以 `ContextComposer` 作为单一请求视图入口，以固定流水线保障顺序，以 Strategy 允许宿主挑选缩减方式，以 immutable `ContextView` 保持原始 transcript 不变。这样可先无行为变化地收口现有实现，再按需加入自动摘要，而不把记忆、RAG 或多 Agent 语义拖进 `agent-core`。
