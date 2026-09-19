# `:relay:llm`

模型调用层。只表示「一个后端怎么聊」，不决定端云路由。

Maven：`io.github.tonexue1:relay-llm:0.1.0`（jar）

## 提供什么 API

| 类型 | 作用 |
|---|---|
| [`Provider`](src/main/kotlin/relay/llm/Provider.kt) | `chat` / `stream` 端口 |
| [`ChatRequest`](src/main/kotlin/relay/llm/model/ChatRequest.kt) / `Message` / `ToolDef` / `ChatChunk` | 与后端无关的请求响应 |
| [`OpenAiCompatibleProvider`](src/main/kotlin/relay/llm/provider/OpenAiCompatibleProvider.kt) | OpenAI `/chat/completions` 方言（DeepSeek、vLLM、Ollama 等） |
| [`DeepSeek`](src/main/kotlin/relay/llm/provider/DeepSeek.kt) | DeepSeek 预设（模型窗口、能力表） |
| [`Interceptor`](src/main/kotlin/relay/llm/interceptor/Interceptor.kt) + `Provider.intercept(...)` | 横切：重试、日志、指标 |
| [`FallbackProvider`](src/main/kotlin/relay/llm/provider/FallbackProvider.kt) | 主失败再走备，只表达可用性 |
| [`TextEmbedder`](src/main/kotlin/relay/llm/embed/TextEmbedder.kt) / `OpenAiCompatibleEmbedder` | `/embeddings` |
| [`TokenCounter`](src/main/kotlin/relay/llm/token/TokenCounter.kt) | 启发式 token 估计 |
| [`ToolCallAccumulator`](src/main/kotlin/relay/llm/tool/ToolCallAccumulator.kt) | 把流式 `ToolCallDelta` 拼成完整调用 |

```kotlin
interface Provider {
    val info: ProviderInfo
    suspend fun chat(request: ChatRequest): ChatResponse
    fun stream(request: ChatRequest): Flow<ChatChunk>
}
```

## 能力边界

- **做**：统一端口、一种云实现、拦截器、失败回退、embedding 端口。
- **不做**：端云路由策略、Agent loop、记忆、UI。`ChatRequest.extra` 会牺牲跨后端可移植性。
- **不探测模型**：`ModelInfo` 由调用方声明，构造时不上网。
- **取消**：取消 `chat` 的协程、停掉 `stream` 的收集即中止请求。

## 依赖了什么

- 无其它 `relay-*` 模块。
- 运行时：OkHttp、Retrofit、kotlinx-serialization / coroutines。
- 被谁用：[`ondevice`](../ondevice/README.md) 实现 `Provider`；[`agent-core`](../agent-core/README.md) 在 loop 里调用它；[`memory`](../memory/README.md) 用 `TextEmbedder` / `Message`。
