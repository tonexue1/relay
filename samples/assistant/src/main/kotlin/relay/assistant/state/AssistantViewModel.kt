package relay.assistant.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.AgentException
import relay.agent.FunTool
import relay.agent.Tool
import relay.artifacts.FileArtifactRepository
import relay.assistant.BuildConfig
import relay.assistant.artifact.ArtifactGroundingGate
import relay.assistant.session.AssistantSession
import relay.assistant.session.SessionStore
import relay.assistant.session.PendingInteractionSnapshot
import relay.assistant.session.toAgentTranscript
import relay.assistant.time.currentTimeContextAugmenter
import relay.llm.RelayLlmException
import relay.llm.provider.DeepSeek
import relay.ondevice.OnDeviceProvider
import relay.ondevice.ToolCallEvaluation
import relay.ondevice.cpu.CpuTopology
import relay.ondevice.engine.JniLlamaEngine
import relay.ondevice.model.ModelStore
import relay.ondevice.model.ModelSpec
import relay.ondevice.model.OnDeviceModels
import relay.assistant.memory.OWNER_USER
import relay.assistant.memory.SPACE_ASSISTANT
import relay.assistant.memory.captureTurn
import relay.assistant.memory.assistantEmbedder
import relay.assistant.memory.ensureAssistantSpace
import relay.assistant.memory.rememberTools
import relay.memory.RecallContext
import relay.memory.agent.recallingFacts
import relay.memory.api.ClockDomain
import relay.memory.api.MemoryKind
import relay.memory.api.MemoryRuntime
import relay.memory.engine.SqliteLedgerRuntime
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

enum class InferenceMode { CLOUD, ON_DEVICE }

data class AssistantUiState(
    val apiKey: String = "",
    val inferenceMode: InferenceMode = InferenceMode.CLOUD,
    val selectedOnDeviceModelId: String = OnDeviceModels.default.id,
    val onDeviceModelReady: Boolean = false,
    val onDeviceModelLoaded: Boolean = false,
    val onDeviceBusy: Boolean = false,
    val onDeviceDownloadProgress: Float = 0f,
    val onDeviceEvaluating: Boolean = false,
    val onDeviceEvaluationResult: ToolCallEvaluation.Result? = null,
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
        get() = !running && !onDeviceEvaluating && when (inferenceMode) {
            InferenceMode.CLOUD -> apiKey.isNotBlank()
            InferenceMode.ON_DEVICE -> onDeviceModelLoaded
        }
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
    private val runtime: MemoryRuntime = SqliteLedgerRuntime(
        application,
        File(application.filesDir, "memory-ledger.db"),
    )
    private val artifacts = FileArtifactRepository(File(application.filesDir, "ui-artifacts"))
    private val modelStore = ModelStore(
        rootDir = File(application.filesDir, "models"),
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build(),
    )
    private val onDeviceEngine = JniLlamaEngine()
    private var onDeviceSpec: ModelSpec = OnDeviceModels.default
    private var onDeviceProvider = OnDeviceProvider(onDeviceEngine, onDeviceSpec)
    private var agent: Agent? = null
    private var boundKey: String? = null
    private var boundInferenceMode: InferenceMode? = null
    private var boundMemoryEnabled: Boolean? = null
    private var boundAutomaticRecall: Boolean? = null
    private var inFlight: Job? = null
    private var lastRawEventIds: List<String> = emptyList()

    init {
        sessionStore.save(initialSessions)
        viewModelScope.launch {
            runtime.ensureAssistantSpace()
            refreshMemory()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ready = modelStore.isReady(onDeviceSpec)
            _uiState.update { it.copy(onDeviceModelReady = ready) }
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
        if (_uiState.value.running) return
        var submittedMessage: String? = null
        var interactionCallId: String? = null
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
                                    interactionCallId = widget.callId
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
            val activeAgent = agent ?: runCatching {
                ensureAgent(_uiState.value, automaticRecall = false)
            }.getOrNull()
            if (activeAgent != null && activeAgent.state.pendingInteraction?.call?.id == interactionCallId) {
                inFlight = viewModelScope.launch { resumeInteraction(activeAgent, interactionCallId!!, it) }
            } else {
                inFlight = viewModelScope.launch {
                    runTurn(input = it, captureMemory = false, automaticRecall = false, showUserTurn = false)
                }
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
            dropAgent()
        }
    }

    fun onMemoryEnabledChange(value: Boolean) {
        sessionStore.saveMemoryEnabled(value)
        _uiState.update { it.copy(memoryEnabled = value) }
        dropAgent()
    }

    fun onInferenceModeChange(value: InferenceMode) {
        if (value == InferenceMode.ON_DEVICE && !_uiState.value.onDeviceModelLoaded) return
        _uiState.update { it.copy(inferenceMode = value, error = null) }
        dropAgent()
    }

    fun selectOnDeviceModel(id: String) {
        val next = OnDeviceModels.selectableById(id) ?: return
        val state = _uiState.value
        if (state.running || state.onDeviceBusy || next.id == onDeviceSpec.id) return
        viewModelScope.launch {
            _uiState.update { it.copy(onDeviceBusy = true, error = null) }
            try {
                if (onDeviceEngine.isLoaded) withContext(Dispatchers.IO) { onDeviceProvider.unload() }
                val ready = withContext(Dispatchers.IO) { modelStore.isReady(next) }
                onDeviceSpec = next
                onDeviceProvider = OnDeviceProvider(onDeviceEngine, next)
                _uiState.update {
                    it.copy(
                        inferenceMode = InferenceMode.CLOUD,
                        selectedOnDeviceModelId = next.id,
                        onDeviceModelReady = ready,
                        onDeviceModelLoaded = false,
                        onDeviceBusy = false,
                        onDeviceDownloadProgress = 0f,
                    )
                }
                dropAgent()
            } catch (error: Exception) {
                _uiState.update { it.copy(onDeviceBusy = false, error = error.message ?: "模型切换失败") }
            }
        }
    }

    fun downloadOnDeviceModel() {
        if (_uiState.value.onDeviceBusy || _uiState.value.onDeviceModelReady) return
        viewModelScope.launch {
            _uiState.update { it.copy(onDeviceBusy = true, error = null, onDeviceDownloadProgress = 0f) }
            try {
                modelStore.ensurePresent(onDeviceSpec) { downloaded, total ->
                    _uiState.update {
                        it.copy(onDeviceDownloadProgress = if (total > 0) downloaded.toFloat() / total else 0f)
                    }
                }
                _uiState.update { it.copy(onDeviceBusy = false, onDeviceModelReady = true, onDeviceDownloadProgress = 1f) }
            } catch (error: Exception) {
                _uiState.update { it.copy(onDeviceBusy = false, error = error.message ?: "模型下载失败") }
            }
        }
    }

    fun loadOnDeviceModel() {
        if (_uiState.value.onDeviceBusy || !_uiState.value.onDeviceModelReady || onDeviceEngine.isLoaded) return
        viewModelScope.launch {
            _uiState.update { it.copy(onDeviceBusy = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    onDeviceProvider.load(modelStore.localFile(onDeviceSpec).absolutePath, nCtx = 2048, cpu = CpuTopology.plan())
                }
                _uiState.update { it.copy(onDeviceBusy = false, onDeviceModelLoaded = true) }
            } catch (error: Exception) {
                _uiState.update { it.copy(onDeviceBusy = false, error = error.message ?: "模型加载失败") }
            }
        }
    }

    fun runOnDeviceToolCallEvaluation() {
        if (
            _uiState.value.onDeviceBusy ||
            _uiState.value.onDeviceEvaluating ||
            !onDeviceEngine.isLoaded
        ) return
        viewModelScope.launch {
            _uiState.update { it.copy(onDeviceEvaluating = true, error = null, onDeviceEvaluationResult = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    ToolCallEvaluation.run(
                        provider = onDeviceProvider,
                        model = onDeviceSpec.id,
                        tools = onDeviceTools().map(Tool::def),
                    )
                }
                _uiState.update { it.copy(onDeviceEvaluating = false, onDeviceEvaluationResult = result) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        onDeviceEvaluating = false,
                        error = error.message ?: "端侧工具调用评测失败",
                    )
                }
            }
        }
    }

    fun newSession() {
        if (_uiState.value.running) return
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
        viewModelScope.launch { refreshMemory() }
    }

    fun startResearchSession(entity: String) {
        if (_uiState.value.running || entity.isBlank()) return
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

    fun selectSession(id: String) {
        val target = _uiState.value.sessions.firstOrNull { it.id == id } ?: return
        if (id == _uiState.value.activeSessionId || _uiState.value.running) return
        dropAgent()
        _uiState.update {
            it.copy(activeSessionId = target.id, turns = target.turns, error = null)
        }
        viewModelScope.launch { refreshMemory() }
    }

    fun deleteSession(id: String) {
        if (_uiState.value.running) return
        val state = _uiState.value
        val remaining = state.sessions.filterNot { it.id == id }.ifEmpty { listOf(SessionStore.fresh()) }
        val active = if (state.activeSessionId == id) remaining.first() else {
            remaining.firstOrNull { it.id == state.activeSessionId } ?: remaining.first()
        }
        if (active.id != state.activeSessionId) {
            dropAgent()
        }
        sessionStore.save(remaining)
        _uiState.update {
            it.copy(sessions = remaining, activeSessionId = active.id, turns = active.turns)
        }
        viewModelScope.launch { refreshMemory() }
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
        viewModelScope.launch { refreshMemory() }
    }

    fun onBackground() {
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
                lastRawEventIds = listOf(
                    runtime.captureTurn(
                        spaceId = SPACE_ASSISTANT,
                        ownerId = OWNER_USER,
                        domain = ClockDomain.WALL_CLOCK,
                        role = "user",
                        text = input,
                        sessionId = sessionId,
                        taskScopeId = taskScopeId,
                    ),
                )
            }
            val visible = runAgent(activeAgent, input)
            if (visible.isNotBlank() && _uiState.value.memoryEnabled && captureMemory) {
                runtime.captureTurn(
                    spaceId = SPACE_ASSISTANT,
                    ownerId = OWNER_USER,
                    domain = ClockDomain.WALL_CLOCK,
                    role = "assistant",
                    text = visible,
                    sessionId = sessionId,
                    taskScopeId = taskScopeId,
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
            _uiState.update {
                it.copy(
                    running = false,
                    turns = if (activeAgent.state.pendingInteraction == null) OrderedTurnReducer.complete(it.turns) else it.turns,
                )
            }
            persistActiveSession()
            refreshMemory()
        }
    }

    private suspend fun resumeInteraction(activeAgent: Agent, callId: String, result: String) {
        _uiState.update { it.copy(running = true, error = null) }
        try {
            activeAgent.resumeInteraction(callId, result).collect { event ->
                _uiState.update { it.copy(turns = OrderedTurnReducer.reduce(it.turns, event)) }
            }
        } catch (error: Exception) {
            _uiState.update { it.copy(error = error.message ?: error.toString()) }
        } finally {
            _uiState.update { it.copy(running = false, turns = OrderedTurnReducer.complete(it.turns)) }
            persistActiveSession()
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
                    val pending = agent?.state?.pendingInteraction?.let { interaction ->
                        PendingInteractionSnapshot(interaction.call, agent!!.state.messages)
                    }
                    session.copy(
                        title = if (session.title == "新对话" && firstUserText.isNotBlank()) {
                            firstUserText.replace(Regex("\\s+"), " ").take(18)
                        } else {
                            session.title
                        },
                        updatedAt = System.currentTimeMillis(),
                        turns = state.turns,
                        pendingInteraction = pending,
                    )
                } else {
                    session
                }
            }.sortedByDescending { it.updatedAt }
            sessionStore.save(sessions)
            state.copy(sessions = sessions)
        }
    }

    private suspend fun refreshMemory() {
        val context = RecallContext(
            sessionId = _uiState.value.activeSessionId,
            taskScopeId = _uiState.value.memoryScopeId(),
        )
        val items = runtime.listItems(SPACE_ASSISTANT, OWNER_USER)
        val pending = runtime.pendingRawCount(SPACE_ASSISTANT)
        val relations = items.filter { it.kind == MemoryKind.STATE }.map { item ->
            val visible = MemoryVisibility.recallable(item.scope, item.lifecycle, item.scopeId, context)
            MemoryRelationUi(
                subject = item.ownerId,
                predicate = item.fieldId.orEmpty(),
                objectValue = item.text,
                scopeLabel = MemoryVisibility.label(item.scope, item.lifecycle, item.scopeId),
                recallable = visible,
                isolated = MemoryVisibility.isolated(item.scope, item.scopeId, context),
            )
        }
        val claimUi = items.filter { it.kind != MemoryKind.STATE }.map { item ->
            val visible = MemoryVisibility.recallable(item.scope, item.lifecycle, item.scopeId, context)
            MemoryClaimUi(
                text = item.text,
                confidence = 1.0,
                scopeLabel = MemoryVisibility.label(item.scope, item.lifecycle, item.scopeId),
                recallable = visible,
                isolated = MemoryVisibility.isolated(item.scope, item.scopeId, context),
            )
        }
        val graphFacts = relations.filter { it.recallable }
        val names = graphFacts.flatMap { listOf(it.subject, it.objectValue) }.distinct()
        _uiState.update {
            it.copy(
                graph = GraphSpec(
                    title = "当前可用记忆",
                    nodes = names.map { name -> GraphNode(name, name) },
                    edges = graphFacts.map { fact ->
                        GraphEdge(fact.subject, fact.predicate, fact.objectValue)
                    },
                    claims = claimUi.filter { claim -> claim.recallable }.map { claim -> claim.text },
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
            boundKey == state.apiKey &&
            boundInferenceMode == state.inferenceMode &&
            boundMemoryEnabled == state.memoryEnabled &&
            boundAutomaticRecall == automaticRecall
        ) return agent!!
        dropAgent()
        boundKey = state.apiKey
        boundInferenceMode = state.inferenceMode
        boundMemoryEnabled = state.memoryEnabled
        boundAutomaticRecall = automaticRecall
        val onDevice = state.inferenceMode == InferenceMode.ON_DEVICE
        check(!onDevice || onDeviceEngine.isLoaded) { "请先加载端侧模型" }
        val provider = if (onDevice) onDeviceProvider else DeepSeek.provider(state.apiKey, httpClient)
        val embedder = assistantEmbedder(httpClient)
        agent = Agent(
            provider = provider,
            config = AgentConfig(
                model = if (onDevice) onDeviceSpec.id else DeepSeek.CHAT,
                systemPrompt = if (onDevice) ON_DEVICE_SYSTEM_PROMPT else SYSTEM_PROMPT,
                maxToolBatches = 8,
                timeoutMillis = 90_000,
            ),
            tools = if (onDevice) {
                onDeviceTools()
            } else {
                (if (state.memoryEnabled) {
                    runtime.rememberTools(
                        spaceId = SPACE_ASSISTANT,
                        ownerId = OWNER_USER,
                        rawEventIds = { lastRawEventIds },
                        embedder = embedder,
                    )
                } else {
                    emptyList()
                }) + uiArtifactTools(artifacts, includeHtml = false)
            },
            contextAugmenters = buildList {
                add(currentTimeContextAugmenter())
                if (!onDevice && state.memoryEnabled && automaticRecall) {
                    add(runtime.recallingFacts(
                        spaceId = SPACE_ASSISTANT,
                        ownerId = OWNER_USER,
                        embedder = embedder,
                    ))
                }
            },
            beforeToolCall = { call ->
                ArtifactGroundingGate.check(call, groundingEvidence())
            },
        )
        val checkpoint = state.activeSession?.pendingInteraction
        agent!!.state.messages = checkpoint?.messages ?: state.turns.toAgentTranscript()
        checkpoint?.let { agent!!.restoreInteraction(it.call) }
        return agent!!
    }

    private fun dropAgent() {
        agent = null
        boundKey = null
        boundInferenceMode = null
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

    private fun onDeviceTools(): List<Tool> = listOf(
        FunTool(
            name = "get_current_time",
            description = "获取设备当前本地时间。时间、日期或相对日期问题必须调用此工具。",
        ) {
            Instant.now().atZone(ZoneId.systemDefault()).toString()
        },
    )

    override fun onCleared() {
        inFlight?.cancel()
        onDeviceEngine.cancel()
        Thread({ onDeviceProvider.unload() }, "assistant-ondevice-unload").apply {
            isDaemon = true
            start()
        }
        (runtime as? SqliteLedgerRuntime)?.close()
        super.onCleared()
    }
}

private fun AssistantUiState.memoryScopeId(): String =
    sessions.firstOrNull { it.id == activeSessionId }?.effectiveMemoryScopeId ?: activeSessionId

private const val SYSTEM_PROMPT =
    "你是手机上的个人助理。每次请求都会提供当前本地时间；所有今天、明天等相对日期必须以该时间计算。" +
        "历史消息中的临时安排若已过期，绝不能作为今天待办；日期不明时先澄清。先使用上下文中垫入的用户记忆。" +
        "用户说出稳定事实时调用 remember 写成一句话。" +
        "事实变了先 search_memories，再 remember 写转变句并带上旧记忆 id。一次性打算不要记。" +
        "事实不确定就明确说明。需要结构化呈现时调用原生 UI 工具；不要生成 HTML。" +
        "需要用户在多个明确选项中做决定时，使用 render_choice_form；一组相关问题放在同一表单中，" +
        "taskAnchor 必须准确概括本轮用户的原始任务，等待用户提交后只围绕该任务继续。" +
        "生成图表或 Markdown 产物时，只能使用本轮用户原文或已召回记忆中的数值；" +
        "派生数值必须能由原始数据直接计算，缺少原始数据时先向用户询问，禁止补造示例数据。" +
        "用中文回答，简洁、具体、可执行。"

private const val ON_DEVICE_SYSTEM_PROMPT =
    "你是手机上的离线个人助理。当前为端侧模型模式，不能联网或访问长期记忆。" +
        "遇到时间、日期或相对日期问题时，必须调用 get_current_time。" +
        "用中文回答，简洁、具体、可执行。"
