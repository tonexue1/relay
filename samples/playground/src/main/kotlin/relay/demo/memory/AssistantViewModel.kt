package relay.demo.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.AgentEvent
import relay.agent.AgentException
import relay.demo.BuildConfig
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.provider.DeepSeek
import relay.memory.GRAPH_ASSISTANT
import relay.memory.MemoryRuntime
import relay.memory.MemorySessionCoordinator
import relay.memory.FlushReason
import relay.memory.RawTurn
import relay.memory.dream.AgentConsolidator
import relay.memory.engine.FileArtifactStore
import relay.memory.engine.RoomMemoryDb
import relay.memory.engine.SqliteMemoryStore
import relay.memory.extract.CloudTripleExtractor

data class AssistantLine(
    val role: String,
    val text: String,
)

data class AssistantUiState(
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val prompt: String = AssistantCorpus.talks.first().prompt,
    val running: Boolean = false,
    val replaying: Boolean = false,
    val replayProgress: String = "",
    val learning: Boolean = false,
    val consolidating: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val lines: List<AssistantLine> = emptyList(),
    val facts: String = "",
    val factCount: Int = 0,
    val claimCount: Int = 0,
    val pendingRaw: Int = 0,
    val stageTrace: String = "",
    val toolTrace: String = "",
) {
    val busy: Boolean get() = running || replaying
    val canSend: Boolean get() = !running && prompt.isNotBlank() && apiKey.isNotBlank()
}

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val store = SqliteMemoryStore(
        RoomMemoryDb.file(application),
        FileArtifactStore(File(application.filesDir, "memory-artifacts")),
    )

    private var memory: MemoryRuntime? = null
    private var dayAgent: Agent? = null
    private var boundKey: String? = null
    private var inFlight: Job? = null
    private val learnMutex = Mutex()
    private var unsweptLearns = 0
    private var sessionId: String = newSessionId()
    private val learnCoordinator = MemorySessionCoordinator(viewModelScope) { reason ->
        flushLearn(reason)
    }

    init {
        viewModelScope.launch {
            refreshGraph()
            if (_uiState.value.apiKey.isNotBlank() && store.unconsumed(GRAPH_ASSISTANT).isNotEmpty()) {
                learnCoordinator.requestFlush(FlushReason.FOREGROUND_RECOVERY)
            }
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value) }
        if (boundKey != null && boundKey != value) {
            viewModelScope.launch {
                learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
                dropSession()
                sessionId = newSessionId()
            }
        }
    }

    fun onPromptChange(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        collectDay(state.prompt.trim())
    }

    fun toggleLatestReplay() {
        if (_uiState.value.replaying) {
            inFlight?.cancel()
            return
        }
        val state = _uiState.value
        if (state.busy || state.apiKey.isBlank()) return
        inFlight = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    replaying = true,
                    replayProgress = "准备回放",
                    error = null,
                )
            }
            try {
                learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
                dropSession()
                sessionId = newSessionId()
                _uiState.update {
                    it.copy(
                        lines = emptyList(),
                        output = "",
                        toolTrace = "",
                        stageTrace = "回放开始",
                    )
                }
                AssistantCorpus.episodeClaimReplay.forEachIndexed { index, input ->
                    _uiState.update {
                        it.copy(replayProgress = "${index + 1}/${AssistantCorpus.episodeClaimReplay.size}")
                    }
                    runTurn(input)
                    if ((index + 1) % REPLAY_EPISODE_TURNS == 0) {
                        learnCoordinator.flushAndJoin(FlushReason.TURN_THRESHOLD)
                    }
                }
                learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
                _uiState.update { it.copy(stageTrace = "回放完成") }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(stageTrace = "回放已停止") }
                throw e
            } finally {
                _uiState.update {
                    it.copy(
                        replaying = false,
                        replayProgress = "",
                        running = false,
                        output = "",
                    )
                }
                refreshGraph()
            }
        }
    }

    fun resetChat() {
        inFlight?.cancel()
        _uiState.update {
            it.copy(
                running = false,
                learning = false,
                consolidating = false,
                output = "",
                error = null,
                lines = emptyList(),
                toolTrace = "",
                stageTrace = "",
            )
        }
        viewModelScope.launch {
            learnCoordinator.flushAndJoin(FlushReason.RESET)
            dropSession()
            sessionId = newSessionId()
        }
    }

    private fun collectDay(input: String) {
        inFlight = viewModelScope.launch { runTurn(input) }
    }

    private suspend fun runTurn(input: String) {
        _uiState.update {
            it.copy(
                running = true,
                output = "",
                error = null,
                prompt = "",
                toolTrace = "",
                stageTrace = "对话",
                lines = it.lines + AssistantLine("user", input),
            )
        }
        try {
            store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = input, sessionId = sessionId))
            refreshGraph()
            val assistant = runAgent(ensureDay(_uiState.value), input)
            if (assistant.isNotBlank()) {
                store.capture(
                    RawTurn(GRAPH_ASSISTANT, role = "assistant", text = assistant, sessionId = sessionId),
                )
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
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _uiState.update { it.copy(running = false) }
            refreshGraph()
            learnCoordinator.onTurnCompleted()
        }
    }

    private suspend fun flushLearn(reason: FlushReason) {
        learnMutex.withLock {
            val state = _uiState.value
            if (state.apiKey.isBlank()) return@withLock
            val runtime = memory ?: run {
                ensureDay(state)
                memory ?: return@withLock
            }
            if (store.unconsumed(GRAPH_ASSISTANT).isEmpty()) return@withLock
            _uiState.update { it.copy(learning = true, stageTrace = "学习中 · ${reason.name.lowercase()}") }
            try {
                val reports = mutableListOf<relay.memory.LearnReport>()
                var report: relay.memory.LearnReport
                var succeeded: Boolean
                do {
                    report = if (reason == FlushReason.FOREGROUND_RECOVERY) {
                        runtime.learn(GRAPH_ASSISTANT)
                    } else {
                        runtime.learn(GRAPH_ASSISTANT, sessionId)
                    }
                    reports += report
                    succeeded = report.outcome?.name?.startsWith("SUCCESS") == true
                } while (
                    reason == FlushReason.FOREGROUND_RECOVERY &&
                    succeeded &&
                    report.eventIds.isNotEmpty() &&
                    store.unconsumed(GRAPH_ASSISTANT).isNotEmpty() &&
                    reports.size < MAX_RECOVERY_BATCHES
                )
                val claims = reports.flatMap { it.claims }
                val drafts = reports.flatMap { it.drafts }
                val extractErrors = reports.flatMap { it.extractErrors }
                val ingestErrors = reports.flatMap { it.errors }
                val lastOutcome = reports.lastOrNull()?.outcome
                val body = buildString {
                    append("Claim ${claims.size} 条，图稿 ${drafts.size} 条")
                    lastOutcome?.let { append(" · $it") }
                    append('\n')
                    for (claim in claims) append("- ${claim.text}\n")
                    for (draft in drafts) {
                        append("- ${draft.s} ${draft.p} ${draft.o}")
                        if (draft.retract) append(" retract")
                        append('\n')
                    }
                    if (extractErrors.isNotEmpty()) {
                        append("\nextract errors\n")
                        for (error in extractErrors) append("- $error\n")
                    }
                    if (ingestErrors.isNotEmpty()) {
                        append("\ningest errors\n")
                        for (err in ingestErrors) {
                            append("- ${err.p} ${err.reason} (${err.s} ${err.o})\n")
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        toolTrace = body.trim(),
                        stageTrace = if (lastOutcome?.name?.startsWith("SUCCESS") == true) {
                            "已学习"
                        } else {
                            "学习待重试"
                        },
                    )
                }
                if (drafts.isNotEmpty()) {
                    unsweptLearns++
                    if (unsweptLearns >= CONSOLIDATE_EVERY_LEARNS) {
                        runConsolidate(runtime)
                        unsweptLearns = 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.message ?: e.toString()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "学习失败: ${e.message}") }
            } finally {
                _uiState.update { it.copy(learning = false) }
                refreshGraph()
            }
        }
    }

    fun onBackground() {
        learnCoordinator.requestFlush(FlushReason.BACKGROUND)
    }

    fun onForeground() {
        if (_uiState.value.apiKey.isNotBlank()) {
            learnCoordinator.requestFlush(FlushReason.FOREGROUND_RECOVERY)
        }
    }

    private suspend fun runConsolidate(runtime: MemoryRuntime) {
        if (store.facts(GRAPH_ASSISTANT).facts.isEmpty()) return
        _uiState.update { it.copy(consolidating = true, stageTrace = "整理中") }
        try {
            val report = runtime.consolidate(GRAPH_ASSISTANT)
            _uiState.update {
                it.copy(
                    stageTrace = "已整理",
                    toolTrace = report.summary.ifBlank { it.toolTrace },
                )
            }
        } finally {
            _uiState.update { it.copy(consolidating = false) }
            refreshGraph()
        }
    }

    private suspend fun runAgent(agent: Agent, input: String): String {
        var assistant = ""
        agent.prompt(input).collect { event ->
            when (event) {
                is AgentEvent.MessageUpdate -> when (val chunk = event.chunk) {
                    is ChatChunk.Text -> {
                        assistant += chunk.delta
                        _uiState.update { it.copy(output = it.output + chunk.delta) }
                    }
                    else -> Unit
                }
                is AgentEvent.ToolExecutionStart -> appendTrace(
                    "→ ${event.call.name} ${event.call.argumentsJson.take(160)}",
                )
                is AgentEvent.ToolExecutionEnd -> {
                    val mark = if (event.isError) "✗" else "←"
                    appendTrace("$mark ${event.call.name} ${event.result.take(200)}")
                }
                else -> Unit
            }
        }
        return assistant
    }

    private fun appendTrace(line: String) {
        _uiState.update { state ->
            val next = if (state.toolTrace.isBlank()) line else state.toolTrace + "\n" + line
            state.copy(toolTrace = next)
        }
    }

    private suspend fun refreshGraph() {
        val hit = store.facts(GRAPH_ASSISTANT)
        val pending = store.unconsumed(GRAPH_ASSISTANT).size
        val claims = store.claims(GRAPH_ASSISTANT)
        _uiState.update {
            it.copy(
                facts = hit.render(),
                factCount = hit.facts.size,
                claimCount = claims.size,
                pendingRaw = pending,
            )
        }
    }

    private fun ensureDay(state: AssistantUiState): Agent {
        if (dayAgent != null && boundKey == state.apiKey && memory != null) return dayAgent!!
        dropSession()
        boundKey = state.apiKey
        val provider = DeepSeek.provider(apiKey = state.apiKey, httpClient = httpClient)
        val runtime = MemoryRuntime(
            store = store,
            extractor = CloudTripleExtractor(provider),
            consolidator = AgentConsolidator(provider, store),
        )
        memory = runtime
        dayAgent = Agent(
            provider = provider,
            config = AgentConfig(
                model = DeepSeek.CHAT,
                systemPrompt = DAY_SYSTEM,
                maxTurns = 8,
                timeoutMillis = 90_000,
            ),
            tools = runtime.dayTools(GRAPH_ASSISTANT),
            contextAugmenters = listOf(runtime.recalling(GRAPH_ASSISTANT)),
        )
        return dayAgent!!
    }

    private fun dropSession() {
        dayAgent = null
        memory = null
        boundKey = null
        unsweptLearns = 0
    }

    override fun onCleared() {
        inFlight?.cancel()
        learnCoordinator.cancel()
        store.close()
        super.onCleared()
    }

    private companion object {
        const val CONSOLIDATE_EVERY_LEARNS = 3
        const val MAX_RECOVERY_BATCHES = 32
        const val REPLAY_EPISODE_TURNS = 4
        fun newSessionId(): String = UUID.randomUUID().toString()
    }
}

private const val DAY_SYSTEM =
    "你是手机上的个人助理。图在用户设备上。先看垫进来的已知事实，不够再 memory_query 或 memory_facts。" +
        "query 用字面词（花生），不要把火锅当成过敏。用户新说的事实先听着，不要自己 ingest；Episode 后会自动学习。" +
        "复杂经历可能以相关经历形式垫入。" +
        "不知道图里有没有就说还没记住。用中文，简短。"
