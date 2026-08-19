package relay.memory

/**
 * Deterministic personal graph. No [relay.llm.Provider] lives here.
 *
 * Callers must pass [graphId] on every read and write. Cross-graph JOIN is forbidden.
 */
interface MemoryStore {
    suspend fun capture(turn: RawTurn): String

    suspend fun ingest(drafts: List<TripleDraft>)

    suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int = 2000,
        principal: String = "user",
    ): MemoryHit

    suspend fun forget(graphId: String, now: Long = System.currentTimeMillis())

    suspend fun pendingReview(graphId: String): List<ReviewItem>

    suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean)

    suspend fun rebuildFromFactLog(graphId: String)

    suspend fun unconsumed(graphId: String, principal: String = "user"): List<RawEvent>

    suspend fun markConsumed(graphId: String, ids: List<String>)

    suspend fun markScope(graphId: String, ids: List<String>, scope: String)

    /** Current valid edges, for the facts card. */
    suspend fun facts(graphId: String): MemoryHit
}
