package relay.llm.model

/** One increment of a streamed response. A stream always terminates with [Done]. */
sealed interface ChatChunk {
    data class Text(val delta: String) : ChatChunk

    data class ToolCalls(val delta: ToolCallDelta) : ChatChunk

    data class Done(
        val usage: Usage? = null,
        val finishReason: FinishReason = FinishReason.STOP,
        val extra: Map<String, String> = emptyMap(),
    ) : ChatChunk
}
