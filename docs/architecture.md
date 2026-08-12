# Relay · 端云协同 Agent 运行时架构设计

> 状态:架构收敛中(v0.1,先落盘)
> 定位:参考 [earendil-works/pi](https://github.com/earendil-works/pi) 的分层理念,做一套**面向端云协同的安卓 Agent 运行时库**。项目代号 **Relay**(端↔云接力/协同)。

---

## 1. 项目背景与目标

- **定位**:端侧 AI 基础建设——为安卓应用提供一套端云协同的 Agent 运行时基础设施。
- **核心命题**:端侧轻推理 + 云端大模型协同。端上跑轻量模型(路由决策 / 语义缓存 / 脱敏 / 简单问答 / 离线兜底),复杂任务交云端。
- **实现语言**:库用 **Kotlin** 编写(UI 层必须 Kotlin/Compose);对外保证 **Java 友好**(额外提供同步/回调包装),使用方可继续用 Java。
- **端侧 runtime 选型倾向**:优先高层 API(Gemini Nano / AICore、ML Kit、LiteRT via Play Services、MediaPipe Tasks / LiteRT-LM),**尽量不碰 C++**;llama.cpp(JNI)作为可选的加分项。

---

## 2. 设计原则

1. **机制与策略分离(mechanism vs policy)**:框架提供能力(机制),端云路由等策略交给业务编排层决定。
2. **端口与适配器(hexagonal)**:`relay-llm` 定义抽象端口 `Provider`;云、端都是它的适配器。
3. **`Provider` 保持纯粹**:`Provider` 只代表"一个模型后端",不承载横切行为。
4. **横切关注点用拦截器**:缓存 / 重试 / 限流 / 埋点 / 日志走**拦截器链**(参照 OkHttp),不污染 `Provider` 语义。
5. **端云路由归业务层**:框架不强加路由;顶多提供可选的组合工具。
6. **上层无感**:`relay-agent-core` 只依赖 `Provider` 一个类型,不关心底下是端、是云、还是套了缓存/路由/兜底。

---

## 3. 整体分层架构

```
┌───────────────────────────────────────────────┐
│  业务编排层  (App / demo,框架的使用者)           │
│  · 自己选 provider(云 / 端 / 组合)              │
│  · 自己决定端云路由策略                          │
│  · 用 core 的 API 组装所需 agent                │
│  · 用 ui-kit 渲染                               │
└───────┬───────────────────────┬───────────────┘
        │ 用 API 构建 agent       │ 渲染
        ▼                        ▼
┌────────────────┐      ┌─────────────────┐
│ relay-agent-core   │      │  relay-ui-kit       │
│ agent runtime   │      │  Compose UI      │
│ loop/tool/记忆  │      └─────────────────┘
│ —— 被注入 provider,不关心端/云                 │
└───────┬────────┘
        │ 依赖抽象
        ▼
┌─────────────────────────────────────────────┐
│  relay-llm   模型调用(抽象 + 实现 + 拦截器)         │
│  interface Provider { info/chat/stream }       │
│  ├── 云 providers (OpenAI / Anthropic …)       │
│  ├── 拦截器链 (Caching/Retry/RateLimit/Metrics)│
│  └── 可选组合工具 (RoutingProvider …)          │
└──────────────────▲──────────────────────────┘
                   │ 实现 Provider 接口
        ┌──────────┴──────────┐
        │ relay-ondevice          │
        │ 端侧部署 / 运行时      │
        │ 模型下载 / 能力探测 / 加载 │
        │ LiteRT / Nano 封装    │
        └─────────────────────┘
```

**模块依赖方向**(全部指向稳定抽象 `relay-llm`):

- `relay-ondevice` → `relay-llm`
- `relay-agent-core` → `relay-llm`
- `relay-ui-kit` → `relay-agent-core`
- 业务编排层 → `relay-agent-core` / `relay-ui-kit` / `relay-llm` / `relay-ondevice`

---

## 4. 各层职责

### 4.1 `relay-llm` —— 模型调用层
- 定义 `Provider` 抽象(端口)与请求/响应数据模型。
- 内置云 provider 实现(OpenAI / Anthropic / …)。
- 提供拦截器链机制与内置拦截器。
- 提供可选组合工具(如 `RoutingProvider`、`FallbackProvider`)——但路由**策略**由业务层给。
- **不感知端/云。**

### 4.2 `relay-ondevice` —— 端侧部署 / 运行时层
- 把端侧模型封装成符合 `relay-llm` 接口的 `Provider`。
- **runtime**:**llama.cpp**(arm64 CPU)经 **JNI/NDK** 集成;模型 **Qwen2.5 0.5B/1.5B(Q4 GGUF)**。无 GMS,故不用 Gemini Nano/ML Kit/LiteRT-Play-Services。
- 负责:模型下载、存储、版本管理、模型加载与生命周期、native(llama.cpp)桥接。
- 提供端侧轻推理能力:简单问答、(后续)embedding、分类(供路由信号)。
- 能力探测 + 降级链:**当前先不做**(见决策记录),先假定目标机可用。

> **为何 NPU 不做**:llama.cpp 无 Kirin NPU 后端(其 NPU 后端仅高通 Hexagon / 昇腾数据中心 CANN);吃 Kirin 9020 NPU 须换 MindSpore Lite + HiAI(`.ms` 格式,非 GGUF,且 LLM 支持存疑)。端云协同下端侧只做轻推理,小模型 CPU 已够,故 NPU 暂不纳入。

### 4.3 `relay-agent-core` —— Agent 运行时层
- 提供构建/运行 agent 的能力(API):agent loop、tool calling、记忆、状态管理。
- 被注入一个 `Provider` 即可运行,**不 care 端/云/路由**。

### 4.4 `relay-ui-kit` —— UI 组件层(Compose)
- 聊天 / agent 交互组件:消息流、流式打字、Markdown/代码渲染、工具调用卡片。
- 展示端云协同特有信息:**来源标识(端/云)、延迟、成本、离线状态**。

### 4.5 业务编排层 —— 框架使用者
- 选 provider、定端云路由策略、用 core 的 API 拼出所需 agent、用 ui-kit 渲染。

---

## 5. 核心抽象:Provider API

```kotlin
interface Provider {
    val info: ProviderInfo                                  // 自我描述:是谁、支持啥
    suspend fun chat(request: ChatRequest): ChatResponse    // 一元调用
    fun stream(request: ChatRequest): Flow<ChatChunk>       // 流式调用
}

data class ProviderInfo(
    val id: String,                    // "openai" / "ondevice-gemma"
    val models: List<ModelInfo>,       // 每个模型自带元信息
)

data class ModelInfo(
    val id: String,                    // "gpt-4o" / "gemma-3-1b"
    val contextWindow: Int,            // 上下文窗口(tokens)—— 历史裁剪/摘要、路由判断都要用
    val maxOutputTokens: Int? = null,  // 最大输出 tokens(可空)
    val capabilities: Set<Capability>, // 能力下沉到模型级(同家不同模型能力不同)
)

enum class Capability { STREAMING, TOOLS, VISION, JSON_SCHEMA, EMBEDDING }

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<ToolDef> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val extra: Map<String, Any?> = emptyMap(),  // 逃生舱:某家特有参数
)

data class Message(
    val role: Role,                    // SYSTEM / USER / ASSISTANT / TOOL
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
)

data class ChatResponse(
    val message: Message,
    val usage: Usage?,
    val finishReason: FinishReason,
)

sealed interface ChatChunk {           // 流式增量
    data class Text(val delta: String) : ChatChunk
    data class ToolCall(val delta: ToolCallDelta) : ChatChunk
    data class Done(val usage: Usage?, val finishReason: FinishReason) : ChatChunk
}
```

### 端/云同构验证

云 provider(HTTP/SSE)与端 provider(本地引擎)实现同一个 `Provider` 接口,对上层完全一致:

```kotlin
// 云:在 relay-llm
class OpenAiProvider(apiKey: String, http: HttpClient) : Provider {
    override val info = ProviderInfo("openai", listOf(
        ModelInfo("gpt-4o", contextWindow = 128_000, maxOutputTokens = 16_384,
            capabilities = setOf(Capability.STREAMING, Capability.TOOLS, Capability.VISION)),
    ))
    override suspend fun chat(request: ChatRequest): ChatResponse { /* REST */ }
    override fun stream(request: ChatRequest): Flow<ChatChunk> { /* SSE */ }
}

// 端:在 relay-ondevice
class OnDeviceProvider(engine: LiteRtLmEngine) : Provider {
    override val info = ProviderInfo("ondevice-gemma", listOf(
        ModelInfo("gemma-3-1b", contextWindow = 8_192, maxOutputTokens = 2_048,
            capabilities = setOf(Capability.STREAMING)),   // 端侧小模型:窗口小、不支持 TOOLS
    ))
    override suspend fun chat(request: ChatRequest): ChatResponse { /* 本地推理 */ }
    override fun stream(request: ChatRequest): Flow<ChatChunk> { /* callbackFlow 逐 token */ }
}
```

### `ModelInfo.contextWindow` 的用途

- **`relay-agent-core`**:对话历史 + 工具结果超过窗口时,据此触发**裁剪 / 摘要**。
- **端云路由(业务层)**:输入预估 token 超过端侧模型窗口 → 直接转云;否则可留在端上。
- **拦截器**:`MetricsInterceptor` 可结合窗口统计"上下文占用率"。

### token 计数策略(#8 调研结论)

**核心事实**:token 计数是 **provider 专属**的,没有通用 tokenizer;同一段文本在不同家/不同模型版本上计数可差 10~35%(Anthropic 新老 tokenizer 就差约 35%)。**不能用一家的 tokenizer 去估另一家。**

**采用分层策略(pre-flight 估算 + 事后校准)**:

- **事后真值**:每次响应读取 `ChatResponse.usage`(provider 返回的真实 token),作为**账单级真值**——用于成本统计、上下文占用回填。
- **pre-flight 估算**(发送前,用于历史裁剪 / 路由 / 预算门):
  - **默认**:轻量启发式(英文约 4 字符/token,中文约 1.5~2 字符/token),零依赖,够做预算门。
  - **可选精确**:可插拔 `TokenCounter`——OpenAI 系用 tiktoken 端口;Anthropic/Gemini 有官方 `count_tokens` 端点(需联网,留给高价值路径如大请求准入)。
  - **端侧模型**:自带 tokenizer(`.gguf`/`.task`),可本地精确计数。
- **设计**:`TokenCounter` 抽象,按 provider/model 选实现;默认启发式,精确实现按需注入。

---

## 6. 横切关注点:拦截器

```kotlin
interface Interceptor {
    suspend fun intercept(request: ChatRequest, chain: Chain): ChatResponse
    fun interceptStream(request: ChatRequest, chain: StreamChain): Flow<ChatChunk> =
        chain.proceed(request)   // 默认透传
}

// 给 provider 套拦截器链,返回的仍是 Provider
fun Provider.intercept(vararg interceptors: Interceptor): Provider =
    InterceptedProvider(this, interceptors.toList())
```

内置拦截器(候选):

| 拦截器 | 作用 |
|---|---|
| `CachingInterceptor` | 精确 / 语义缓存,命中不调模型 |
| `RetryInterceptor` | 失败重试 + 退避 |
| `RateLimitInterceptor` | 限流 / 并发控制 |
| `MetricsInterceptor` | 埋点:token、延迟、provider 占比、成本 |
| `LoggingInterceptor` | 日志 |

**待定**:拦截器接口需同时优雅覆盖一元与流式(Flow)两种路径,`interceptStream` 的 `Chain` 抽象待细化。

---

## 7. 端云路由(业务层)

- 路由是**业务策略**,不在 `relay-llm` 里强制。
- `relay-llm` 提供可选的 `RoutingProvider`(组合工具),策略由业务传入:

```kotlin
val provider = RoutingProvider(onDevice, cloud) { req ->
    if (req.isSimple || req.hasPII) onDevice else cloud
}.intercept(CachingInterceptor(), MetricsInterceptor())

val agent = Agent(provider = provider, tools = myTools)   // 交给 core
```

- 路由所需信号(复杂度分类、语义缓存 embedding、脱敏检测)由 `relay-ondevice` 的端侧轻推理能力提供。

---

## 8. Java 互操作

`suspend` / `Flow` 对 Java 不友好,`relay-llm` 额外提供同步/回调包装(不改核心):

```kotlin
class BlockingProvider(private val delegate: Provider) {
    fun chat(request: ChatRequest): ChatResponse = runBlocking { delegate.chat(request) }
    fun stream(request: ChatRequest, onChunk: Consumer<ChatChunk>) { /* 回调式 */ }
}
```

---

## 9. 技术栈选型(倾向,待 Plan 阶段最终敲定)

| 关注点 | 倾向选型 |
|---|---|
| 语言 | Kotlin(库)+ Java 友好包装 |
| 异步 / 流式 | Coroutines + Flow |
| 网络 | Ktor Client(或 OkHttp)|
| 序列化 | kotlinx.serialization |
| UI | Jetpack Compose |
| 端侧 runtime | **llama.cpp**(arm64 **CPU**,经 JNI/NDK),模型 **Qwen2.5 0.5B/1.5B · Q4 GGUF**。无 GMS,故不用 Gemini Nano/ML Kit/LiteRT。**NPU 不做**(见下注)。 |
| 构建 | Gradle 多 module |
| minSdk | 28 |
| 目标机型 | 华为 Mate 70 Pro(Kirin 9020,NPU)⚠️ 见 OS 抉择 |
| 发布 | Maven / AAR |

---

## 10. 决策记录 & 待定问题

**已定:**
1. ✅ `Provider` **暂不支持多模态输入**(图片/音频),后续再议。
2. ✅ **embedding 暂不支持**,后续支持(届时倾向单独拆 `EmbeddingProvider`)。
5. ✅ `relay-ondevice` **先不做能力探测**(降级链后置)。
6. ✅ minSdk **28**;目标机型 **华为 Mate 70 Pro**。⚠️ 但触发下方"OS 抉择"阻塞项。
7. ✅ 发布方式:**Maven / AAR**。
8. ✅ **token 计数策略**已定(见第 5 节:pre-flight 启发式估算 + 事后 `usage` 真值 + 可插拔 `TokenCounter`)。

**已定(OS 抉择,原阻塞项):**
- ✅ **方案 A —— 目标 HarmonyOS 4.x 双框架(Android 兼容)**:保留 Kotlin/Compose/Gradle/AAR;因无 GMS,端侧走 llama.cpp/MNN/ONNX(C++/JNI)或华为 HiAI。**已接受 C++/JNI。**
- ⚠️ **残留风险**:华为在逐步用 HarmonyOS NEXT 淘汰 Android 兼容框架,此路线有时效性;若将来目标机升级到纯血鸿蒙,Android 方案会失效(需评估 ArkTS 迁移)。

**已定(端侧选型):**
9. ✅ 端侧 runtime = **llama.cpp(arm64 CPU,JNI)**;模型 = **Qwen2.5 0.5B/1.5B(Q4 GGUF)**;**NPU 不做**(理由见 4.2)。

**仍待定:**
3. **取消 / 超时**如何在接口层传递(CoroutineContext?显式参数?)。
4. 拦截器**流式路径**的 `Chain` 抽象具体形态。

---

## 11. 模块清单

| Module | 说明 | 语言 |
|---|---|---|
| `relay-llm` | 模型调用抽象 + 云实现 + 拦截器 + 可选组合工具 | Kotlin |
| `relay-ondevice` | 端侧部署 / 运行时,提供端侧 Provider | Kotlin(可选 JNI)|
| `relay-agent-core` | Agent 运行时(loop / tool / 记忆 / 状态)| Kotlin |
| `relay-ui-kit` | Compose UI 组件 | Kotlin |
| 业务编排层 | 使用者(App / demo)| Kotlin / Java |
