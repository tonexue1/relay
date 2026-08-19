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
import relay.llm.provider.DeepSeek
import relay.memory.extract.remembering
import relay.memory.FileArtifactStore
import relay.memory.GRAPH_ASSISTANT
import relay.memory.RawTurn
import relay.memory.SqliteMemoryStore
import relay.memory.recallPad

data class AssistantLine(
    val role: String,
    val text: String,
)

data class AssistantUiState(
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val prompt: String = AssistantCorpus.probes.first().prompt,
    val running: Boolean = false,
    val seeding: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val lines: List<AssistantLine> = emptyList(),
    val facts: String = "",
    val factCount: Int = 0,
    val recallPad: String = "",
    val seedNote: String = "",
) {
    val canSend: Boolean get() = !running && !seeding && prompt.isNotBlank() && apiKey.isNotBlank()
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
        viewModelScope.launch {
            refreshFacts()
            refreshPad(_uiState.value.prompt)
        }
    }

    fun onApiKeyChange(value: String) {
        dropAgent()
        _uiState.update { it.copy(apiKey = value) }
    }

    fun onPromptChange(value: String) {
        _uiState.update { it.copy(prompt = value) }
        viewModelScope.launch { refreshPad(value) }
    }

    fun seedWave(index: Int) {
        val wave = AssistantCorpus.waves.getOrNull(index) ?: return
        if (_uiState.value.seeding || _uiState.value.running) return
        viewModelScope.launch {
            _uiState.update { it.copy(seeding = true, error = null) }
            try {
                store.ingest(wave.drafts)
                refreshFacts()
                refreshPad(_uiState.value.prompt)
                val count = _uiState.value.factCount
                _uiState.update {
                    it.copy(seedNote = "${wave.title} 写入 ${wave.drafts.size} 条，活图现在 ${count} 条")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "入库失败: ${e.message}") }
            } finally {
                _uiState.update { it.copy(seeding = false) }
            }
        }
    }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        val input = state.prompt.trim()
        collect(input)
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
                refreshPad(input)
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

    private suspend fun refreshFacts() {
        val hit = store.facts(GRAPH_ASSISTANT)
        _uiState.update { it.copy(facts = hit.render(), factCount = hit.facts.size) }
    }

    private suspend fun refreshPad(text: String) {
        val pad = store.recallPad(GRAPH_ASSISTANT, text)
        _uiState.update { it.copy(recallPad = pad) }
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
