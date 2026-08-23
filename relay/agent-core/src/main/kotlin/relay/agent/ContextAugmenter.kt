package relay.agent

import relay.llm.model.Message

/**
 * Adds temporary request context. Does not rewrite the transcript and does not write
 * back to [AgentState.messages].
 */
fun interface ContextAugmenter {
    suspend fun augment(messages: List<Message>): ContextAugmentation
}

data class ContextAugmentation(
    val messages: List<Message> = emptyList(),
) {
    companion object {
        val Empty = ContextAugmentation()
    }
}
