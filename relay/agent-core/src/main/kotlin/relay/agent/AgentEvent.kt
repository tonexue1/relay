package relay.agent

import relay.llm.model.ChatChunk
import relay.llm.model.Message
import relay.llm.model.ToolCall

/**
 * Lifecycle events for one agent run, shaped after pi-agent-core.
 *
 * A turn is one LLM call plus any tool executions that followed it. [MessageUpdate]
 * forwards [ChatChunk] as-is so UI can keep using `ToolCallAccumulator`.
 */
sealed interface AgentEvent {
    data object AgentStart : AgentEvent

    data class AgentEnd(val messages: List<Message>) : AgentEvent

    data object TurnStart : AgentEvent

    data class TurnEnd(
        val message: Message,
        val toolResults: List<Message>,
    ) : AgentEvent

    data class MessageStart(val message: Message) : AgentEvent

    data class MessageUpdate(val chunk: ChatChunk) : AgentEvent

    data class MessageEnd(val message: Message) : AgentEvent

    data class ToolExecutionStart(val call: ToolCall) : AgentEvent

    data class ToolExecutionEnd(
        val call: ToolCall,
        val result: String,
        val isError: Boolean,
    ) : AgentEvent
}
