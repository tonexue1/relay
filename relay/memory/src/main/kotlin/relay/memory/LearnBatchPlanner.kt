package relay.memory

/**
 * Deterministically slices persisted raw turns into a bounded extraction episode.
 *
 * Scheduling and lifecycle stay with the host. The planner only chooses durable rows.
 */
class LearnBatchPlanner(
    private val maxUserTurns: Int = 8,
    private val maxChars: Int = 8_000,
    private val contextTurns: Int = 2,
) {
    init {
        require(maxUserTurns > 0)
        require(maxChars > 0)
        require(contextTurns >= 0)
    }

    suspend fun next(
        store: MemoryStore,
        graphId: String,
        sessionId: String? = null,
    ): LearnBatch? {
        val pending = if (sessionId == null) {
            store.unconsumed(graphId)
        } else {
            store.unconsumed(graphId, sessionId)
        }
        val first = pending.firstOrNull() ?: return null
        val batchSession = sessionId ?: first.sessionId
        val sessionEvents = pending.filter { it.sessionId == batchSession }
        val selected = mutableListOf<RawEvent>()
        var userTurns = 0
        var chars = 0
        for (event in sessionEvents) {
            if (event.role == "user" && userTurns >= maxUserTurns && selected.isNotEmpty()) break
            val eventChars = event.text.length + event.role.length + 3
            val continuesPair = event.role == "assistant" && selected.lastOrNull()?.role == "user"
            if (selected.isNotEmpty() && chars + eventChars > maxChars && !continuesPair) break
            selected += event
            chars += eventChars
            if (event.role == "user") userTurns++
        }
        if (selected.isEmpty()) return null
        val context = store.contextBefore(
            graphId = graphId,
            sessionId = batchSession,
            beforeTs = selected.first().ts,
            limit = contextTurns * 2,
        )
        return LearnBatch(
            graphId = graphId,
            sessionId = batchSession,
            events = selected,
            contextEvents = context,
            taskScopeId = selected.firstNotNullOfOrNull { it.taskScopeId.takeIf(String::isNotBlank) }.orEmpty(),
        )
    }
}
