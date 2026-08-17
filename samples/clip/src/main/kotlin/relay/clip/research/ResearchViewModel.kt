package relay.clip.research

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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
import relay.agent.ToolExecutionMode
import relay.clip.BuildConfig
import relay.clip.search.WebSearch
import relay.llm.RelayLlmException
import relay.llm.interceptor.RetryInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.model.Role
import relay.llm.provider.DeepSeek
import relay.orchestra.InMemoryArtifactStore
import relay.orchestra.Supervisor
import relay.orchestra.TeamBudgetExceeded
import relay.orchestra.TeamEvent
import relay.orchestra.TeamLedger
import relay.orchestra.WorkerSpec
import relay.orchestra.WorkerStatus

data class ScoutCard(
    val workerId: String,
    val task: String,
    val status: String,
    val findings: List<String> = emptyList(),
)

data class ResearchUiState(
    val source: String = "",
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val bochaKey: String = BuildConfig.BOCHA_API_KEY,
    val running: Boolean = false,
    val leadText: String = "",
    val scouts: List<ScoutCard> = emptyList(),
    val toolLog: List<String> = emptyList(),
    val error: String? = null,
) {
    val canRun: Boolean get() =
        !running && source.isNotBlank() && apiKey.isNotBlank() && bochaKey.isNotBlank()
}

class ResearchViewModel(app: Application) : AndroidViewModel(app) {

    private val trace = ResearchTrace(File(app.filesDir, ResearchTrace.FILE_NAME))

    private val llmHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val toolHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(ResearchUiState())
    val uiState: StateFlow<ResearchUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null
    private var committedLead: String = ""
    private var leadDraft: String = ""
    private var leadIsToolTurn: Boolean = false

    fun setSource(text: String) {
        if (_uiState.value.source == text) return
        _uiState.update { it.copy(source = text) }
    }

    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value) }

    fun onBochaKeyChange(value: String) = _uiState.update { it.copy(bochaKey = value) }

    fun research() {
        val state = _uiState.value
        if (!state.canRun) return
        val topic = state.source.trim()
        val artifacts = InMemoryArtifactStore()
        val ledger = TeamLedger(
            runId = "clip-${System.currentTimeMillis()}",
            goal = topic,
            maxWorkers = 4,
        )
        val search = WebSearch(toolHttp, state.bochaKey.trim())
        val cloud = DeepSeek.provider(
            apiKey = state.apiKey.trim(),
            httpClient = llmHttp,
        ).intercept(RetryInterceptor(maxAttempts = 3))

        val team = Supervisor(
            spawnLead = { workerTools ->
                Agent(
                    provider = cloud,
                    config = AgentConfig(
                        model = DeepSeek.CHAT,
                        systemPrompt = LEAD_SYSTEM,
                        maxTurns = 10,
                        timeoutMillis = 120_000,
                    ),
                    tools = workerTools + readArtifactTool(artifacts),
                )
            },
            workers = listOf(
                WorkerSpec(
                    id = "scout",
                    description = SCOUT_DESCRIPTION,
                    spawn = {
                        Agent(
                            provider = cloud,
                            config = AgentConfig(
                                model = DeepSeek.CHAT,
                                systemPrompt = SCOUT_SYSTEM,
                                maxTurns = 6,
                                toolExecution = ToolExecutionMode.Sequential,
                                timeoutMillis = 90_000,
                            ),
                            tools = scoutTools(search),
                        )
                    },
                ),
            ),
            artifacts = artifacts,
            ledger = ledger,
        )

        committedLead = ""
        leadDraft = ""
        leadIsToolTurn = false
        trace.reset(topic)
        _uiState.update {
            it.copy(running = true, leadText = "", scouts = emptyList(), toolLog = emptyList(), error = null)
        }
        inFlight = viewModelScope.launch {
            try {
                team.prompt(topic).collect(::onTeamEvent)
                trace.line("flow completed leadChars=${_uiState.value.leadText.length} scouts=${_uiState.value.scouts.size}")
            } catch (e: CancellationException) {
                trace.line("CANCEL")
                _uiState.update { it.copy(error = "已取消") }
                throw e
            } catch (e: TeamBudgetExceeded) {
                trace.fail(e)
                _uiState.update { it.copy(error = e.message) }
            } catch (e: RelayLlmException) {
                trace.fail(e)
                _uiState.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                trace.fail(e)
                _uiState.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                trace.done()
                _uiState.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
    }

    private fun onTeamEvent(event: TeamEvent) {
        when (event) {
            is TeamEvent.Lead -> when (val child = event.event) {
                is AgentEvent.MessageStart -> {
                    if (child.message.role == Role.ASSISTANT) {
                        leadDraft = ""
                        leadIsToolTurn = false
                    }
                }
                is AgentEvent.MessageUpdate -> when (val chunk = child.chunk) {
                    is ChatChunk.ToolCalls -> {
                        leadIsToolTurn = true
                        val d = chunk.delta
                        trace.line("lead delta tool idx=${d.index} id=${d.id} name=${d.name} args=${d.argumentsDelta?.take(200)}")
                        _uiState.update { it.copy(leadText = committedLead) }
                    }
                    is ChatChunk.Text -> if (!leadIsToolTurn) {
                        leadDraft += chunk.delta
                        _uiState.update { it.copy(leadText = leadDraft) }
                    }
                    is ChatChunk.Done -> trace.line("lead done reason=${chunk.finishReason} usage=${chunk.usage}")
                    else -> Unit
                }
                is AgentEvent.MessageEnd -> {
                    val msg = child.message
                    if (msg.role == Role.ASSISTANT) {
                        trace.line(
                            "lead msgEnd tools=${msg.toolCalls.joinToString { it.name + it.argumentsJson.take(120) }} " +
                                "text=${msg.content?.take(300)}",
                        )
                    }
                    if (msg.role != Role.ASSISTANT) {
                        Unit
                    } else if (msg.toolCalls.isEmpty() && !msg.content.isNullOrBlank()) {
                        committedLead = msg.content.orEmpty()
                        _uiState.update { it.copy(leadText = committedLead) }
                    } else {
                        _uiState.update { it.copy(leadText = committedLead) }
                    }
                }
                is AgentEvent.ToolExecutionStart -> {
                    trace.line("lead → ${child.call.name} ${child.call.argumentsJson}")
                    appendToolLog("lead → ${child.call.name} ${child.call.argumentsJson.take(80)}")
                }
                is AgentEvent.ToolExecutionEnd -> {
                    trace.line(
                        "lead ← ${child.call.name} err=${child.isError} ${child.result}",
                    )
                    appendToolLog(
                        "lead ← ${child.call.name} " +
                            (if (child.isError) "ERR " else "ok ") +
                            child.result.take(120),
                    )
                }
                else -> Unit
            }
            is TeamEvent.CallStarted -> {
                trace.line("CallStarted ${event.workerId} task=${event.task}")
                _uiState.update {
                    it.copy(scouts = it.scouts + ScoutCard(event.workerId, event.task, "running"))
                }
            }
            is TeamEvent.CallEnded -> {
                trace.line(
                    "CallEnded ${event.workerId} status=${event.result.status} " +
                        "findings=${event.result.findings} unknowns=${event.result.unknowns} " +
                        "refs=${event.result.artifactRefs}",
                )
                _uiState.update { state ->
                val idx = state.scouts.indexOfLast {
                    it.workerId == event.workerId && it.status == "running"
                }
                if (idx < 0) return@update state
                val status = when (event.result.status) {
                    WorkerStatus.ok -> "ok"
                    WorkerStatus.partial -> "partial"
                    WorkerStatus.failed -> "failed"
                }
                val next = state.scouts.toMutableList()
                next[idx] = next[idx].copy(
                    status = status,
                    findings = event.result.findings.ifEmpty { event.result.unknowns },
                )
                state.copy(scouts = next)
                }
            }
            is TeamEvent.CallChild -> when (val child = event.event) {
                is AgentEvent.MessageUpdate -> when (val chunk = child.chunk) {
                    is ChatChunk.ToolCalls -> {
                        val d = chunk.delta
                        trace.line("${event.workerId} delta tool idx=${d.index} id=${d.id} name=${d.name} args=${d.argumentsDelta?.take(200)}")
                    }
                    is ChatChunk.Done -> trace.line("${event.workerId} done reason=${chunk.finishReason}")
                    else -> Unit
                }
                is AgentEvent.MessageEnd -> {
                    val msg = child.message
                    if (msg.role == Role.ASSISTANT) {
                        trace.line(
                            "${event.workerId} msgEnd tools=${msg.toolCalls.joinToString { it.name + it.argumentsJson.take(160) }} " +
                                "text=${msg.content?.take(300)}",
                        )
                    }
                }
                is AgentEvent.ToolExecutionStart -> {
                    trace.line("${event.workerId} → ${child.call.name} ${child.call.argumentsJson}")
                    appendToolLog("${event.workerId} → ${child.call.name} ${child.call.argumentsJson.take(80)}")
                }
                is AgentEvent.ToolExecutionEnd -> {
                    trace.line("${event.workerId} ← ${child.call.name} err=${child.isError} ${child.result}")
                    appendToolLog(
                        "${event.workerId} ← ${child.call.name} " +
                            (if (child.isError) "ERR " else "ok ") +
                            child.result.take(120),
                    )
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun appendToolLog(line: String) {
        _uiState.update { it.copy(toolLog = (it.toolLog + line).takeLast(30)) }
    }
}
