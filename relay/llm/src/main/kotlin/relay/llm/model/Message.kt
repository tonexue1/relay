package relay.llm.model

import kotlinx.serialization.json.JsonObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * A single conversation turn.
 *
 * An assistant turn that requests tools carries [toolCalls] with a null [content].
 * A [Role.TOOL] turn carries the tool result in [content] and must set [toolCallId].
 */
data class Message(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    companion object {
        fun system(content: String): Message = Message(Role.SYSTEM, content)

        fun user(content: String): Message = Message(Role.USER, content)

        fun assistant(content: String): Message = Message(Role.ASSISTANT, content)

        fun toolResult(toolCallId: String, content: String): Message =
            Message(Role.TOOL, content = content, toolCallId = toolCallId)
    }
}

/** A tool invocation requested by the model. [argumentsJson] is the raw JSON object emitted by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** Declaration of a tool the model may call. [parameters] is a JSON Schema object. */
data class ToolDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject,
)

/**
 * Incremental tool-call fragment emitted while streaming.
 *
 * Providers stream tool calls piecewise: [id] and [name] usually arrive once, then
 * [argumentsDelta] arrives across many chunks and must be concatenated per [index].
 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
)
