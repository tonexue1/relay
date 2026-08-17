package relay.orchestra.call

import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.Agent
import relay.agent.AgentEvent
import relay.agent.Tool
import relay.llm.model.Role
import relay.llm.model.ToolDef
import relay.orchestra.ArtifactStore
import relay.orchestra.Assignment
import relay.orchestra.AssignmentStatus
import relay.orchestra.OrchestraJson
import relay.orchestra.TeamBudgetExceeded
import relay.orchestra.TeamEvent
import relay.orchestra.TeamLedger
import relay.orchestra.WorkerReturn
import relay.orchestra.WorkerStatus
import relay.orchestra.toJson

/**
 * Worker as a [Tool]. Each [execute] spawns a fresh [Agent], bubbles its events
 * through [events], and returns a short [WorkerReturn] JSON to the lead.
 */
internal class AgentTool(
    val workerId: String,
    private val spawn: () -> Agent,
    private val artifacts: ArtifactStore,
    private val ledger: TeamLedger,
    private val events: SendChannel<TeamEvent>,
    description: String = "Delegate a self-contained task to $workerId",
    private val inlineLimit: Int = INLINE_LIMIT,
) : Tool {

    override val def: ToolDef = ToolDef(
        name = workerId,
        description = description,
        parameters = taskSchema,
    )

    override suspend fun execute(toolCallId: String, argumentsJson: String): String {
        val task = parseTask(argumentsJson)
        events.send(TeamEvent.CallStarted(workerId, task))
        try {
            ledger.claimWorker()
        } catch (e: TeamBudgetExceeded) {
            val failed = WorkerReturn(
                status = WorkerStatus.failed,
                unknowns = listOf(e.message ?: "budget exhausted"),
            )
            events.send(TeamEvent.CallEnded(workerId, failed))
            throw e
        }

        val result = try {
            val text = collectWorker(task)
            if (text.isBlank()) {
                WorkerReturn(
                    status = WorkerStatus.failed,
                    unknowns = listOf("worker produced no final answer"),
                )
            } else {
                pack(text, toolCallId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WorkerReturn(
                status = WorkerStatus.failed,
                unknowns = listOf(e.message ?: e.toString()),
            )
        }

        ledger.record(
            Assignment(
                workerId = workerId,
                task = task,
                status = result.status.toAssignmentStatus(),
                returnSummary = result.findings.joinToString(" ").ifBlank { null },
                artifacts = result.artifactRefs,
            ),
        )
        events.send(TeamEvent.CallEnded(workerId, result))
        return result.toJson()
    }

    private suspend fun collectWorker(task: String): String {
        val agent = spawn()
        var text: String? = null
        agent.prompt(task).collect { event ->
            events.send(TeamEvent.CallChild(workerId, event))
            if (event is AgentEvent.MessageEnd &&
                event.message.role == Role.ASSISTANT &&
                event.message.toolCalls.isEmpty() &&
                !event.message.content.isNullOrBlank()
            ) {
                text = event.message.content
            }
        }
        return text.orEmpty()
    }

    private suspend fun pack(text: String, toolCallId: String): WorkerReturn {
        val findings = findingsFrom(text)
        if (text.length <= inlineLimit) {
            return WorkerReturn(status = WorkerStatus.ok, findings = findings)
        }
        val ref = artifacts.put(ledger.runId, artifactName(workerId, toolCallId), text)
        return WorkerReturn(
            status = WorkerStatus.ok,
            findings = findings,
            artifactRefs = listOf(ref),
        )
    }

    companion object {
        const val INLINE_LIMIT = 400

        /** Parallel Calls share [workerId]; the tool-call id keeps artifacts from clobbering. */
        fun artifactName(workerId: String, toolCallId: String): String {
            val call = toolCallId.replace('/', '_').ifBlank { "call" }
            return "$workerId/$call"
        }

        val taskSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("task") {
                    put("type", "string")
                    put("description", "Self-contained task for this worker")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("task")) }
        }
    }
}

internal fun parseTask(argumentsJson: String): String {
    val trimmed = argumentsJson.trim()
    if (trimmed.startsWith("{")) {
        runCatching {
            val obj = OrchestraJson.parseToJsonElement(trimmed).jsonObject
            obj["task"]?.jsonPrimitive?.contentOrNull
                ?: obj["query"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.let { return it }
    }
    return argumentsJson
}

internal fun findingsFrom(text: String): List<String> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    // Models often recap the search first; the findings are at the tail.
    val short = lines.takeLast(3).map { it.take(200) }
    return short.ifEmpty { listOf(text.take(200)).filter { it.isNotEmpty() } }
}

private fun WorkerStatus.toAssignmentStatus(): AssignmentStatus = when (this) {
    WorkerStatus.ok -> AssignmentStatus.ok
    WorkerStatus.partial -> AssignmentStatus.partial
    WorkerStatus.failed -> AssignmentStatus.failed
}
