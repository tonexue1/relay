package relay.orchestra

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import relay.orchestra.call.AgentTool

/**
 * Fixed-order Calls. No lead LLM: each step is an [AgentTool], and the next
 * prompt is the previous [WorkerReturn] text or resolved artifact content.
 */
class Pipeline(
    private val steps: List<WorkerSpec>,
    private val artifacts: ArtifactStore,
    private val ledger: TeamLedger,
) {
    init {
        require(steps.isNotEmpty()) { "Pipeline needs at least one step" }
    }

    fun prompt(input: String): Flow<TeamEvent> = channelFlow {
        val events = Channel<TeamEvent>(Channel.UNLIMITED)
        val childJob = launch {
            for (child in events) send(child)
        }
        try {
            runSteps(input, events)
        } finally {
            events.close()
            childJob.join()
        }
    }

    suspend fun run(input: String): WorkerReturn {
        val events = Channel<TeamEvent>(Channel.UNLIMITED)
        return runSteps(input, events)
    }

    private suspend fun runSteps(
        input: String,
        events: SendChannel<TeamEvent>,
    ): WorkerReturn {
        var task = input
        var last: WorkerReturn? = null
        for (spec in steps) {
            val tool = AgentTool(
                workerId = spec.id,
                spawn = spec.spawn,
                artifacts = artifacts,
                ledger = ledger,
                events = events,
                description = spec.description,
            )
            val json = tool.execute(toolCallId = spec.id, argumentsJson = taskJson(task))
            val ret = WorkerReturn.fromJson(json)
            last = ret
            task = nextPrompt(ret)
        }
        return last ?: error("Pipeline produced no result")
    }

    private suspend fun nextPrompt(ret: WorkerReturn): String {
        val findings = ret.findings.joinToString("\n")
        if (ret.artifactRefs.isEmpty()) {
            return findings.ifBlank { ret.toJson() }
        }
        val bodies = buildString {
            for ((i, ref) in ret.artifactRefs.withIndex()) {
                if (i > 0) append("\n\n")
                append(ref.uri)
                append('\n')
                append(artifacts.get(ref))
            }
        }
        return buildString {
            if (findings.isNotBlank()) {
                append(findings)
                append("\n\n")
            }
            append(bodies)
        }
    }

    private fun taskJson(task: String): String =
        OrchestraJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("task", JsonPrimitive(task)) },
        )
}
