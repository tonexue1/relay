package relay.memory.engine

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "raw_event")
data class RawEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    val ts: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "text_ref") val textRef: String,
    val source: String,
    val consumed: Int,
)

@Entity(tableName = "fact_log")
data class FactLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    val ts: Long,
    val s: String,
    val p: String,
    val o: String,
    val confidence: Double,
    @ColumnInfo(name = "raw_event_ids") val rawEventIds: String,
    val retract: Int,
    @ColumnInfo(name = "valid_at") val validAt: Long,
    @ColumnInfo(name = "invalid_at") val invalidAt: Long?,
)

@Entity(
    tableName = "claim_log",
    indices = [
        Index(value = ["graph_id", "created_at"]),
        Index(value = ["run_id"]),
    ],
)
data class ClaimLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    val subject: String,
    val text: String,
    val confidence: Double,
    @ColumnInfo(name = "raw_event_ids") val rawEventIds: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "extraction_run",
    indices = [Index(value = ["graph_id", "started_at"])],
)
data class ExtractionRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val status: String,
    @ColumnInfo(name = "event_ids") val eventIds: String,
    @ColumnInfo(name = "context_event_ids") val contextEventIds: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "response_ref") val responseRef: String,
    val error: String,
)

@Entity(
    tableName = "node",
    indices = [Index(value = ["graph_id", "canonical_name"], unique = true)],
)
data class NodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    val type: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
)

@Entity(tableName = "node_alias", primaryKeys = ["graph_id", "alias"])
data class NodeAliasEntity(
    @ColumnInfo(name = "graph_id") val graphId: String,
    val alias: String,
    @ColumnInfo(name = "node_id") val nodeId: String,
)

@Entity(tableName = "edge")
data class EdgeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    val src: String,
    val dst: String,
    val relation: String,
    val confidence: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expired_at") val expiredAt: Long?,
    @ColumnInfo(name = "valid_at") val validAt: Long,
    @ColumnInfo(name = "invalid_at") val invalidAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val provenance: String,
)

@Entity(tableName = "pending_review")
data class PendingReviewEntity(
    @PrimaryKey @ColumnInfo(name = "edge_id") val edgeId: String,
    val reason: String,
    val confidence: Double,
    val s: String,
    val p: String,
    val o: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["node_id", "graph_id"],
)
@Entity(tableName = "node_fts")
data class NodeFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    val aliases: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["claim_id", "graph_id"],
)
@Entity(tableName = "claim_fts")
data class ClaimFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int,
    @ColumnInfo(name = "claim_id") val claimId: String,
    @ColumnInfo(name = "graph_id") val graphId: String,
    val subject: String,
    val text: String,
)
