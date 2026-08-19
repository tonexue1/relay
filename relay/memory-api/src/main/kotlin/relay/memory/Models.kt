package relay.memory

import kotlinx.serialization.Serializable

data class RawTurn(
    val graphId: String,
    val role: String,
    val text: String,
    val sessionId: String = "",
    val source: String = "chat",
    val scope: String = "private",
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
    val scope: String,
    val textRef: String = "",
)

data class TripleDraft(
    val graphId: String,
    val s: String,
    val p: String,
    val o: String,
    val rawEventIds: List<String> = emptyList(),
    val confidence: Double = 0.7,
)

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
    val validFrom: Long,
    val validTo: Long?,
    val updatedAt: Long,
    val scope: String,
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
)

@Serializable
internal data class AliasRec(
    val graphId: String,
    val alias: String,
    val nodeId: String,
)

@Serializable
internal data class Snapshot(
    val nodes: List<NodeRec>,
    val aliases: List<AliasRec>,
    val edges: List<EdgeRec>,
    val raw: List<RawEvent>,
    val factLog: List<FactLogRec>,
    val reviews: List<ReviewItem>,
)
