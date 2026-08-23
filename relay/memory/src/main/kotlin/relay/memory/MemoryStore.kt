package relay.memory

/**
 * Deterministic personal graph. SQLite + FTS only. No LLM.
 *
 * Callers must pass [graphId] on every read and write. Cross-graph JOIN is forbidden.
 */
interface MemoryStore {
    suspend fun capture(turn: RawTurn): String

    suspend fun ingest(drafts: List<TripleDraft>): IngestResult

    suspend fun ingestClaims(
        sessionId: String,
        runId: String,
        drafts: List<ClaimDraft>,
    ): List<OpenClaim>

    suspend fun queryClaims(
        graphId: String,
        text: String,
        budgetChars: Int = 2000,
    ): List<OpenClaim>

    suspend fun claims(graphId: String): List<OpenClaim>

    suspend fun startExtractionRun(
        graphId: String,
        sessionId: String,
        eventIds: List<String>,
        contextEventIds: List<String> = emptyList(),
    ): String

    suspend fun finishExtractionRun(
        runId: String,
        outcome: ExtractOutcome,
        rawResponse: String = "",
        errors: List<String> = emptyList(),
    )

    suspend fun commitExtraction(
        graphId: String,
        sessionId: String,
        runId: String,
        eventIds: List<String>,
        claims: List<ClaimDraft>,
        drafts: List<TripleDraft>,
        outcome: ExtractOutcome,
        rawResponse: String = "",
    ): IngestResult

    suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int = 2000,
        at: Long = System.currentTimeMillis(),
    ): MemoryHit

    suspend fun forget(graphId: String, now: Long = System.currentTimeMillis())

    suspend fun pendingReview(graphId: String): List<ReviewItem>

    suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean)

    suspend fun rebuildFromFactLog(graphId: String)

    suspend fun unconsumed(graphId: String): List<RawEvent>

    suspend fun unconsumed(graphId: String, sessionId: String): List<RawEvent>

    suspend fun contextBefore(
        graphId: String,
        sessionId: String,
        beforeTs: Long,
        limit: Int,
    ): List<RawEvent>

    suspend fun markConsumed(graphId: String, ids: List<String>)

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
