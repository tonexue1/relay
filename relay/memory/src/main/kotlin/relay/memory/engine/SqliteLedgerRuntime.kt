package relay.memory.engine

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import relay.memory.MemoryScope
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.CommitResult
import relay.memory.api.EmbeddingPut
import relay.memory.api.EpisodeCommand
import relay.memory.api.EvidenceRef
import relay.memory.api.FieldRegistration
import relay.memory.api.IndexHealth
import relay.memory.api.LifecycleState
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryCodes
import relay.memory.api.MemoryError
import relay.memory.api.MemoryFault
import relay.memory.api.MemoryKind
import relay.memory.api.MemoryRecord
import relay.memory.api.MemoryRuntime
import relay.memory.api.MemoryWriterKind
import relay.memory.api.OnMissing
import relay.memory.api.OverwritePolicy
import relay.memory.api.RawEventDraft
import relay.memory.api.RawEventId
import relay.memory.api.RecallRequest
import relay.memory.api.RecallResult
import relay.memory.api.RecallStatus
import relay.memory.api.ReflectionCommand
import relay.memory.api.SchemaRegistration
import relay.memory.api.SearchHit
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.api.StateFieldSpec
import relay.memory.api.StateHistoryRequest
import relay.memory.api.StateReadRequest
import relay.memory.api.StateReadResult
import relay.memory.api.StateSchemaSnapshot
import relay.memory.api.StateSnapshot
import relay.memory.api.StateVersion
import relay.memory.api.TargetLifecycle

class SqliteLedgerRuntime(
    private val db: LedgerRoomDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MemoryRuntime {
    constructor(context: Context) : this(LedgerDb.inMemory(context))

    constructor(context: Context, file: File) : this(LedgerDb.file(context, file))

    private val lock = Mutex()
    private val dao get() = db.ledger()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun registerStateSchema(snapshot: StateSchemaSnapshot): SchemaRegistration = lock.withLock {
        dao.withTx {
            val existing = space(snapshot.spaceId)
            if (existing == null) {
                insertSpace(
                    SpaceEntity(snapshot.spaceId, snapshot.clockDomain.name, clock()),
                )
            } else if (existing.clockDomain != snapshot.clockDomain.name) {
                throw MemoryFault(MemoryCodes.CLOCK_DOMAIN_MISMATCH)
            }
            for (spec in snapshot.fields) {
                insertField(spec.toEntity(clock()))
            }
            SchemaRegistration(snapshot.spaceId, fields(snapshot.spaceId).map { it.fieldId })
        }
    }

    override suspend fun ensureStateField(spec: StateFieldSpec): FieldRegistration = lock.withLock {
        dao.withTx {
            space(spec.spaceId) ?: throw MemoryFault(MemoryCodes.UNKNOWN_SPACE)
            val resolved = resolveFieldId(spec.spaceId, spec.fieldId)
            if (resolved != null) {
                return@withTx FieldRegistration(resolved, created = false)
            }
            insertField(spec.toEntity(clock()))
            FieldRegistration(spec.fieldId, created = true)
        }
    }

    override suspend fun putFieldAlias(spaceId: String, alias: String, canonicalFieldId: String) = lock.withLock {
        dao.withTx {
            space(spaceId) ?: throw MemoryFault(MemoryCodes.UNKNOWN_SPACE)
            field(spaceId, canonicalFieldId) ?: throw MemoryFault(MemoryCodes.UNKNOWN_FIELD)
            val occupied = field(spaceId, alias)
            if (occupied != null && alias != canonicalFieldId) {
                throw MemoryFault(MemoryCodes.ALIAS_COLLISION)
            }
            upsertAlias(StateFieldAliasEntity(spaceId, alias, canonicalFieldId))
        }
        Unit
    }

    override suspend fun capture(event: RawEventDraft): RawEventId = lock.withLock {
        dao.withTx {
            val space = space(event.spaceId) ?: throw MemoryFault(MemoryCodes.UNKNOWN_SPACE)
            if (space.clockDomain != event.clockDomain.name) {
                throw MemoryFault(MemoryCodes.CLOCK_DOMAIN_MISMATCH)
            }
            if (event.idempotencyKey != null) {
                rawByKey(event.spaceId, event.idempotencyKey)?.id?.let { return@withTx it }
            }
            val id = newId()
            insertRaw(
                LedgerRawEntity(
                    id = id,
                    spaceId = event.spaceId,
                    ownerId = event.ownerId,
                    sessionId = event.sessionId,
                    taskScopeId = event.taskScopeId,
                    role = event.role,
                    content = event.content,
                    clockDomain = event.clockDomain.name,
                    occurredAt = event.occurredAt,
                    capturedAt = clock(),
                    processingState = "PENDING",
                    contentHash = sha256Hex(event.content),
                    idempotencyKey = event.idempotencyKey,
                ),
            )
            id
        }
    }

    override suspend fun commit(batch: MemoryBatch): CommitResult = lock.withLock {
        try {
            dao.withTx {
                val space = space(batch.spaceId) ?: return@withTx fail(MemoryCodes.UNKNOWN_SPACE)
                val ids = mutableListOf<String>()
                for (command in batch.commands) {
                    when (command) {
                        is StateCommand -> ids += commitState(space, batch, command)
                        is EpisodeCommand -> ids += commitEpisode(space, batch, command)
                        is ReflectionCommand -> ids += commitReflection(space, batch, command)
                    }
                }
                for (rawId in batch.commitRawIds) {
                    val raw = raw(rawId) ?: return@withTx fail(MemoryCodes.SOURCE_NOT_FOUND)
                    if (raw.spaceId != batch.spaceId) return@withTx fail(MemoryCodes.SOURCE_NOT_FOUND)
                    setRawState(rawId, "COMMITTED")
                }
                CommitResult(ok = true, itemIds = ids)
            }
        } catch (fault: MemoryFault) {
            fail(fault.code, fault.message ?: fault.code)
        }
    }

    override suspend fun getStates(request: StateReadRequest): StateReadResult = lock.withLock {
        readStates(request)
    }

    private suspend fun readStates(request: StateReadRequest): StateReadResult {
        val space = dao.space(request.spaceId) ?: return StateReadResult(emptyMap(), mapOf("_" to err(MemoryCodes.UNKNOWN_SPACE)))
        if (space.clockDomain != request.at.domain.name) {
            return StateReadResult(emptyMap(), mapOf("_" to err(MemoryCodes.CLOCK_DOMAIN_MISMATCH)))
        }
        val states = linkedMapOf<String, StateSnapshot>()
        val errors = linkedMapOf<String, MemoryError>()
        for (selector in request.selectors) {
            val canonical = dao.resolveFieldId(request.spaceId, selector.fieldId)
            if (canonical == null) {
                errors[selector.fieldId] = err(MemoryCodes.UNKNOWN_FIELD)
                continue
            }
            val own = dao.pickAsOf(
                request.spaceId,
                request.ownerId,
                canonical,
                request.at.t,
                request.sessionId,
                request.taskScopeId,
            )
            val conflict = request.includeOwners.any { other ->
                if (other == request.ownerId) return@any false
                val theirs = dao.pickAsOf(
                    request.spaceId,
                    other,
                    canonical,
                    request.at.t,
                    request.sessionId,
                    request.taskScopeId,
                )
                theirs != null && own != null && theirs.payloadJson != own.payloadJson
            }
            if (conflict) {
                errors[selector.fieldId] = err(MemoryCodes.AMBIGUOUS_FIELD)
                continue
            }
            if (own != null) states[canonical] = own.toSnapshot(canonical)
        }
        return StateReadResult(states, errors)
    }

    override suspend fun getStateHistory(request: StateHistoryRequest): List<StateVersion> = lock.withLock {
        val canonical = dao.resolveFieldId(request.spaceId, request.fieldId) ?: return emptyList()
        dao.stateVersions(request.spaceId, request.ownerId, canonical).map { row ->
            StateVersion(
                itemId = row.id,
                isCurrent = row.isCurrent == 1,
                lifecycle = LifecycleState.valueOf(row.lifecycleState),
                text = row.text,
                validFrom = row.validFrom,
                validTo = row.validTo,
                writerKind = MemoryWriterKind.valueOf(row.writerKind),
            )
        }
    }

    override suspend fun recall(request: RecallRequest): RecallResult = lock.withLock {
        if (request.requiredFields.isNotEmpty() &&
            (request.contextContractId.isNullOrBlank() || request.contextContractVersion.isNullOrBlank())
        ) {
            return RecallResult(
                status = RecallStatus.BLOCKED,
                required = emptyMap(),
                hits = emptyList(),
                blocked = listOf(err(MemoryCodes.REQUIRED_CONTRACT)),
            )
        }
        val selectors = request.requiredFields.map { relay.memory.api.StateSelector(it.fieldId) }.toSet()
        val read = if (selectors.isEmpty()) {
            StateReadResult(emptyMap())
        } else {
            readStates(
                StateReadRequest(
                    spaceId = request.spaceId,
                    ownerId = request.ownerId,
                    at = request.at,
                    selectors = selectors,
                    includeOwners = request.includeOwners,
                    sessionId = request.sessionId,
                    taskScopeId = request.taskScopeId,
                ),
            )
        }
        val blocked = read.errors.values.toMutableList()
        val missingKnown = request.requiredFields.filter { req ->
            val canonical = dao.resolveFieldId(request.spaceId, req.fieldId)
            req.onMissing == OnMissing.BLOCK && canonical != null && canonical !in read.states && canonical !in read.errors
        }
        if (missingKnown.isNotEmpty()) {
            blocked += err("BLOCKED", missingKnown.joinToString { it.fieldId })
        }
        val hits = search(request)
        val status = if (blocked.isEmpty()) RecallStatus.READY else RecallStatus.BLOCKED
        RecallResult(status, read.states, hits, blocked)
    }

    override suspend fun putEmbedding(put: EmbeddingPut): Boolean = lock.withLock {
        val item = dao.item(put.memoryId) ?: return false
        val job = dao.embeddingJob(put.memoryId)
        val now = clock()
        if (put.vector.isEmpty() || (put.textHash != null && put.textHash != item.textHash)) {
            if (job != null) dao.setJobStatus(job.id, "FAILED", now)
            return false
        }
        dao.upsertModel(
            EmbeddingModelEntity(
                modelId = put.modelId,
                modelVersion = "1",
                dimensions = put.vector.size,
                tokenizerVersion = "",
                queryPrefix = "",
                documentPrefix = "",
                normalization = "l2",
                active = 1,
            ),
        )
        dao.upsertEmbedding(
            MemoryEmbeddingEntity(
                memoryId = put.memoryId,
                modelId = put.modelId,
                textHash = item.textHash,
                vectorBlob = put.vector.toBytes(),
                indexedAt = now,
            ),
        )
        if (job != null) dao.setJobStatus(job.id, "COMPLETED", now)
        true
    }

    override suspend fun indexHealth(spaceId: String): IndexHealth =
        IndexHealth(ftsOk = true, embeddingPending = dao.pendingJobs(spaceId))

    override suspend fun listItems(spaceId: String, ownerId: String): List<MemoryRecord> = lock.withLock {
        dao.itemsForOwners(spaceId, listOf(ownerId)).map { row ->
            MemoryRecord(
                itemId = row.id,
                kind = MemoryKind.valueOf(row.kind),
                ownerId = row.ownerId,
                fieldId = row.fieldId,
                text = row.text,
                scope = MemoryScope.valueOf(row.scope),
                scopeId = row.scopeId,
                lifecycle = LifecycleState.valueOf(row.lifecycleState),
            )
        }
    }

    override suspend fun pendingRawCount(spaceId: String): Int = lock.withLock {
        dao.pendingRawCount(spaceId)
    }

    fun close() {
        db.close()
    }

    suspend fun dropEmbeddingStorage() {
        lock.withLock { dao.deleteEmbeddings() }
    }

    private suspend fun LedgerDao.commitState(
        space: SpaceEntity,
        batch: MemoryBatch,
        command: StateCommand,
    ): String {
        if (command.validFrom.domain.name != space.clockDomain) {
            throw MemoryFault(MemoryCodes.CLOCK_DOMAIN_MISMATCH)
        }
        if (space.clockDomain == ClockDomain.STORY_TIME.name && command.validFrom.t == Long.MIN_VALUE) {
            throw MemoryFault(MemoryCodes.MISSING_CLOCK)
        }
        val fieldId = resolveFieldId(batch.spaceId, command.fieldId)
            ?: throw MemoryFault(MemoryCodes.UNKNOWN_FIELD)
        val spec = field(batch.spaceId, fieldId) ?: throw MemoryFault(MemoryCodes.UNKNOWN_FIELD)
        requireSources(command.sources, batch.spaceId)
        val writers = spec.allowedWriters.split(",").filter { it.isNotBlank() }.toSet()
        if (batch.writerKind.name !in writers) throw MemoryFault(MemoryCodes.WRITER_NOT_ALLOWED)
        val locked = spec.overwritePolicy == OverwritePolicy.USER_LOCK.name &&
            currentStates(batch.spaceId, batch.ownerId, fieldId).any { it.writerKind == MemoryWriterKind.USER_EDIT.name }
        if (locked &&
            batch.writerKind == MemoryWriterKind.EXTRACTOR &&
            command.targetLifecycle == TargetLifecycle.CURRENT &&
            !command.overrideUserEdit
        ) {
            throw MemoryFault(MemoryCodes.USER_LOCK)
        }
        if (spec.overwritePolicy == OverwritePolicy.EXTRACTOR_CANDIDATE_ONLY.name &&
            batch.writerKind == MemoryWriterKind.EXTRACTOR &&
            command.targetLifecycle == TargetLifecycle.CURRENT
        ) {
            throw MemoryFault(MemoryCodes.WRITER_NOT_ALLOWED)
        }
        val current = currentState(batch.spaceId, batch.ownerId, command.scope.name, command.scopeId, fieldId)
        if (command.expectedCurrentId != null && current?.id != command.expectedCurrentId) {
            throw MemoryFault(MemoryCodes.CAS_CONFLICT)
        }
        val now = clock()
        val currentLifecycle = if (command.targetLifecycle == TargetLifecycle.CURRENT) {
            LifecycleState.ACTIVE
        } else {
            LifecycleState.CANDIDATE
        }
        if (current != null && currentLifecycle == LifecycleState.ACTIVE) {
            closeCurrent(current.id, command.validFrom.t, now)
        }
        val id = newId()
        val payload = json.encodeToString(JsonObject.serializer(), command.payload)
        insertItem(
            MemoryItemEntity(
                id = id,
                spaceId = batch.spaceId,
                ownerId = batch.ownerId,
                kind = MemoryKind.STATE.name,
                fieldId = fieldId,
                memoryKey = null,
                payloadJson = payload,
                text = command.rendered.text,
                rendererId = command.rendered.rendererId,
                rendererVersion = command.rendered.rendererVersion,
                scope = command.scope.name,
                scopeId = command.scopeId,
                isCurrent = if (currentLifecycle == LifecycleState.ACTIVE) 1 else 0,
                lifecycleState = currentLifecycle.name,
                confidence = command.confidence,
                salience = 0.5,
                clockDomain = space.clockDomain,
                occurredAt = null,
                validFrom = command.validFrom.t,
                validTo = null,
                createdAt = now,
                updatedAt = now,
                supersedesId = current?.id,
                retractedAt = null,
                writerKind = batch.writerKind.name,
                writerId = batch.writerId,
                writerRunId = batch.writerRunId,
                mirroredSourceRevision = command.sourceRevision,
                payloadHash = sha256Hex(payload),
                textHash = sha256Hex(command.rendered.text),
                idempotencyKey = null,
            ),
        )
        attach(id, command.sources, command.tags, command.rendered.text)
        return id
    }

    private suspend fun LedgerDao.commitEpisode(
        space: SpaceEntity,
        batch: MemoryBatch,
        command: EpisodeCommand,
    ): String {
        val occurred = command.occurredAt ?: throw MemoryFault(MemoryCodes.MISSING_CLOCK)
        if (occurred.domain.name != space.clockDomain) {
            throw MemoryFault(MemoryCodes.CLOCK_DOMAIN_MISMATCH)
        }
        requireSources(command.sources, batch.spaceId)
        if (episodeByKey(batch.spaceId, batch.ownerId, command.idempotencyKey) != null) {
            throw MemoryFault(MemoryCodes.IDEMPOTENT_REPLAY)
        }
        val now = clock()
        val id = newId()
        val payload = json.encodeToString(JsonObject.serializer(), command.payload)
        insertItem(
            MemoryItemEntity(
                id = id,
                spaceId = batch.spaceId,
                ownerId = batch.ownerId,
                kind = MemoryKind.EPISODE.name,
                fieldId = null,
                memoryKey = null,
                payloadJson = payload,
                text = command.rendered.text,
                rendererId = command.rendered.rendererId,
                rendererVersion = command.rendered.rendererVersion,
                scope = command.scope.name,
                scopeId = command.scopeId,
                isCurrent = 0,
                lifecycleState = LifecycleState.ACTIVE.name,
                confidence = command.confidence,
                salience = 0.5,
                clockDomain = space.clockDomain,
                occurredAt = occurred.t,
                validFrom = null,
                validTo = null,
                createdAt = now,
                updatedAt = now,
                supersedesId = null,
                retractedAt = null,
                writerKind = batch.writerKind.name,
                writerId = batch.writerId,
                writerRunId = batch.writerRunId,
                mirroredSourceRevision = null,
                payloadHash = sha256Hex(payload),
                textHash = sha256Hex(command.rendered.text),
                idempotencyKey = command.idempotencyKey,
            ),
        )
        attach(id, command.sources, command.tags, command.rendered.text)
        return id
    }

    private suspend fun LedgerDao.commitReflection(
        space: SpaceEntity,
        batch: MemoryBatch,
        command: ReflectionCommand,
    ): String {
        if (command.validFrom.domain.name != space.clockDomain) {
            throw MemoryFault(MemoryCodes.CLOCK_DOMAIN_MISMATCH)
        }
        requireSources(command.sources, batch.spaceId)
        val currentLifecycle = if (command.targetLifecycle == TargetLifecycle.CURRENT) {
            LifecycleState.ACTIVE
        } else {
            LifecycleState.CANDIDATE
        }
        if (currentLifecycle == LifecycleState.ACTIVE && command.evidence.isEmpty()) {
            throw MemoryFault(MemoryCodes.MISSING_EVIDENCE)
        }
        for (ev in command.evidence) {
            val row = item(ev.memoryId) ?: throw MemoryFault(MemoryCodes.SOURCE_NOT_FOUND)
            if (row.spaceId != batch.spaceId) throw MemoryFault(MemoryCodes.SOURCE_NOT_FOUND)
        }
        val current = currentReflection(
            batch.spaceId,
            batch.ownerId,
            command.scope.name,
            command.scopeId,
            command.memoryKey,
        )
        if (command.expectedCurrentId != null && current?.id != command.expectedCurrentId) {
            throw MemoryFault(MemoryCodes.CAS_CONFLICT)
        }
        val now = clock()
        if (current != null && currentLifecycle == LifecycleState.ACTIVE) {
            closeCurrent(current.id, command.validFrom.t, now)
        }
        val id = newId()
        val payload = json.encodeToString(JsonObject.serializer(), command.payload)
        insertItem(
            MemoryItemEntity(
                id = id,
                spaceId = batch.spaceId,
                ownerId = batch.ownerId,
                kind = MemoryKind.REFLECTION.name,
                fieldId = null,
                memoryKey = command.memoryKey,
                payloadJson = payload,
                text = command.rendered.text,
                rendererId = command.rendered.rendererId,
                rendererVersion = command.rendered.rendererVersion,
                scope = command.scope.name,
                scopeId = command.scopeId,
                isCurrent = if (currentLifecycle == LifecycleState.ACTIVE) 1 else 0,
                lifecycleState = currentLifecycle.name,
                confidence = command.confidence,
                salience = 0.5,
                clockDomain = space.clockDomain,
                occurredAt = null,
                validFrom = command.validFrom.t,
                validTo = null,
                createdAt = now,
                updatedAt = now,
                supersedesId = current?.id,
                retractedAt = null,
                writerKind = batch.writerKind.name,
                writerId = batch.writerId,
                writerRunId = batch.writerRunId,
                mirroredSourceRevision = null,
                payloadHash = sha256Hex(payload),
                textHash = sha256Hex(command.rendered.text),
                idempotencyKey = null,
            ),
        )
        for (ev in command.evidence) {
            insertEvidence(MemoryEvidenceEntity(id, ev.memoryId, ev.relation))
        }
        attach(id, command.sources, command.tags, command.rendered.text)
        return id
    }

    private suspend fun LedgerDao.requireSources(
        sources: List<relay.memory.api.SourceRef>,
        spaceId: String,
    ) {
        if (sources.isEmpty()) throw MemoryFault(MemoryCodes.MISSING_SOURCE)
        for (src in sources) {
            if (src.type != SourceType.RAW_EVENT) continue
            val row = raw(src.id) ?: throw MemoryFault(MemoryCodes.SOURCE_NOT_FOUND)
            if (row.spaceId != spaceId) throw MemoryFault(MemoryCodes.SOURCE_NOT_FOUND)
        }
    }

    private suspend fun LedgerDao.attach(
        memoryId: String,
        sources: List<relay.memory.api.SourceRef>,
        tags: List<String>,
        text: String,
    ) {
        for (src in sources) {
            insertSource(MemorySourceEntity(memoryId, src.type.name, src.id))
        }
        for (tag in tags) insertTag(MemoryTagEntity(memoryId, tag))
        insertFts(memoryId, ftsIndexText(text))
        insertJob(
            IndexJobEntity(
                id = newId(),
                memoryId = memoryId,
                kind = "EMBEDDING",
                status = "PENDING",
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun search(request: RecallRequest): List<SearchHit> {
        val owners = (listOf(request.ownerId) + request.includeOwners).distinct()
        val items = dao.itemsForOwners(request.spaceId, owners).filter { item ->
            visibleInSearch(item, request)
        }
        val query = request.query.trim()
        val ftsHits = if (query.isEmpty()) {
            emptyList()
        } else {
            items.filter { it.text.contains(query) }.map {
                it.toHit("FTS")
            }
        }
        val vectorHits = vectorHits(request, items)
        val recent = items
            .sortedByDescending { it.occurredAt ?: it.validFrom ?: it.createdAt }
            .take(8)
            .map { it.toHit("RECENT") }
        return (ftsHits + vectorHits + recent).distinctBy { "${it.channel}:${it.itemId}" }
    }

    private suspend fun vectorHits(
        request: RecallRequest,
        items: List<MemoryItemEntity>,
    ): List<SearchHit> {
        val query = request.queryVector ?: return emptyList()
        if (query.isEmpty() || items.isEmpty()) return emptyList()
        return try {
            val embeddings = dao.embeddings(request.embeddingModelId, items.map { it.id })
            val byId = items.associateBy { it.id }
            embeddings.mapNotNull { row ->
                val item = byId[row.memoryId] ?: return@mapNotNull null
                val score = cosine(query, row.vectorBlob.toFloats())
                if (score >= 0.8) item.toHit("VECTOR") else null
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun visibleInSearch(item: MemoryItemEntity, request: RecallRequest): Boolean {
        val scopeOk = when (item.scope) {
            MemoryScope.PROFILE.name -> true
            MemoryScope.SESSION.name -> item.scopeId == request.sessionId && request.sessionId.isNotEmpty()
            MemoryScope.TASK.name -> item.scopeId == request.taskScopeId && request.taskScopeId.isNotEmpty()
            else -> false
        }
        if (!scopeOk) return false
        return when (item.kind) {
            MemoryKind.STATE.name, MemoryKind.REFLECTION.name ->
                item.lifecycleState == LifecycleState.ACTIVE.name &&
                    item.validFrom != null &&
                    item.validFrom <= request.at.t &&
                    (item.validTo == null || item.validTo > request.at.t)
            MemoryKind.EPISODE.name ->
                item.occurredAt != null && item.occurredAt <= request.at.t
            else -> false
        }
    }

    private suspend fun LedgerDao.resolveFieldId(spaceId: String, name: String): String? {
        alias(spaceId, name)?.let { return it.canonicalFieldId }
        field(spaceId, name)?.let { return it.fieldId }
        return null
    }

    private suspend fun LedgerDao.pickAsOf(
        spaceId: String,
        ownerId: String,
        fieldId: String,
        at: Long,
        sessionId: String,
        taskScopeId: String,
    ): MemoryItemEntity? {
        val live = stateVersions(spaceId, ownerId, fieldId).filter { row ->
            row.lifecycleState == LifecycleState.ACTIVE.name &&
                row.validFrom != null &&
                row.validFrom <= at &&
                (row.validTo == null || row.validTo > at)
        }
        fun pick(scope: String, scopeId: String) =
            live.filter { it.scope == scope && (scope == MemoryScope.PROFILE.name || it.scopeId == scopeId) }
                .maxByOrNull { it.validFrom ?: 0L }
        return pick(MemoryScope.SESSION.name, sessionId)
            ?: pick(MemoryScope.TASK.name, taskScopeId)
            ?: pick(MemoryScope.PROFILE.name, "")
    }

    private fun MemoryItemEntity.toSnapshot(fieldId: String) = StateSnapshot(
        itemId = id,
        fieldId = fieldId,
        ownerId = ownerId,
        payload = json.decodeFromString(JsonObject.serializer(), payloadJson),
        text = text,
        scope = MemoryScope.valueOf(scope),
        scopeId = scopeId,
        validFrom = validFrom,
        validTo = validTo,
    )

    private fun MemoryItemEntity.toHit(channel: String) = SearchHit(
        itemId = id,
        kind = MemoryKind.valueOf(kind),
        ownerId = ownerId,
        text = text,
        channel = channel,
    )

    private fun StateFieldSpec.toEntity(now: Long) = StateFieldEntity(
        spaceId = spaceId,
        fieldId = fieldId,
        createdBy = "HOST_SEED",
        valueContract = contract.json,
        allowedWriters = allowedWriters.joinToString(",") { it.name },
        riskTier = riskTier.name,
        authorityMode = authorityMode.name,
        projectionMode = projectionMode.name,
        overwritePolicy = overwritePolicy.name,
        createdAt = now,
    )

    private fun fail(code: String, message: String = code) =
        CommitResult(ok = false, error = MemoryError(code, message))

    private fun err(code: String, message: String = code) = MemoryError(code, message)

    private fun newId(): String = UUID.randomUUID().toString()
}

private fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

private fun ByteArray.toFloats(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.float }
}

private fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size || a.isEmpty()) return 0.0
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom == 0.0) 0.0 else dot / denom
}
