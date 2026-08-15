package relay.demo.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.AgentEvent
import relay.agent.AgentException
import relay.demo.BuildConfig
import relay.llm.RelayLlmException
import relay.llm.interceptor.LoggingInterceptor
import relay.llm.interceptor.RetryInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.model.Role
import relay.llm.provider.DeepSeek

data class AgentTestUiState(
    val baseUrl: String = DeepSeek.BASE_URL,
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val model: String = DeepSeek.CHAT,
    val prompt: String = SAMPLE_TASKS.first().second,
    val running: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val events: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
    val canContinue: Boolean = false,
) {
    val canSend: Boolean get() = !running && prompt.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()
}

class AgentTestViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AgentTestUiState())
    val uiState: StateFlow<AgentTestUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val toolHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private var agent: Agent? = null
    private var boundKey: String? = null
    private var inFlight: Job? = null
    private val toolbox = DemoToolbox(toolHttp)

    fun onBaseUrlChange(value: String) {
        dropAgent()
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun onApiKeyChange(value: String) {
        dropAgent()
        _uiState.update { it.copy(apiKey = value) }
    }

    fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        collect(ensureAgent(state).prompt(state.prompt), resetOutput = true)
    }

    fun continueRun() {
        val current = agent ?: return
        collect(current.continueRun(), resetOutput = true)
    }

    fun cancel() {
        inFlight?.cancel()
    }

    fun resetSession() {
        inFlight?.cancel()
        dropAgent()
        toolbox.clear()
        _uiState.update {
            it.copy(
                running = false,
                output = "",
                error = null,
                events = emptyList(),
                logs = emptyList(),
                canContinue = false,
            )
        }
    }

    private fun collect(events: kotlinx.coroutines.flow.Flow<AgentEvent>, resetOutput: Boolean) {
        _uiState.update {
            it.copy(
                running = true,
                output = if (resetOutput) "" else it.output,
                error = null,
                canContinue = false,
            )
        }
        inFlight = viewModelScope.launch {
            try {
                events.collect { event -> handle(event) }
            } catch (e: CancellationException) {
                appendEvent("cancelled")
                throw e
            } catch (e: AgentException) {
                _uiState.update { it.copy(error = e.message) }
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.describe()) }
            } finally {
                _uiState.update {
                    it.copy(
                        running = false,
                        canContinue = canContinueFrom(agent),
                    )
                }
            }
        }
    }

    private fun handle(event: AgentEvent) {
        appendEvent(formatEvent(event))
        when (event) {
            is AgentEvent.MessageUpdate -> when (val chunk = event.chunk) {
                is ChatChunk.Text -> _uiState.update { it.copy(output = it.output + chunk.delta) }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun ensureAgent(state: AgentTestUiState): Agent {
        val existing = agent
        if (existing != null && boundKey == state.apiKey) return existing
        val created = Agent(
            provider = DeepSeek.provider(
                apiKey = state.apiKey,
                httpClient = httpClient,
                baseUrl = state.baseUrl,
            ).intercept(
                LoggingInterceptor(logger = { line -> appendLog(line) }),
                RetryInterceptor(maxAttempts = 3),
            ),
            config = AgentConfig(
                model = state.model,
                systemPrompt = DEMO_SYSTEM_PROMPT,
                maxTurns = 12,
                timeoutMillis = 90_000,
            ),
            tools = toolbox.tools(),
        )
        agent = created
        boundKey = state.apiKey
        return created
    }

    private fun dropAgent() {
        agent = null
        boundKey = null
    }

    private fun appendEvent(line: String) {
        _uiState.update { it.copy(events = (it.events + line).takeLast(MAX_LINES)) }
    }

    private fun appendLog(line: String) {
        _uiState.update { it.copy(logs = (it.logs + line).takeLast(MAX_LINES)) }
    }

    private companion object {
        const val MAX_LINES = 200
    }
}

private val DEMO_SYSTEM_PROMPT =
    "你是手机上的助手。事实、算术、单位换算和笔记都必须走工具，不要口算或编造。" +
        "可用工具：get_current_time、calculator、convert_units、save_note、read_note、list_notes、echo、web_search、fetch_url。" +
        "回答用中文，先把该调的工具调完，再给简短结论。"

private fun canContinueFrom(agent: Agent?): Boolean {
    val last = agent?.state?.messages?.lastOrNull() ?: return false
    return last.role == Role.USER || last.role == Role.TOOL
}

private fun formatEvent(event: AgentEvent): String = when (event) {
    AgentEvent.AgentStart -> "agent_start"
    is AgentEvent.AgentEnd -> "agent_end messages=${event.messages.size}"
    AgentEvent.TurnStart -> "turn_start"
    is AgentEvent.TurnEnd ->
        "turn_end tools=${event.toolResults.size} assistant=${event.message.content?.take(40) ?: event.message.toolCalls.joinToString { it.name }}"
    is AgentEvent.MessageStart -> "message_start ${event.message.role.name.lowercase()}"
    is AgentEvent.MessageUpdate -> when (val chunk = event.chunk) {
        is ChatChunk.Text -> "message_update text:${chunk.delta}"
        is ChatChunk.ToolCalls -> "message_update tool_call:${chunk.delta.name ?: chunk.delta.argumentsDelta}"
        is ChatChunk.Done -> "message_update done ${chunk.finishReason}"
    }
    is AgentEvent.MessageEnd -> "message_end ${event.message.role.name.lowercase()}"
    is AgentEvent.ToolExecutionStart -> "tool_execution_start ${event.call.name} ${event.call.argumentsJson}"
    is AgentEvent.ToolExecutionEnd ->
        "tool_execution_end ${event.call.name} isError=${event.isError} ${event.result.take(80)}"
}

private fun RelayLlmException.describe(): String = when (this) {
    is RelayLlmException.Auth -> "鉴权失败($statusCode):检查 API Key"
    is RelayLlmException.RateLimited -> "被限流"
    is RelayLlmException.Timeout -> "请求超时"
    is RelayLlmException.Network -> "网络不可达:${message}"
    is RelayLlmException.Server -> "服务端错误($statusCode)"
    is RelayLlmException.InvalidRequest -> "请求无效:${message}"
    is RelayLlmException.Unknown -> "未知错误:${message}"
}

fun agentCallSiteSnippet(): String = """
    |val agent = Agent(
    |    provider = DeepSeek.provider(apiKey).intercept(
    |        LoggingInterceptor(logger = ::log),
    |        RetryInterceptor(maxAttempts = 3),
    |    ),
    |    config = AgentConfig(model = DeepSeek.CHAT, systemPrompt = "..."),
    |    tools = toolbox.tools(),
    |)
    |
    |agent.prompt(input).collect { event ->
    |    when (event) {
    |        is AgentEvent.MessageUpdate -> /* ChatChunk */
    |        is AgentEvent.ToolExecutionEnd -> /* result */
    |        is AgentEvent.AgentEnd -> /* transcript */
    |        else -> Unit
    |    }
    |}
""".trimMargin()
