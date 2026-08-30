package relay.assistant.memory

data class StateProposal(
    val fieldId: String,
    val value: String,
    val rawEventIds: List<String> = emptyList(),
)

data class RememberOutcome(
    val ok: Boolean,
    val message: String,
    val fieldId: String? = null,
    val candidate: Boolean = false,
)
