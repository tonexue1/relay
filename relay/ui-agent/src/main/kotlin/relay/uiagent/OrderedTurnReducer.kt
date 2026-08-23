package relay.uiagent

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import relay.agent.AgentEvent
import relay.artifacts.ArtifactRef
import relay.llm.model.ChatChunk
import relay.uikit.FileSpec
import relay.uikit.FallbackSpec
import relay.uikit.WidgetSpec

@Serializable
data class ChatTurn(
    val id: String,
    val role: String,
    val items: List<TurnItem>,
    val complete: Boolean = true,
)

@Serializable
sealed interface TurnItem {
    val id: String

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: String,
    ) : TurnItem

    @Serializable
    @SerialName("process")
    data class Process(
        override val id: String,
        val callId: String,
        val label: String,
        val argumentsSummary: String,
        val status: ProcessStatus = ProcessStatus.RUNNING,
        val resultSummary: String = "",
    ) : TurnItem

    @Serializable
    @SerialName("widget")
    data class Widget(
        override val id: String,
        val callId: String,
        val spec: WidgetSpec,
    ) : TurnItem

    @Serializable
    @SerialName("artifact")
    data class Artifact(
        override val id: String,
        val callId: String,
        val file: FileSpec,
    ) : TurnItem
}

@Serializable
enum class ProcessStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

object OrderedTurnReducer {
    fun begin(
        turns: List<ChatTurn>,
        input: String,
        id: String = UUID.randomUUID().toString(),
    ): List<ChatTurn> = turns + listOf(
        ChatTurn("$id-user", "user", listOf(TurnItem.Text("$id-user-text", input))),
        ChatTurn("$id-assistant", "assistant", emptyList(), complete = false),
    )

    fun beginContinuation(
        turns: List<ChatTurn>,
        id: String = UUID.randomUUID().toString(),
    ): List<ChatTurn> = turns + ChatTurn(
        "$id-assistant",
        "assistant",
        emptyList(),
        complete = false,
    )

    fun reduce(turns: List<ChatTurn>, event: AgentEvent): List<ChatTurn> {
        val index = turns.indexOfLast { it.role == "assistant" && !it.complete }
        if (index < 0) return turns
        val turn = turns[index]
        val updated = when (event) {
            is AgentEvent.MessageUpdate -> {
                val text = (event.chunk as? ChatChunk.Text)?.delta ?: return turns
                val last = turn.items.lastOrNull()
                if (last is TurnItem.Text) {
                    turn.copy(items = turn.items.dropLast(1) + last.copy(text = last.text + text))
                } else {
                    turn.copy(items = turn.items + TurnItem.Text("text-${turn.items.size}", text))
                }
            }
            is AgentEvent.ToolExecutionStart -> onToolStart(turn, event)
            is AgentEvent.ToolExecutionEnd -> onToolEnd(turn, event)
            is AgentEvent.AgentEnd -> turn.copy(complete = true)
            else -> turn
        }
        return turns.toMutableList().also { it[index] = updated }
    }

    fun complete(turns: List<ChatTurn>): List<ChatTurn> {
        val index = turns.indexOfLast { it.role == "assistant" && !it.complete }
        if (index < 0) return turns
        return turns.toMutableList().also { list ->
            val turn = list[index]
            list[index] = turn.copy(
                complete = true,
                items = turn.items.map { item ->
                    if (item is TurnItem.Process && item.status == ProcessStatus.RUNNING) {
                        item.copy(status = ProcessStatus.CANCELLED)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun visibleProjection(turn: ChatTurn): String = turn.items.mapNotNull { item ->
        when (item) {
            is TurnItem.Text -> item.text
            is TurnItem.Widget -> item.spec.summary()
            is TurnItem.Artifact -> item.file.summary()
            is TurnItem.Process -> null
        }
    }.filter { it.isNotBlank() }.joinToString("\n")

    private fun onToolStart(turn: ChatTurn, event: AgentEvent.ToolExecutionStart): ChatTurn {
        val call = event.call
        val process = TurnItem.Process(
            id = "process-${call.id}",
            callId = call.id,
            label = call.name,
            argumentsSummary = call.argumentsJson.replace(Regex("\\s+"), " ").take(160),
        )
        val extra: TurnItem? = when {
            call.name in UiToolNames.renderers -> {
                val spec = runCatching { widgetFromToolCall(call.name, call.argumentsJson) }
                    .getOrElse { FallbackSpec("组件解析失败", it.message.orEmpty(), call.id) }
                TurnItem.Widget("widget-${call.id}", call.id, spec)
            }
            call.name in UiToolNames.writers -> {
                val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
                TurnItem.Artifact(
                    "artifact-${call.id}",
                    call.id,
                    FileSpec(
                        artifactId = "",
                        artifactVersion = 1,
                        name = args?.get("name")?.jsonPrimitive?.content ?: "产物",
                        mime = if (call.name == UiToolNames.WRITE_HTML) "text/html" else "text/markdown",
                        status = "pending",
                    ),
                )
            }
            else -> null
        }
        return turn.copy(items = turn.items + listOfNotNull(process, extra))
    }

    private fun onToolEnd(turn: ChatTurn, event: AgentEvent.ToolExecutionEnd): ChatTurn {
        val ref = if (!event.isError && event.call.name in UiToolNames.writers) {
            runCatching { Json.decodeFromString<ArtifactRef>(event.result) }.getOrNull()
        } else {
            null
        }
        return turn.copy(items = turn.items.map { item ->
            when {
                item is TurnItem.Process && item.callId == event.call.id -> item.copy(
                    status = if (event.isError) ProcessStatus.FAILED else ProcessStatus.SUCCEEDED,
                    resultSummary = event.result.replace(Regex("\\s+"), " ").take(200),
                )
                item is TurnItem.Artifact && item.callId == event.call.id -> item.copy(
                    file = if (ref != null) {
                        item.file.copy(
                            artifactId = ref.artifactId,
                            artifactVersion = ref.version,
                            status = "ready",
                        )
                    } else {
                        item.file.copy(status = "error", summaryText = event.result.take(200))
                    },
                )
                else -> item
            }
        })
    }
}
