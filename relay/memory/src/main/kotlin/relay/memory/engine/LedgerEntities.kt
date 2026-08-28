package relay.memory.engine

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "memory_space")
data class SpaceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "clock_domain") val clockDomain: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "state_field", primaryKeys = ["space_id", "field_id"])
data class StateFieldEntity(
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "field_id") val fieldId: String,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "value_contract") val valueContract: String,
    @ColumnInfo(name = "allowed_writers") val allowedWriters: String,
    @ColumnInfo(name = "risk_tier") val riskTier: String,
    @ColumnInfo(name = "authority_mode") val authorityMode: String,
    @ColumnInfo(name = "projection_mode") val projectionMode: String,
    @ColumnInfo(name = "overwrite_policy") val overwritePolicy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val deprecated: Int = 0,
)

@Entity(tableName = "state_field_alias", primaryKeys = ["space_id", "alias"])
data class StateFieldAliasEntity(
    @ColumnInfo(name = "space_id") val spaceId: String,
    val alias: String,
    @ColumnInfo(name = "canonical_field_id") val canonicalFieldId: String,
)

@Entity(
    tableName = "ledger_raw_event",
    indices = [Index(value = ["space_id", "idempotency_key"], unique = true)],
)
data class LedgerRawEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "task_scope_id") val taskScopeId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "clock_domain") val clockDomain: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "processing_state") val processingState: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
)

@Entity(
    tableName = "memory_item",
    indices = [
        Index(value = ["space_id", "owner_id", "kind", "field_id"]),
        Index(value = ["space_id", "owner_id", "idempotency_key"]),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val kind: String,
    @ColumnInfo(name = "field_id") val fieldId: String?,
    @ColumnInfo(name = "memory_key") val memoryKey: String?,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val text: String,
    @ColumnInfo(name = "renderer_id") val rendererId: String,
    @ColumnInfo(name = "renderer_version") val rendererVersion: String,
    val scope: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "is_current") val isCurrent: Int,
    @ColumnInfo(name = "lifecycle_state") val lifecycleState: String,
    val confidence: Double,
    val salience: Double,
    @ColumnInfo(name = "clock_domain") val clockDomain: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "valid_from") val validFrom: Long?,
    @ColumnInfo(name = "valid_to") val validTo: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "supersedes_id") val supersedesId: String?,
    @ColumnInfo(name = "retracted_at") val retractedAt: Long?,
    @ColumnInfo(name = "writer_kind") val writerKind: String,
    @ColumnInfo(name = "writer_id") val writerId: String,
    @ColumnInfo(name = "writer_run_id") val writerRunId: String,
    @ColumnInfo(name = "mirrored_source_revision") val mirroredSourceRevision: Long?,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    @ColumnInfo(name = "text_hash") val textHash: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
)

@Entity(tableName = "memory_source", primaryKeys = ["memory_id", "source_type", "source_id"])
data class MemorySourceEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
)

@Entity(tableName = "memory_tag", primaryKeys = ["memory_id", "tag"])
data class MemoryTagEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val tag: String,
)

@Entity(tableName = "memory_evidence", primaryKeys = ["reflection_id", "evidence_id"])
data class MemoryEvidenceEntity(
    @ColumnInfo(name = "reflection_id") val reflectionId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    val relation: String,
)

@Entity(tableName = "embedding_model")
data class EmbeddingModelEntity(
    @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    val dimensions: Int,
    @ColumnInfo(name = "tokenizer_version") val tokenizerVersion: String,
    @ColumnInfo(name = "query_prefix") val queryPrefix: String,
    @ColumnInfo(name = "document_prefix") val documentPrefix: String,
    val normalization: String,
    val active: Int,
)

@Entity(tableName = "memory_embedding", primaryKeys = ["memory_id", "model_id"])
data class MemoryEmbeddingEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "text_hash") val textHash: String,
    @ColumnInfo(name = "vector_blob") val vectorBlob: ByteArray,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)

@Entity(tableName = "index_job")
data class IndexJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["memory_id"],
)
@Entity(tableName = "memory_fts")
data class MemoryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val text: String,
)
