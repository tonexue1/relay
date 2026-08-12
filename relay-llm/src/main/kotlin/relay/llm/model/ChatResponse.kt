package relay.llm.model

enum class FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, UNKNOWN }

/** Token accounting as reported by the provider. Treated as billing-grade truth. */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

data class ChatResponse(
    val message: Message,
    val usage: Usage?,
    val finishReason: FinishReason,
    val model: String? = null,
    val providerId: String? = null,
)
