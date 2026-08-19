package relay.memory

/**
 * Deterministic personal graph. SQLite + FTS only. No LLM.
 *
 * Callers must pass [graphId] on every read and write. Cross-graph JOIN is forbidden.
 */
interface MemoryStore {
    suspend fun capture(turn: RawTurn): String

    suspend fun ingest(drafts: List<TripleDraft>): IngestResult

    suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int = 2000,
        principal: String = "user",
        at: Long = System.currentTimeMillis(),
    ): MemoryHit

    suspend fun forget(graphId: String, now: Long = System.currentTimeMillis())

    suspend fun pendingReview(graphId: String): List<ReviewItem>

    suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean)

    suspend fun rebuildFromFactLog(graphId: String)

    suspend fun unconsumed(graphId: String, principal: String = "user"): List<RawEvent>

    suspend fun markConsumed(graphId: String, ids: List<String>)

    suspend fun markScope(graphId: String, ids: List<String>, scope: String)

    /** Edges current at [at]. Optional [p] / [node] filter on relation or endpoint name. */
    suspend fun facts(
        graphId: String,
        at: Long = System.currentTimeMillis(),
        p: String? = null,
        node: String? = null,
    ): MemoryHit

    /** Edges whose system clock (created, updated, or expired) is at or after [since]. */
    suspend fun recent(graphId: String, since: Long): MemoryHit

    /** Live edges touching any of [nodeNames]. */
    suspend fun neighborhood(graphId: String, nodeNames: List<String>): MemoryHit

    /**
     * System-expire live edges on [drop], insert the remapped edge on [keep] if missing,
     * alias [drop] to [keep]. Does not DELETE rows or set world `invalid_at`.
     */
    suspend fun mergeNodes(graphId: String, keep: String, drop: String)
}
