package relay.demo.orchestra

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
import relay.llm.provider.DeepSeek
import relay.orchestra.GroupChat
import relay.orchestra.TeamEvent

data class Seat(
    val id: String,
    val stance: String,
)

data class Bubble(
    val id: Long,
    val speakerId: String,
    val text: String,
    val streaming: Boolean = false,
)

data class GroupChatUiState(
    val baseUrl: String = DeepSeek.BASE_URL,
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val model: String = DeepSeek.CHAT,
    val prompt: String = GROUP_CHAT_TOPICS.first().second,
    val running: Boolean = false,
    val error: String? = null,
    val speakingId: String? = null,
    val bubbles: List<Bubble> = emptyList(),
    val events: List<String> = emptyList(),
) {
    val canSend: Boolean
        get() = !running && prompt.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()

    val highlightId: String
        get() = speakingId ?: nextSeat(bubbles)
}

val GROUP_CHAT_SEATS: List<Seat> = listOf(
    Seat("产品", "要快，先验证有没有人要"),
    Seat("工程", "边界、取消、隔离没做对就不要扩"),
    Seat("设计", "用户必须一眼看出现在是谁在说话"),
)

internal val GROUP_CHAT_TOPICS: List<Pair<String, String>> = listOf(
    "端侧 3B" to "要不要让端上 3B 当主对话模型？",
    "共享 messages" to "多 agent 该不该共享一份 messages？",
    "大聊天框" to "调试台要不要做成一个大聊天框？",
)

class GroupChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GroupChatUiState())
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var chat: GroupChat? = null
    private var boundKey: String? = null
    private var inFlight: Job? = null
    private var nextBubbleId = 1L

    fun onBaseUrlChange(value: String) {
        dropChat()
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun onApiKeyChange(value: String) {
        dropChat()
        _uiState.update { it.copy(apiKey = value) }
    }

    fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        val table = ensureChat(state)
        _uiState.update { it.copy(running = true, error = null, speakingId = null) }
        inFlight = viewModelScope.launch {
            try {
                table.prompt(state.prompt).collect { handle(it) }
            } catch (e: CancellationException) {
                appendEvent("cancelled")
                throw e
            } catch (e: AgentException) {
                _uiState.update { it.copy(error = e.message) }
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.describe()) }
            } finally {
                _uiState.update { it.copy(running = false, speakingId = null) }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
    }

    fun resetTable() {
        inFlight?.cancel()
        dropChat()
        nextBubbleId = 1L
        _uiState.update {
            it.copy(
                running = false,
                error = null,
                speakingId = null,
                bubbles = emptyList(),
                events = emptyList(),
            )
        }
    }

    private fun handle(event: TeamEvent) {
        when (event) {
            is TeamEvent.YieldStarted -> {
                _uiState.update { it.copy(speakingId = event.speakerId) }
                appendEvent("yield ${event.speakerId}")
                startDraft(event.speakerId)
            }
            is TeamEvent.YieldChild -> when (val child = event.event) {
                is AgentEvent.MessageUpdate -> when (val chunk = child.chunk) {
                    is ChatChunk.Text -> appendDraft(event.speakerId, chunk.delta)
                    else -> Unit
                }
                else -> Unit
            }
            is TeamEvent.Utterance -> {
                commitBubble(event.speakerId, event.text)
                appendEvent("utterance ${event.speakerId}")
                if (event.speakerId == "user") {
                    _uiState.update { it.copy(speakingId = null) }
                }
            }
            else -> Unit
        }
    }

    private fun ensureChat(state: GroupChatUiState): GroupChat {
        val existing = chat
        if (existing != null && boundKey == state.apiKey) return existing
        val provider = DeepSeek.provider(
            apiKey = state.apiKey,
            httpClient = httpClient,
            baseUrl = state.baseUrl,
        ).intercept(
            LoggingInterceptor(logger = { }),
            RetryInterceptor(maxAttempts = 3),
        )
        val created = GroupChat(
            members = GROUP_CHAT_SEATS.map { seat ->
                GroupChat.Member(seat.id) { transform ->
                    Agent(
                        provider = provider,
                        config = AgentConfig(
                            model = state.model,
                            systemPrompt = expertCard(seat),
                            maxTurns = 1,
                            timeoutMillis = 60_000,
                        ),
                        transformContext = transform,
                    )
                }
            },
        )
        chat = created
        boundKey = state.apiKey
        return created
    }

    private fun dropChat() {
        chat = null
        boundKey = null
    }

    private fun startDraft(speakerId: String) {
        _uiState.update { state ->
            val bubbles = state.bubbles
            val last = bubbles.lastOrNull()
            if (last?.speakerId == speakerId && last.streaming) {
                state
            } else {
                state.copy(bubbles = bubbles + Bubble(nextBubbleId++, speakerId, "", streaming = true))
            }
        }
    }

    private fun appendDraft(speakerId: String, delta: String) {
        _uiState.update { state ->
            val bubbles = state.bubbles.toMutableList()
            val last = bubbles.lastOrNull()
            if (last != null && last.speakerId == speakerId && last.streaming) {
                bubbles[bubbles.lastIndex] = last.copy(text = last.text + delta)
            } else {
                bubbles += Bubble(nextBubbleId++, speakerId, delta, streaming = true)
            }
            state.copy(bubbles = bubbles)
        }
    }

    private fun commitBubble(speakerId: String, text: String) {
        _uiState.update { state ->
            val bubbles = state.bubbles.toMutableList()
            val last = bubbles.lastOrNull()
            if (last != null && last.speakerId == speakerId && last.streaming) {
                bubbles[bubbles.lastIndex] = last.copy(text = text.ifBlank { last.text }, streaming = false)
            } else {
                bubbles += Bubble(nextBubbleId++, speakerId, text)
            }
            state.copy(bubbles = bubbles)
        }
    }

    private fun appendEvent(line: String) {
        _uiState.update { it.copy(events = (it.events + line).takeLast(80)) }
    }
}

internal fun nextSeat(bubbles: List<Bubble>): String {
    val ids = GROUP_CHAT_SEATS.map { it.id }
    val sinceUser = bubbles.takeLastWhile { it.speakerId != "user" }
    val spoken = sinceUser.count { it.speakerId in ids }
    if (spoken >= ids.size) return ids.first()
    val last = sinceUser.lastOrNull { it.speakerId in ids }?.speakerId ?: return ids.first()
    return ids[(ids.indexOf(last) + 1) % ids.size]
}

private fun expertCard(seat: Seat): String =
    "你是圆桌上的「${seat.id}」。立场：${seat.stance}。" +
        "只说你自己的一句（两三句中文），不要总结全场，不要主持，不要点下一位。" +
        "根据已公开的对白回应，不要编造别人没说过的话。"

private fun RelayLlmException.describe(): String = when (this) {
    is RelayLlmException.Auth -> "鉴权失败($statusCode):检查 API Key"
    is RelayLlmException.RateLimited -> "被限流"
    is RelayLlmException.Timeout -> "请求超时"
    is RelayLlmException.Network -> "网络不可达:$message"
    is RelayLlmException.Server -> "服务端错误($statusCode)"
    is RelayLlmException.InvalidRequest -> "请求无效:$message"
    is RelayLlmException.Unknown -> "未知错误:$message"
}
