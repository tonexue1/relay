package relay.memory.api

import kotlinx.serialization.json.JsonObject
import relay.memory.MemoryScope

enum class ClockDomain { WALL_CLOCK, STORY_TIME }

data class ClockStamp(
    val domain: ClockDomain,
    val t: Long,
)

enum class OverwritePolicy {
    EXTRACTOR_CAN_CURRENT,
    EXTRACTOR_CANDIDATE_ONLY,
    USER_LOCK,
}

enum class MemoryWriterKind { EXTRACTOR, USER_EDIT, HOST }

enum class AuthorityMode { MEMORY_AUTHORITATIVE, HOST_AUTHORITATIVE }

enum class ProjectionMode { NONE, MEMORY_MIRROR }

enum class RiskTier { LOW, HIGH }

enum class TargetLifecycle { CURRENT, CANDIDATE }

enum class MemoryKind { STATE, EPISODE, REFLECTION }

enum class SourceType { RAW_EVENT, USER_EDIT, HOST_TXN, IMPORT }

enum class LifecycleState { CANDIDATE, ACTIVE, RETRACTED }

enum class OnMissing { BLOCK, SKIP }

enum class RecallStatus { READY, BLOCKED }

data class ValueContract(val json: String = "{}")

data class StateFieldSpec(
    val spaceId: String,
    val fieldId: String,
    val contract: ValueContract = ValueContract(),
    val authorityMode: AuthorityMode = AuthorityMode.MEMORY_AUTHORITATIVE,
    val projectionMode: ProjectionMode = ProjectionMode.NONE,
    val riskTier: RiskTier = RiskTier.LOW,
    val allowedWriters: Set<MemoryWriterKind> = MemoryWriterKind.entries.toSet(),
    val overwritePolicy: OverwritePolicy = OverwritePolicy.EXTRACTOR_CAN_CURRENT,
)

data class StateSchemaSnapshot(
    val spaceId: String,
    val clockDomain: ClockDomain,
    val fields: List<StateFieldSpec> = emptyList(),
)

data class SchemaRegistration(
    val spaceId: String,
    val fieldIds: List<String>,
)

data class FieldRegistration(
    val fieldId: String,
    val created: Boolean,
)

data class RawEventDraft(
    val spaceId: String,
    val ownerId: String,
    val role: String,
    val content: String,
    val clockDomain: ClockDomain,
    val sessionId: String = "",
    val taskScopeId: String = "",
    val occurredAt: Long? = null,
    val idempotencyKey: String? = null,
)

typealias RawEventId = String

data class RenderedText(
    val text: String,
    val rendererId: String = "",
    val rendererVersion: String = "",
)

data class SourceRef(
    val type: SourceType,
    val id: String,
)

sealed interface MemoryCommand

data class StateCommand(
    val fieldId: String,
    val payload: JsonObject,
    val rendered: RenderedText,
    val sources: List<SourceRef>,
    val validFrom: ClockStamp,
    val targetLifecycle: TargetLifecycle = TargetLifecycle.CURRENT,
    val expectedCurrentId: String? = null,
    val sourceRevision: Long? = null,
    val overrideUserEdit: Boolean = false,
    val scope: MemoryScope = MemoryScope.PROFILE,
    val scopeId: String = "",
    val tags: List<String> = emptyList(),
    val confidence: Double = 1.0,
) : MemoryCommand

data class EvidenceRef(
    val memoryId: String,
    val relation: String = "SUPPORTS",
)

data class ReflectionCommand(
    val memoryKey: String,
    val rendered: RenderedText,
    val sources: List<SourceRef>,
    val validFrom: ClockStamp,
    val evidence: List<EvidenceRef> = emptyList(),
    val targetLifecycle: TargetLifecycle = TargetLifecycle.CURRENT,
    val expectedCurrentId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    val scope: MemoryScope = MemoryScope.PROFILE,
    val scopeId: String = "",
    val tags: List<String> = emptyList(),
    val confidence: Double = 1.0,
) : MemoryCommand

data class EmbeddingPut(
    val memoryId: String,
    val modelId: String,
    val vector: FloatArray,
    val textHash: String? = null,
)

data class EpisodeCommand(
    val idempotencyKey: String,
    val occurredAt: ClockStamp?,
    val rendered: RenderedText,
    val sources: List<SourceRef>,
    val payload: JsonObject = JsonObject(emptyMap()),
    val scope: MemoryScope = MemoryScope.PROFILE,
    val scopeId: String = "",
    val tags: List<String> = emptyList(),
    val confidence: Double = 1.0,
) : MemoryCommand

data class MemoryBatch(
    val spaceId: String,
    val ownerId: String,
    val writerKind: MemoryWriterKind,
    val writerId: String,
    val writerRunId: String,
    val commands: List<MemoryCommand>,
    val commitRawIds: List<RawEventId> = emptyList(),
)

data class MemoryError(
    val code: String,
    val message: String = "",
)

data class CommitResult(
    val ok: Boolean,
    val error: MemoryError? = null,
    val itemIds: List<String> = emptyList(),
)

data class RequiredField(
    val fieldId: String,
    val onMissing: OnMissing = OnMissing.BLOCK,
)

data class RecallRequest(
    val spaceId: String,
    val ownerId: String,
    val query: String,
    val queryVector: FloatArray? = null,
    val embeddingModelId: String = "default",
    val at: ClockStamp,
    val sessionId: String = "",
    val taskScopeId: String = "",
    val includeOwners: List<String> = emptyList(),
    val recentMessages: List<String> = emptyList(),
    val requiredFields: List<RequiredField> = emptyList(),
    val contextContractId: String? = null,
    val contextContractVersion: String? = null,
    val budgetChars: Int = 2_000,
    val explain: Boolean = false,
)

data class StateSelector(
    val fieldId: String,
)

data class StateReadRequest(
    val spaceId: String,
    val ownerId: String,
    val at: ClockStamp,
    val selectors: Set<StateSelector>,
    val includeOwners: List<String> = emptyList(),
    val sessionId: String = "",
    val taskScopeId: String = "",
)

data class StateSnapshot(
    val itemId: String,
    val fieldId: String,
    val ownerId: String,
    val payload: JsonObject,
    val text: String,
    val scope: MemoryScope,
    val scopeId: String,
    val validFrom: Long?,
    val validTo: Long?,
)

data class StateReadResult(
    val states: Map<String, StateSnapshot>,
    val errors: Map<String, MemoryError> = emptyMap(),
)

data class StateHistoryRequest(
    val spaceId: String,
    val ownerId: String,
    val fieldId: String,
    val sessionId: String = "",
    val taskScopeId: String = "",
)

data class StateVersion(
    val itemId: String,
    val isCurrent: Boolean,
    val lifecycle: LifecycleState,
    val text: String,
    val validFrom: Long?,
    val validTo: Long?,
    val writerKind: MemoryWriterKind,
)

data class SearchHit(
    val itemId: String,
    val kind: MemoryKind,
    val ownerId: String,
    val text: String,
    val channel: String,
)

data class RecallResult(
    val status: RecallStatus,
    val required: Map<String, StateSnapshot>,
    val hits: List<SearchHit>,
    val blocked: List<MemoryError> = emptyList(),
)

data class IndexHealth(
    val ftsOk: Boolean,
    val embeddingPending: Int = 0,
)

data class MemoryRecord(
    val itemId: String,
    val kind: MemoryKind,
    val ownerId: String,
    val fieldId: String?,
    val text: String,
    val scope: MemoryScope,
    val scopeId: String,
    val lifecycle: LifecycleState,
)

class MemoryFault(
    val code: String,
    message: String = code,
) : RuntimeException(message)

object MemoryCodes {
    const val CLOCK_DOMAIN_MISMATCH = "CLOCK_DOMAIN_MISMATCH"
    const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
    const val UNKNOWN_SPACE = "UNKNOWN_SPACE"
    const val AMBIGUOUS_FIELD = "AMBIGUOUS_FIELD"
    const val SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND"
    const val CAS_CONFLICT = "CAS_CONFLICT"
    const val WRITER_NOT_ALLOWED = "WRITER_NOT_ALLOWED"
    const val USER_LOCK = "USER_LOCK"
    const val IDEMPOTENT_REPLAY = "IDEMPOTENT_REPLAY"
    const val MISSING_CLOCK = "MISSING_CLOCK"
    const val ALIAS_COLLISION = "ALIAS_COLLISION"
    const val MISSING_SOURCE = "MISSING_SOURCE"
    const val REQUIRED_CONTRACT = "REQUIRED_CONTRACT"
    const val MISSING_EVIDENCE = "MISSING_EVIDENCE"
    const val EMBEDDING_FAILED = "EMBEDDING_FAILED"
}
