package relay.demo.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
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
import relay.llm.model.ChatChunk
import relay.llm.model.Role
import relay.llm.provider.DeepSeek
import relay.memory.AssistantPlay
import relay.memory.CloudTripleExtractor
import relay.memory.FileArtifactStore
import relay.memory.GRAPH_ASSISTANT
import relay.memory.SqliteMemoryStore
import relay.memory.RawTurn
import relay.memory.remembering

data class AssistantLine(
    val role: String,
    val text: String,
)

data class AssistantUiState(
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val prompt: String = SAMPLE_PROMPTS.first().second,
    val running: Boolean = false,
    val organizing: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val lines: List<AssistantLine> = emptyList(),
    val facts: String = "",
    val unconsumed: Int = 0,
    val cloudOk: Boolean = false,
) {
    val canSend: Boolean get() = !running && prompt.isNotBlank() && apiKey.isNotBlank()
}

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val store = SqliteMemoryStore(
        AndroidMemoryDb(application),
        FileArtifactStore(File(application.filesDir, "memory-artifacts")),
    )

    private var agent: Agent? = null
    private var boundKey: String? = null
    private var inFlight: Job? = null

    init {
        viewModelScope.launch { refreshFacts() }
    }

    fun onApiKeyChange(value: String) {
        dropAgent()
        _uiState.update { it.copy(apiKey = value) }
    }

    fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }

    fun onCloudOkChange(value: Boolean) = _uiState.update { it.copy(cloudOk = value) }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        val input = state.prompt.trim()
        collect(input)
    }

    fun organize() {
        if (_uiState.value.organizing) return
        viewModelScope.launch { runOrganize(fromUser = true) }
    }

    fun resetChat() {
        inFlight?.cancel()
        dropAgent()
        _uiState.update {
            it.copy(
                running = false,
                output = "",
                error = null,
                lines = emptyList(),
            )
        }
    }

    private fun collect(input: String) {
        _uiState.update {
            it.copy(
                running = true,
                output = "",
                error = null,
                prompt = "",
                lines = it.lines + AssistantLine("user", input),
            )
        }
        inFlight = viewModelScope.launch {
            try {
                store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = input))
                var assistant = ""
                ensureAgent(_uiState.value).prompt(input).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdate -> when (val chunk = event.chunk) {
                            is ChatChunk.Text -> {
                                assistant += chunk.delta
                                _uiState.update { it.copy(output = it.output + chunk.delta) }
                            }
                            else -> Unit
                        }
                        else -> Unit
                    }
                }
                if (assistant.isNotBlank()) {
                    store.capture(RawTurn(GRAPH_ASSISTANT, role = "assistant", text = assistant))
                    _uiState.update {
                        it.copy(lines = it.lines + AssistantLine("assistant", assistant), output = "")
                    }
                }
                runOrganize(fromUser = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentException) {
                _uiState.update { it.copy(error = e.message) }
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _uiState.update { it.copy(running = false) }
                refreshFacts()
            }
        }
    }

    private suspend fun runOrganize(fromUser: Boolean) {
        if (!_uiState.value.cloudOk) {
            if (fromUser) {
                _uiState.update { it.copy(error = "未允许上云，原文仍留在本机") }
            }
            refreshFacts()
            return
        }
        val waiting = store.unconsumed(GRAPH_ASSISTANT)
        if (waiting.isEmpty()) {
            refreshFacts()
            return
        }
        store.markScope(GRAPH_ASSISTANT, waiting.map { it.id }, "cloud_ok")
        val pending = store.unconsumed(GRAPH_ASSISTANT, principal = "extractor")
        if (pending.isEmpty()) {
            refreshFacts()
            return
        }
        val key = _uiState.value.apiKey
        if (key.isBlank()) return
        _uiState.update { it.copy(organizing = true, error = null) }
        try {
            val extractor = CloudTripleExtractor(
                DeepSeek.provider(apiKey = key, httpClient = httpClient),
            )
            val drafts = extractor.extract(
                GRAPH_ASSISTANT,
                CloudTripleExtractor.formatTurns(pending),
                pending.map { it.id },
            )
            if (drafts.isNotEmpty()) store.ingest(drafts)
        } catch (e: RelayLlmException) {
            _uiState.update { it.copy(error = "整理失败: ${e.message}") }
        } finally {
            _uiState.update { it.copy(organizing = false) }
            refreshFacts()
        }
    }

    private suspend fun refreshFacts() {
        val facts = store.facts(GRAPH_ASSISTANT).render()
        val pending = store.unconsumed(GRAPH_ASSISTANT).size
        _uiState.update { it.copy(facts = facts, unconsumed = pending) }
    }

    private fun ensureAgent(state: AssistantUiState): Agent {
        val existing = agent
        if (existing != null && boundKey == state.apiKey) return existing
        val created = Agent(
            provider = DeepSeek.provider(apiKey = state.apiKey, httpClient = httpClient),
            config = AgentConfig(
                model = DeepSeek.CHAT,
                systemPrompt = ASSISTANT_SYSTEM,
                maxTurns = 1,
                timeoutMillis = 90_000,
            ),
            transformContext = store.remembering(GRAPH_ASSISTANT),
        )
        agent = created
        boundKey = state.apiKey
        return created
    }

    private fun dropAgent() {
        agent = null
        boundKey = null
    }

    override fun onCleared() {
        inFlight?.cancel()
        store.close()
        super.onCleared()
    }
}

private const val ASSISTANT_SYSTEM =
    "你是手机上的个人助理。根据已记住的事实回答，简短直接。" +
        "事实与问题冲突时以事实为准。不知道就说不知道。用中文。"

val SAMPLE_PROMPTS: List<Pair<String, String>> = listOf(
    "过敏" to "记一下，我花生过敏，火锅蘸料也别推荐花生酱。",
    "火锅" to "今晚想吃火锅，有什么别踩的雷？",
    "作业" to "我作业没做完。",
    "工龄" to "我工作两年了。",
    "话剧" to AssistantPlay.SAMPLE_PROMPT,
)
