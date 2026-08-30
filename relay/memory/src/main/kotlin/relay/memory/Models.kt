package relay.memory

enum class MemoryScope {
    PROFILE,
    TASK,
    SESSION,
}

/** Scope filter for host inventory / pad. Profile is always in; session/task match ids. */
data class RecallContext(
    val sessionId: String = "",
    val taskScopeId: String = "",
    val allowCrossTask: Boolean = false,
)
