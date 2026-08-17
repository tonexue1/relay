package relay.werewolf

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
import relay.llm.RelayLlmException
import relay.llm.interceptor.LoggingInterceptor
import relay.llm.interceptor.RetryInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.provider.DeepSeek
import relay.orchestra.TeamEvent
import relay.werewolf.engine.Channel
import relay.werewolf.engine.Phase
import relay.werewolf.engine.WerewolfEngine
import relay.werewolf.engine.deal

data class Bubble(
    val id: Long,
    val speakerId: String,
    val text: String,
    val channel: String = Channel.PUBLIC,
    val streaming: Boolean = false,
)

data class WerewolfUiState(
    val baseUrl: String = DeepSeek.BASE_URL,
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val model: String = DeepSeek.CHAT,
    val running: Boolean = false,
    val error: String? = null,
    val phase: String = "未开局",
    val day: Int = 0,
    val speakingId: String? = null,
    val godView: Boolean = false,
    val roster: String = "一号到六号 · 身份暗牌",
    val bubbles: List<Bubble> = emptyList(),
    val events: List<String> = emptyList(),
) {
    val canStart: Boolean
        get() = !running && baseUrl.isNotBlank() && apiKey.isNotBlank()

    val visibleBubbles: List<Bubble>
        get() = if (godView) bubbles else bubbles.filter { it.channel == Channel.PUBLIC }
}

class WerewolfViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WerewolfUiState())
    val uiState: StateFlow<WerewolfUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var inFlight: Job? = null
    private var nextBubbleId = 1L
    private var engine: WerewolfEngine? = null

    fun onBaseUrlChange(value: String) = _uiState.update { it.copy(baseUrl = value) }

    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value) }

    fun toggleGodView() = _uiState.update { it.copy(godView = !it.godView) }

    fun start() {
        val state = _uiState.value
        if (!state.canStart) return
        val table = WerewolfEngine(deal())
        engine = table
        val provider = DeepSeek.provider(
            apiKey = state.apiKey,
            httpClient = httpClient,
            baseUrl = state.baseUrl,
        ).intercept(
            LoggingInterceptor(logger = { }),
            RetryInterceptor(maxAttempts = 3),
        )
        val match = WerewolfMatch(table) { _, transform ->
            Agent(
                provider = provider,
                config = AgentConfig(
                    model = state.model,
                    systemPrompt = "你在玩狼人杀。你只知道自己的座位号和暗牌；别人只有号，没有官方身份。不要当主持人，不要改规则。",
                    maxTurns = 1,
                    timeoutMillis = 60_000,
                ),
                transformContext = transform,
            )
        }
        nextBubbleId = 1L
        _uiState.update {
            it.copy(
                running = true,
                error = null,
                speakingId = null,
                bubbles = emptyList(),
                events = emptyList(),
                phase = table.publicPhaseLabel(),
                day = table.day,
                roster = table.publicRoster(),
            )
        }
        inFlight = viewModelScope.launch {
            try {
                match.play().collect { event ->
                    handle(event)
                    syncBoard()
                }
            } catch (e: CancellationException) {
                appendEvent("cancelled")
                throw e
            } catch (e: AgentException) {
                _uiState.update { it.copy(error = e.message) }
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.describe()) }
            } finally {
                syncBoard()
                _uiState.update { it.copy(running = false, speakingId = null) }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
    }

    private fun handle(event: TeamEvent) {
        when (event) {
            is TeamEvent.YieldStarted -> {
                val night = engine?.phase == Phase.NIGHT_WOLVES || engine?.phase == Phase.NIGHT_SEER
                _uiState.update {
                    it.copy(speakingId = if (night && !it.godView) null else event.speakerId)
                }
                appendEvent(if (night) "night" else "yield ${event.speakerId}")
                val channel = when (engine?.phase) {
                    Phase.NIGHT_WOLVES -> Channel.WOLF
                    Phase.NIGHT_SEER -> Channel.SEER
                    else -> Channel.PUBLIC
                }
                startDraft(event.speakerId, channel)
            }
            is TeamEvent.YieldChild -> when (val child = event.event) {
                is AgentEvent.MessageUpdate -> when (val chunk = child.chunk) {
                    is ChatChunk.Text -> appendDraft(event.speakerId, chunk.delta)
                    else -> Unit
                }
                else -> Unit
            }
            is TeamEvent.Utterance -> {
                commitBubble(event.speakerId, event.text, event.channel)
                appendEvent("utterance ${event.speakerId}")
                if (event.speakerId == "system") {
                    _uiState.update { it.copy(speakingId = null) }
                }
            }
            else -> Unit
        }
    }

    private fun syncBoard() {
        val table = engine ?: return
        _uiState.update {
            it.copy(
                phase = table.publicPhaseLabel(),
                day = table.day,
                roster = table.publicRoster(),
            )
        }
    }

    private fun startDraft(speakerId: String, channel: String) {
        _uiState.update { state ->
            val last = state.bubbles.lastOrNull()
            if (last?.speakerId == speakerId && last.streaming) state
            else state.copy(
                bubbles = state.bubbles + Bubble(nextBubbleId++, speakerId, "", channel, streaming = true),
            )
        }
    }

    private fun appendDraft(speakerId: String, delta: String) {
        _uiState.update { state ->
            val bubbles = state.bubbles.toMutableList()
            val last = bubbles.lastOrNull()
            if (last != null && last.speakerId == speakerId && last.streaming) {
                bubbles[bubbles.lastIndex] = last.copy(text = last.text + delta)
            } else {
                bubbles += Bubble(nextBubbleId++, speakerId, delta, last?.channel ?: Channel.PUBLIC, streaming = true)
            }
            state.copy(bubbles = bubbles)
        }
    }

    private fun commitBubble(speakerId: String, text: String, channel: String) {
        _uiState.update { state ->
            val bubbles = state.bubbles.toMutableList()
            val last = bubbles.lastOrNull()
            if (last != null && last.speakerId == speakerId && last.streaming) {
                bubbles[bubbles.lastIndex] = last.copy(
                    text = text.ifBlank { last.text },
                    channel = channel,
                    streaming = false,
                )
            } else {
                bubbles += Bubble(nextBubbleId++, speakerId, text, channel)
            }
            state.copy(bubbles = bubbles)
        }
    }

    private fun appendEvent(line: String) {
        _uiState.update { it.copy(events = (it.events + line).takeLast(80)) }
    }
}

private fun RelayLlmException.describe(): String = when (this) {
    is RelayLlmException.Auth -> "鉴权失败($statusCode):检查 API Key"
    is RelayLlmException.RateLimited -> "被限流"
    is RelayLlmException.Timeout -> "请求超时"
    is RelayLlmException.Network -> "网络不可达:$message"
    is RelayLlmException.Server -> "服务端错误($statusCode)"
    is RelayLlmException.InvalidRequest -> "请求无效:$message"
    is RelayLlmException.Unknown -> "未知错误:$message"
}
