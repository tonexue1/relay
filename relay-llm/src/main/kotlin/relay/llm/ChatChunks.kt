package relay.llm

import kotlinx.coroutines.flow.Flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.model.Usage
import relay.llm.tool.ToolCallAccumulator

/**
 * Collects a stream into the [ChatResponse] the unary call would have returned.
 *
 * Useful when a caller wants streaming's early feedback for the UI but still needs the
 * assembled message -- notably an agent loop, which cannot dispatch a tool until the
 * whole argument JSON has arrived.
 */
suspend fun Flow<ChatChunk>.foldToResponse(): ChatResponse {
    val text = StringBuilder()
    val toolCalls = ToolCallAccumulator()
    var usage: Usage? = null
    var finishReason = FinishReason.UNKNOWN

    collect { chunk ->
        when (chunk) {
            is ChatChunk.Text -> text.append(chunk.delta)
            is ChatChunk.ToolCalls -> toolCalls.accept(chunk.delta)
            is ChatChunk.Done -> {
                usage = chunk.usage
                finishReason = chunk.finishReason
            }
        }
    }

    return ChatResponse(
        message = Message(
            role = Role.ASSISTANT,
            content = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = toolCalls.build(),
        ),
        usage = usage,
        finishReason = finishReason,
    )
}
