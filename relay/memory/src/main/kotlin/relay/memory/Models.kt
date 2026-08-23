package relay.memory

import kotlinx.serialization.Serializable
import relay.llm.model.FinishReason

data class RawTurn(
    val graphId: String,
    val role: String,
    val text: String,
    val sessionId: String = "",
    val source: String = "chat",
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class RawEvent(
    val id: String,
    val graphId: String,
    val ts: Long,
    val sessionId: String,
    val role: String,
    val text: String,
    val source: String,
    val consumed: Boolean,
    val textRef: String = "",
)

internal data class CleanTriple(
    val s: String,
    val p: String,
    val o: String,
    val retract: Boolean = false,
)

data class IngestError(
    val graphId: String,
    val s: String,
    val p: String,
    val o: String,
    val reason: String,
)

data class IngestResult(
    val errors: List<IngestError> = emptyList(),
)

data class TripleDraft(
    val graphId: String,
    val s: String,
    val p: String,
    val o: String,
    val rawEventIds: List<String> = emptyList(),
    val confidence: Double = 0.7,
    val retract: Boolean = false,
    val validAt: Long? = null,
    val invalidAt: Long? = null,
)

enum class ExtractOutcome {
    SUCCESS,
    SUCCESS_EMPTY,
    PARSE_FAILED,
    TRUNCATED,
    REJECTED,
}

data class ClaimDraft(
    val graphId: String,
    val subject: String,
    val text: String,
    val rawEventIds: List<String> = emptyList(),
    val confidence: Double = 0.7,
)

@Serializable
data class OpenClaim(
    val id: String,
    val graphId: String,
    val sessionId: String,
    val runId: String,
    val subject: String,
    val text: String,
    val confidence: Double,
    val rawEventIds: List<String>,
    val createdAt: Long,
)

data class ExtractResult(
    val outcome: ExtractOutcome,
    val claims: List<ClaimDraft> = emptyList(),
    val drafts: List<TripleDraft> = emptyList(),
    val raw: String = "",
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val errors: List<String> = emptyList(),
) {
    val successful: Boolean
        get() = outcome == ExtractOutcome.SUCCESS || outcome == ExtractOutcome.SUCCESS_EMPTY
}

data class LearnBatch(
    val graphId: String,
    val sessionId: String,
    val events: List<RawEvent>,
    val contextEvents: List<RawEvent> = emptyList(),
) {
    val eventIds: List<String> get() = events.map { it.id }
    val contextEventIds: List<String> get() = contextEvents.map { it.id }
}

data class Fact(
    val s: String,
    val p: String,
    val o: String,
    val edgeId: String = "",
) {
    fun line(): String = "- $s ${predicateLabel(p)} $o"
}

data class MemoryHit(
    val facts: List<Fact> = emptyList(),
) {
    fun render(): String = facts.joinToString("\n") { it.line() }

    val isEmpty: Boolean get() = facts.isEmpty()
}

@Serializable
data class ReviewItem(
    val edgeId: String,
    val reason: String,
    val confidence: Double,
    val s: String,
    val p: String,
    val o: String,
)

@Serializable
internal data class NodeRec(
    val id: String,
    val graphId: String,
    val type: String,
    val canonical: String,
)

@Serializable
internal data class EdgeRec(
    val id: String,
    val graphId: String,
    val srcId: String,
    val dstId: String,
    val relation: String,
    val confidence: Double,
    val createdAt: Long,
    val expiredAt: Long?,
    val validAt: Long,
    val invalidAt: Long?,
    val updatedAt: Long,
    val provenance: List<String>,
)

@Serializable
internal data class FactLogRec(
    val id: String,
    val graphId: String,
    val ts: Long,
    val s: String,
    val p: String,
    val o: String,
    val confidence: Double,
    val rawEventIds: List<String>,
    val retract: Boolean = false,
    val validAt: Long = 0,
    val invalidAt: Long? = null,
)

@Serializable
internal data class AliasRec(
    val graphId: String,
    val alias: String,
    val nodeId: String,
)

@Serializable
internal data class ExtractionRunRec(
    val id: String,
    val graphId: String,
    val sessionId: String,
    val status: String,
    val eventIds: List<String>,
    val contextEventIds: List<String>,
    val startedAt: Long,
    val finishedAt: Long?,
    val response: String,
    val error: String,
)

@Serializable
internal data class Snapshot(
    val nodes: List<NodeRec>,
    val aliases: List<AliasRec>,
    val edges: List<EdgeRec>,
    val raw: List<RawEvent>,
    val factLog: List<FactLogRec>,
    val reviews: List<ReviewItem>,
    val claims: List<OpenClaim> = emptyList(),
    val extractionRuns: List<ExtractionRunRec> = emptyList(),
)
