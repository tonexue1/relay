package relay.agent

/**
 * Result of [Agent]'s `beforeToolCall` hook.
 *
 * [block] skips execution and turns the call into an error tool result. [reason] is the
 * text the model sees; a missing reason uses a default blocked message.
 */
data class BeforeToolCallResult(
    val block: Boolean = false,
    val reason: String? = null,
)
