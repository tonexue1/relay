package relay.assistant.state

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.AgentException
import relay.artifacts.FileArtifactRepository
import relay.assistant.BuildConfig
import relay.assistant.artifact.ArtifactGroundingGate
import relay.assistant.session.AssistantSession
import relay.assistant.session.SessionStore
import relay.assistant.session.toAgentTranscript
import relay.llm.RelayLlmException
import relay.llm.provider.DeepSeek
import relay.memory.FlushReason
import relay.memory.GRAPH_ASSISTANT
import relay.memory.MemoryRuntime
import relay.memory.MemorySessionCoordinator
import relay.memory.RawTurn
import relay.memory.RecallContext
import relay.memory.dream.AgentConsolidator
import relay.memory.engine.FileArtifactStore
import relay.memory.engine.RoomMemoryDb
import relay.memory.engine.SqliteMemoryStore
import relay.memory.extract.CloudTripleExtractor
import relay.memory.predicateLabel
import relay.uikit.ChatTurn
import relay.uikit.OrderedTurnReducer
import relay.uikit.TurnItem
import relay.uikit.uiArtifactTools
import relay.uikit.ChoiceFormSpec
import relay.uikit.GraphEdge
import relay.uikit.GraphNode
import relay.uikit.GraphSpec
import relay.uikit.WidgetParser

data class MemoryRelationUi(
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val scopeLabel: String = "会话",
    val recallable: Boolean = false,
    val isolated: Boolean = false,
)

data class MemoryClaimUi(
    val text: String,
    val confidence: Double,
    val scopeLabel: String = "会话",
    val recallable: Boolean = false,
    val isolated: Boolean = false,
)

data class AssistantUiState(
    val apiKey: String = "",
    val memoryEnabled: Boolean = true,
    val sessions: List<AssistantSession> = emptyList(),
    val activeSessionId: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val running: Boolean = false,
    val learning: Boolean = false,
    val error: String? = null,
    val graph: GraphSpec = GraphSpec(nodes = emptyList(), edges = emptyList()),
    val relations: List<MemoryRelationUi> = emptyList(),
    val claims: List<MemoryClaimUi> = emptyList(),
    val pendingRaw: Int = 0,
) {
    val activeSession: AssistantSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }
    val canSend: Boolean
        get() = apiKey.isNotBlank() && !running
}

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionStore = SessionStore(application)
    private val initialSessions = sessionStore.load().ifEmpty { listOf(SessionStore.fresh()) }
    private val _uiState = MutableStateFlow(
        AssistantUiState(
            apiKey = sessionStore.loadApiKey(BuildConfig.DEEPSEEK_API_KEY),
            memoryEnabled = sessionStore.loadMemoryEnabled(),
            sessions = initialSessions,
            activeSessionId = initialSessions.first().id,
            turns = initialSessions.first().turns,
        ),
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val store = SqliteMemoryStore(
        RoomMemoryDb.file(application),
        FileArtifactStore(File(application.filesDir, "memory-artifacts")),
    )
    private val artifacts = FileArtifactRepository(File(application.filesDir, "ui-artifacts"))
    private val learnMutex = Mutex()
    private var runtime: MemoryRuntime? = null
    private var agent: Agent? = null
    private var boundKey: String? = null
    private var boundMemoryEnabled: Boolean? = null
    private var boundAutomaticRecall: Boolean? = null
    private var inFlight: Job? = null
    private val learnCoordinator = MemorySessionCoordinator(viewModelScope) { reason ->
        flushLearn(reason)
    }

    init {
        sessionStore.save(initialSessions)
        viewModelScope.launch {
            refreshMemory()
            if (
                _uiState.value.apiKey.isNotBlank() &&
                _uiState.value.memoryEnabled &&
                store.unconsumed(GRAPH_ASSISTANT).isNotEmpty()
            ) {
                learnCoordinator.requestFlush(FlushReason.FOREGROUND_RECOVERY)
            }
        }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isBlank() || !_uiState.value.canSend) return
        inFlight = viewModelScope.launch { runTurn(text) }
    }

    fun submitChoiceForm(
        turnId: String,
        itemId: String,
        answers: Map<String, List<String>>,
    ) {
        if (!_uiState.value.canSend) return
        var submittedMessage: String? = null
        _uiState.update { state ->
            state.copy(
                turns = state.turns.map { turn ->
                    if (turn.id != turnId) return@map turn
                    turn.copy(
                        items = turn.items.map { item ->
                            val widget = item as? TurnItem.Widget
                            val form = widget?.spec as? ChoiceFormSpec
                            if (widget == null || form == null || widget.id != itemId || form.submittedAnswers != null) {
                                item
                            } else {
                                val taskAnchor = ChoiceContinuation.taskAnchor(state.turns, turnId, form)
                                val submitted = form.copy(taskAnchor = taskAnchor, submittedAnswers = answers)
                                if (WidgetParser.validate(submitted) != null) {
                                    item
                                } else {
                                    submittedMessage = ChoiceContinuation.message(
                                        spec = form,
                                        answers = answers,
                                        taskAnchor = taskAnchor,
                                    )
                                    widget.copy(spec = submitted)
                                }
                            }
                        },
                    )
                },
            )
        }
        submittedMessage?.let {
            persistActiveSession()
            inFlight = viewModelScope.launch {
                runTurn(
                    input = it,
                    captureMemory = false,
                    automaticRecall = false,
                    showUserTurn = false,
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onApiKeyChange(value: String) {
        sessionStore.saveApiKey(value)
        val changed = boundKey != null && value != boundKey
        _uiState.update { it.copy(apiKey = value) }
        if (changed) {
            viewModelScope.launch {
                learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
                dropAgent()
            }
        }
    }

    fun onMemoryEnabledChange(value: Boolean) {
        sessionStore.saveMemoryEnabled(value)
        _uiState.update { it.copy(memoryEnabled = value) }
        dropAgent()
        if (value && _uiState.value.apiKey.isNotBlank()) {
            learnCoordinator.requestFlush(FlushReason.FOREGROUND_RECOVERY)
        }
    }

    fun newSession() {
        if (_uiState.value.running) return
        viewModelScope.launch {
            learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
            dropAgent()
            val fresh = SessionStore.fresh()
            _uiState.update { state ->
                val sessions = listOf(fresh) + state.sessions
                sessionStore.save(sessions)
                state.copy(
                    sessions = sessions,
                    activeSessionId = fresh.id,
                    turns = emptyList(),
                    error = null,
                )
            }
        }
    }

    fun startResearchSession(entity: String) {
        if (_uiState.value.running || entity.isBlank()) return
        viewModelScope.launch {
            learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
            dropAgent()
            val research = SessionStore.research(entity)
            _uiState.update { state ->
                val sessions = listOf(research) + state.sessions
                sessionStore.save(sessions)
                state.copy(
                    sessions = sessions,
                    activeSessionId = research.id,
                    turns = research.turns,
                    error = null,
                )
            }
        }
    }

    fun selectSession(id: String) {
        val target = _uiState.value.sessions.firstOrNull { it.id == id } ?: return
        if (id == _uiState.value.activeSessionId || _uiState.value.running) return
        viewModelScope.launch {
            learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
            dropAgent()
            _uiState.update {
                it.copy(activeSessionId = target.id, turns = target.turns, error = null)
            }
        }
    }

    fun deleteSession(id: String) {
        if (_uiState.value.running) return
        viewModelScope.launch {
            val state = _uiState.value
            val remaining = state.sessions.filterNot { it.id == id }.ifEmpty { listOf(SessionStore.fresh()) }
            val active = if (state.activeSessionId == id) remaining.first() else {
                remaining.firstOrNull { it.id == state.activeSessionId } ?: remaining.first()
            }
            if (active.id != state.activeSessionId) {
                learnCoordinator.flushAndJoin(FlushReason.NEW_SESSION)
                dropAgent()
            }
            sessionStore.save(remaining)
            _uiState.update {
                it.copy(sessions = remaining, activeSessionId = active.id, turns = active.turns)
            }
        }
    }

    fun renameSession(id: String, title: String) {
        val normalized = title.trim().replace(Regex("\\s+"), " ").take(40)
        if (normalized.isBlank()) return
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == id) it.copy(title = normalized, updatedAt = System.currentTimeMillis()) else it
            }
            sessionStore.save(sessions)
            state.copy(sessions = sessions)
        }
    }

    fun onForeground() {
        if (_uiState.value.apiKey.isNotBlank() && _uiState.value.memoryEnabled) {
            learnCoordinator.requestFlush(FlushReason.FOREGROUND_RECOVERY)
        }
        viewModelScope.launch { refreshMemory() }
    }

    fun onBackground() {
        learnCoordinator.requestFlush(FlushReason.BACKGROUND)
    }

    fun refresh() {
        viewModelScope.launch { refreshMemory() }
    }

    private suspend fun runTurn(
        input: String,
        captureMemory: Boolean = true,
        automaticRecall: Boolean = true,
        showUserTurn: Boolean = true,
    ) {
        val stateAtStart = _uiState.value
        val sessionId = stateAtStart.activeSessionId
        val taskScopeId = stateAtStart.memoryScopeId()
        val activeAgent = try {
            ensureAgent(stateAtStart, automaticRecall)
        } catch (error: Exception) {
            _uiState.update { it.copy(error = error.message ?: error.toString()) }
            return
        }
        _uiState.update {
            it.copy(
                running = true,
                error = null,
                turns = if (showUserTurn) {
                    OrderedTurnReducer.begin(it.turns, input)
                } else {
                    OrderedTurnReducer.beginContinuation(it.turns)
                },
            )
        }
        try {
            if (_uiState.value.memoryEnabled && captureMemory) {
                store.capture(
                    RawTurn(
                        GRAPH_ASSISTANT,
                        role = "user",
                        text = input,
                        sessionId = sessionId,
                        taskScopeId = taskScopeId,
                    ),
                )
            }
            val visible = runAgent(activeAgent, input)
            if (visible.isNotBlank() && _uiState.value.memoryEnabled && captureMemory) {
                store.capture(
                    RawTurn(
                        GRAPH_ASSISTANT,
                        role = "assistant",
                        text = visible,
                        sessionId = sessionId,
                        taskScopeId = taskScopeId,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentException) {
            _uiState.update { it.copy(error = error.message) }
        } catch (error: RelayLlmException) {
            _uiState.update { it.copy(error = error.message ?: error.toString()) }
        } catch (error: Exception) {
            _uiState.update { it.copy(error = error.message ?: error.toString()) }
        } finally {
            _uiState.update { it.copy(running = false, turns = OrderedTurnReducer.complete(it.turns)) }
            persistActiveSession()
            refreshMemory()
            if (_uiState.value.memoryEnabled && captureMemory) learnCoordinator.onTurnCompleted()
        }
    }

    private suspend fun runAgent(activeAgent: Agent, input: String): String {
        activeAgent.prompt(input).collect { event ->
            _uiState.update { it.copy(turns = OrderedTurnReducer.reduce(it.turns, event)) }
        }
        return _uiState.value.turns.lastOrNull { it.role == "assistant" }
            ?.let(OrderedTurnReducer::visibleProjection)
            .orEmpty()
    }

    private fun persistActiveSession() {
        _uiState.update { state ->
            val firstUserText = state.turns.firstOrNull { it.role == "user" }
                ?.items
                ?.filterIsInstance<relay.uikit.TurnItem.Text>()
                ?.firstOrNull()
                ?.text
                .orEmpty()
            val sessions = state.sessions.map { session ->
                if (session.id == state.activeSessionId) {
                    session.copy(
                        title = if (session.title == "新对话" && firstUserText.isNotBlank()) {
                            firstUserText.replace(Regex("\\s+"), " ").take(18)
                        } else {
                            session.title
                        },
                        updatedAt = System.currentTimeMillis(),
                        turns = state.turns,
                    )
                } else {
                    session
                }
            }.sortedByDescending { it.updatedAt }
            sessionStore.save(sessions)
            state.copy(sessions = sessions)
        }
    }

    private suspend fun flushLearn(reason: FlushReason) {
        learnMutex.withLock {
            val state = _uiState.value
            if (!state.memoryEnabled || state.apiKey.isBlank() || store.unconsumed(GRAPH_ASSISTANT).isEmpty()) {
                return@withLock
            }
            val activeRuntime = runtime ?: run {
                ensureAgent(state)
                runtime ?: return@withLock
            }
            _uiState.update { it.copy(learning = true) }
            try {
                if (reason == FlushReason.FOREGROUND_RECOVERY) {
                    activeRuntime.learn(GRAPH_ASSISTANT)
                } else {
                    activeRuntime.learn(GRAPH_ASSISTANT, state.activeSessionId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = "记忆学习失败：${error.message}") }
            } finally {
                _uiState.update { it.copy(learning = false) }
                refreshMemory()
            }
        }
    }

    private suspend fun refreshMemory() {
        val context = RecallContext(
            sessionId = _uiState.value.activeSessionId,
            taskScopeId = _uiState.value.memoryScopeId(),
        )
        val inventory = store.facts(GRAPH_ASSISTANT)
        val recallableKeys = store.facts(GRAPH_ASSISTANT, context).facts
            .map { Triple(it.s, it.p, it.o) }
            .toSet()
        val claims = store.claims(GRAPH_ASSISTANT)
        val pending = store.unconsumed(GRAPH_ASSISTANT).size
        val relations = inventory.facts.map { fact ->
            val visible = Triple(fact.s, fact.p, fact.o) in recallableKeys
            MemoryRelationUi(
                subject = fact.s,
                predicate = predicateLabel(fact.p),
                objectValue = fact.o,
                scopeLabel = MemoryVisibility.label(fact.scope, fact.state, fact.scopeId),
                recallable = visible,
                isolated = MemoryVisibility.isolated(fact.scope, fact.scopeId, context),
            )
        }
        val claimUi = claims.map { claim ->
            val visible = MemoryVisibility.recallable(claim.scope, claim.state, claim.scopeId, context)
            MemoryClaimUi(
                text = claim.text,
                confidence = claim.confidence,
                scopeLabel = MemoryVisibility.label(claim.scope, claim.state, claim.scopeId),
                recallable = visible,
                isolated = MemoryVisibility.isolated(claim.scope, claim.scopeId, context),
            )
        }
        val graphFacts = inventory.facts.filter { Triple(it.s, it.p, it.o) in recallableKeys }
        val names = graphFacts.flatMap { listOf(it.s, it.o) }.distinct()
        _uiState.update {
            it.copy(
                graph = GraphSpec(
                    title = "当前可用记忆",
                    nodes = names.map { name -> GraphNode(name, name) },
                    edges = graphFacts.map { fact ->
                        GraphEdge(fact.s, predicateLabel(fact.p), fact.o)
                    },
                    claims = claimUi.filter { it.recallable }.map { it.text },
                    focusId = names.firstOrNull(),
                    showPredicates = true,
                ),
                relations = relations,
                claims = claimUi,
                pendingRaw = pending,
            )
        }
    }

    private fun ensureAgent(
        state: AssistantUiState,
        automaticRecall: Boolean = true,
    ): Agent {
        if (
            agent != null &&
            runtime != null &&
            boundKey == state.apiKey &&
            boundMemoryEnabled == state.memoryEnabled &&
            boundAutomaticRecall == automaticRecall
        ) return agent!!
        dropAgent()
        boundKey = state.apiKey
        boundMemoryEnabled = state.memoryEnabled
        boundAutomaticRecall = automaticRecall
        val provider = DeepSeek.provider(state.apiKey, httpClient)
        val activeRuntime = MemoryRuntime(
            store = store,
            extractor = CloudTripleExtractor(provider),
            consolidator = AgentConsolidator(provider, store),
        )
        val recallContext = RecallContext(
            sessionId = state.activeSessionId,
            taskScopeId = state.memoryScopeId(),
        )
        runtime = activeRuntime
        agent = Agent(
            provider = provider,
            config = AgentConfig(
                model = DeepSeek.CHAT,
                systemPrompt = SYSTEM_PROMPT,
                maxTurns = 8,
                timeoutMillis = 90_000,
            ),
            tools = (if (state.memoryEnabled) {
                activeRuntime.dayTools(GRAPH_ASSISTANT, recallContext)
            } else {
                emptyList()
            }) +
                uiArtifactTools(artifacts, includeHtml = false),
            contextAugmenters = if (state.memoryEnabled && automaticRecall) {
                listOf(activeRuntime.recalling(GRAPH_ASSISTANT, recallContext))
            } else {
                emptyList()
            },
            beforeToolCall = { call ->
                ArtifactGroundingGate.check(call, groundingEvidence())
            },
        )
        agent!!.state.messages = state.turns.toAgentTranscript()
        return agent!!
    }

    private fun dropAgent() {
        agent = null
        runtime = null
        boundKey = null
        boundMemoryEnabled = null
        boundAutomaticRecall = null
    }

    private fun groundingEvidence(): String = buildString {
        _uiState.value.turns
            .filter { it.role == "user" }
            .flatMap { it.items }
            .filterIsInstance<relay.uikit.TurnItem.Text>()
            .forEach { appendLine(it.text) }
        _uiState.value.relations.filter { it.recallable }.forEach {
            appendLine("${it.subject} ${it.predicate} ${it.objectValue}")
        }
        _uiState.value.claims.filter { it.recallable }.forEach { appendLine(it.text) }
    }

    override fun onCleared() {
        inFlight?.cancel()
        learnCoordinator.cancel()
        store.close()
        super.onCleared()
    }
}

private fun AssistantUiState.memoryScopeId(): String =
    sessions.firstOrNull { it.id == activeSessionId }?.effectiveMemoryScopeId ?: activeSessionId

private const val SYSTEM_PROMPT =
    "你是手机上的个人助理。先使用上下文中的用户记忆，不足时再查询。" +
        "事实不确定就明确说明。需要结构化呈现时调用原生 UI 工具；不要生成 HTML。" +
        "需要用户在多个明确选项中做决定时，使用 render_choice_form；一组相关问题放在同一表单中，" +
        "taskAnchor 必须准确概括本轮用户的原始任务，等待用户提交后只围绕该任务继续。" +
        "生成图表或 Markdown 产物时，只能使用本轮用户原文或已召回记忆中的数值；" +
        "派生数值必须能由原始数据直接计算，缺少原始数据时先向用户询问，禁止补造示例数据。" +
        "用中文回答，简洁、具体、可执行。"
