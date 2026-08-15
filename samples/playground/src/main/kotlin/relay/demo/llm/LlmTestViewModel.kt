package relay.demo.llm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import relay.demo.BuildConfig
import relay.llm.Provider
import relay.llm.RelayLlmException
import relay.llm.interceptor.CallMetrics
import relay.llm.interceptor.LoggingInterceptor
import relay.llm.interceptor.MetricsInterceptor
import relay.llm.interceptor.RetryInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.llm.model.ToolCall
import relay.llm.provider.DeepSeek
import relay.llm.provider.OpenAiCompatibleProvider
import relay.llm.token.HeuristicTokenCounter
import relay.llm.tool.ToolCallAccumulator

data class LlmTestUiState(
    val baseUrl: String = DeepSeek.BASE_URL,
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val model: String = DeepSeek.CHAT,
    val prompt: String = "用一句话解释什么是端云协同。",
    val streaming: Boolean = true,
    val running: Boolean = false,
    val output: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val error: String? = null,
    val metrics: CallMetrics? = null,
    val estimatedPromptTokens: Int = 0,
    val logs: List<String> = emptyList(),
) {
    val canSend: Boolean get() = !running && prompt.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * Drives one relay-llm call and surfaces everything the library reports: streamed text,
 * usage, latency and the interceptor log.
 *
 * The point of interest is [buildProvider] -- assembling a backend plus its cross-cutting
 * behaviour is the whole call-site story.
 */
class LlmTestViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LlmTestUiState())
    val uiState: StateFlow<LlmTestUiState> = _uiState.asStateFlow()

    /**
     * One client for the whole app. relay-llm never mutates it -- each provider derives
     * its own copy -- so the connection pool stays shared.
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val tokenCounter = HeuristicTokenCounter()

    private var inFlight: Job? = null

    fun onBaseUrlChange(value: String) = _uiState.update { it.copy(baseUrl = value) }

    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value) }

    fun onModelChange(value: String) = _uiState.update { it.copy(model = value) }

    fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }

    fun onStreamingChange(value: Boolean) = _uiState.update { it.copy(streaming = value) }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return

        val messages = listOf(
            Message.system("你是一个简洁的中文助手,回答控制在三句话内。"),
            Message.user(state.prompt),
        )
        val request = ChatRequest(
            model = state.model,
            messages = messages,
            temperature = 0.7,
            timeoutMillis = 60_000,
        )

        _uiState.update {
            it.copy(
                running = true,
                output = "",
                toolCalls = emptyList(),
                error = null,
                metrics = null,
                logs = emptyList(),
                estimatedPromptTokens = tokenCounter.count(messages, state.model),
            )
        }

        val provider = buildProvider(state)

        inFlight = viewModelScope.launch {
            try {
                if (state.streaming) collectStream(provider, request) else callUnary(provider, request)
            } catch (e: CancellationException) {
                appendLog("call cancelled by user")
                throw e
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.describe()) }
            } finally {
                _uiState.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
    }

    /**
     * The call site: pick a backend, then layer behaviour onto it. Interceptors run
     * outermost first, so logging wraps retry, and every retry attempt is measured.
     */
    private fun buildProvider(state: LlmTestUiState): Provider =
        OpenAiCompatibleProvider(
            baseUrl = state.baseUrl,
            apiKey = state.apiKey,
            models = DeepSeek.MODELS,
            providerId = DeepSeek.ID,
            httpClient = httpClient,
        ).intercept(
            LoggingInterceptor(logger = ::appendLog),
            RetryInterceptor(maxAttempts = 3),
            MetricsInterceptor { metrics -> _uiState.update { it.copy(metrics = metrics) } },
        )

    private suspend fun collectStream(provider: Provider, request: ChatRequest) {
        val toolCalls = ToolCallAccumulator()

        provider.stream(request).collect { chunk ->
            when (chunk) {
                is ChatChunk.Text ->
                    _uiState.update { it.copy(output = it.output + chunk.delta) }

                is ChatChunk.ToolCalls ->
                    toolCalls.accept(chunk.delta)

                is ChatChunk.Done ->
                    _uiState.update { it.copy(toolCalls = toolCalls.build()) }
            }
        }
    }

    private suspend fun callUnary(provider: Provider, request: ChatRequest) {
        val response = provider.chat(request)
        _uiState.update {
            it.copy(
                output = response.message.content.orEmpty(),
                toolCalls = response.message.toolCalls,
            )
        }
    }

    private fun appendLog(line: String) {
        _uiState.update { it.copy(logs = (it.logs + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val MAX_LOG_LINES = 200
    }
}

private fun RelayLlmException.describe(): String = when (this) {
    is RelayLlmException.Auth -> "鉴权失败($statusCode):检查 API Key"
    is RelayLlmException.RateLimited -> "被限流" + (retryAfterMillis?.let {ms -> ",建议 ${ms / 1000}s 后重试" } ?: "")
    is RelayLlmException.Timeout -> "请求超时"
    is RelayLlmException.Network -> "网络不可达:${message}"
    is RelayLlmException.Server -> "服务端错误($statusCode)"
    is RelayLlmException.InvalidRequest -> "请求无效:${message}"
    is RelayLlmException.Unknown -> "未知错误:${message}"
}

/** Mirrors what [LlmTestViewModel] actually runs, so the UI can show the real call site. */
fun callSiteSnippet(state: LlmTestUiState): String {
    val invocation = if (state.streaming) {
        """
        |provider.stream(request).collect { chunk ->
        |    when (chunk) {
        |        is ChatChunk.Text      -> append(chunk.delta)
        |        is ChatChunk.ToolCalls -> acc.accept(chunk.delta)
        |        is ChatChunk.Done      -> show(chunk.usage)
        |    }
        |}
        """.trimMargin()
    } else {
        "val response = provider.chat(request)"
    }

    return """
    |val provider = OpenAiCompatibleProvider(
    |    baseUrl = "${state.baseUrl}",
    |    apiKey = apiKey,
    |    models = DeepSeek.MODELS,
    |    providerId = DeepSeek.ID,
    |    httpClient = okHttpClient,
    |).intercept(
    |    LoggingInterceptor(logger = ::log),
    |    RetryInterceptor(maxAttempts = 3),
    |    MetricsInterceptor { record(it) },
    |)
    |
    |val request = ChatRequest(
    |    model = "${state.model}",
    |    messages = listOf(Message.system(...), Message.user(prompt)),
    |    temperature = 0.7,
    |    timeoutMillis = 60_000,
    |)
    |
    |$invocation
    """.trimMargin()
}
