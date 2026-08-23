package relay.memory.engine

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import relay.memory.AliasRec
import relay.memory.CleanTriple
import relay.memory.EdgeRec
import relay.memory.ExtractOutcome
import relay.memory.ExtractionRunRec
import relay.memory.Fact
import relay.memory.FactLogRec
import relay.memory.ClaimDraft
import relay.memory.IngestError
import relay.memory.IngestResult
import relay.memory.MemoryHit
import relay.memory.MemoryStore
import relay.memory.NodeRec
import relay.memory.OpenClaim
import relay.memory.RawEvent
import relay.memory.RawTurn
import relay.memory.ReviewItem
import relay.memory.Snapshot
import relay.memory.TripleDraft
import relay.memory.graphFunctionalPredicates
import relay.memory.graphPredicates
import relay.memory.predicateLabel

open class SqliteMemoryStore(
    private val room: MemoryRoomDatabase,
    private val artifacts: ArtifactStore = MemoryArtifactStore(),
) : MemoryStore {
    private val lock = Mutex()
    private val dao get() = room.memory()

    constructor(context: Context) : this(RoomMemoryDb.inMemory(context), MemoryArtifactStore())
    constructor(context: Context, file: File) : this(
        RoomMemoryDb.file(context, file),
        FileArtifactStore(File((file.absoluteFile.parentFile ?: File(".")), "${file.name}.artifacts")),
    )

    override suspend fun capture(turn: RawTurn): String = lock.withLock {
        requireGraph(turn.graphId)
        val id = newId()
        val textRef = artifacts.put(turn.text)
        dao.withTx {
            insertRaw(
                RawEventEntity(
                    id = id,
                    graphId = turn.graphId,
                    ts = turn.ts,
                    sessionId = turn.sessionId,
                    role = turn.role,
                    textRef = textRef,
                    source = turn.source,
                    consumed = 0,
                ),
            )
            ensureUser(turn.graphId)
        }
        id
    }

    override suspend fun ingest(drafts: List<TripleDraft>): IngestResult = lock.withLock {
        dao.withTx { ingestDrafts(drafts) }
    }

    override suspend fun ingestClaims(
        sessionId: String,
        runId: String,
        drafts: List<ClaimDraft>,
    ): List<OpenClaim> = lock.withLock {
        dao.withTx { insertClaimDrafts(sessionId, runId, drafts, System.currentTimeMillis()) }
    }

    override suspend fun queryClaims(
        graphId: String,
        text: String,
        budgetChars: Int,
    ): List<OpenClaim> = lock.withLock {
        requireGraph(graphId)
        val match = ftsMatch(queryTokens(text), normalizeText(text)) ?: return@withLock emptyList()
        dao.withTx {
            val ids = claimFtsIds(match, graphId).map { it.claimId }.distinct()
            if (ids.isEmpty()) return@withTx emptyList()
            val ranked = claimsByIds(ids)
                .map { it.toModel() }
                .distinctBy { normalizeText(it.text) }
                .sortedByDescending { claimScore(it) }
            val result = mutableListOf<OpenClaim>()
            var used = 0
            for (claim in ranked) {
                val size = claim.text.length + claim.subject.length + 3
                if (result.isNotEmpty() && used + size > budgetChars) break
                result += claim
                used += size
            }
            result
        }
    }

    override suspend fun claims(graphId: String): List<OpenClaim> = lock.withLock {
        requireGraph(graphId)
        dao.withTx { claims(graphId).map { it.toModel() } }
    }

    override suspend fun startExtractionRun(
        graphId: String,
        sessionId: String,
        eventIds: List<String>,
        contextEventIds: List<String>,
    ): String = lock.withLock {
        requireGraph(graphId)
        val id = newId()
        dao.withTx {
            insertExtractionRun(
                ExtractionRunEntity(
                    id = id,
                    graphId = graphId,
                    sessionId = sessionId,
                    status = "RUNNING",
                    eventIds = encodeIds(eventIds),
                    contextEventIds = encodeIds(contextEventIds),
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    responseRef = "",
                    error = "",
                ),
            )
        }
        id
    }

    override suspend fun finishExtractionRun(
        runId: String,
        outcome: ExtractOutcome,
        rawResponse: String,
        errors: List<String>,
    ) = lock.withLock {
        val responseRef = rawResponse.takeIf { it.isNotEmpty() }?.let { artifacts.put(it) }.orEmpty()
        dao.withTx {
            finishExtractionRun(
                id = runId,
                status = outcome.name,
                finishedAt = System.currentTimeMillis(),
                responseRef = responseRef,
                error = errors.joinToString("\n"),
            )
        }
    }

    override suspend fun commitExtraction(
        graphId: String,
        sessionId: String,
        runId: String,
        eventIds: List<String>,
        claims: List<ClaimDraft>,
        drafts: List<TripleDraft>,
        outcome: ExtractOutcome,
        rawResponse: String,
    ): IngestResult = lock.withLock {
        requireGraph(graphId)
        require(outcome == ExtractOutcome.SUCCESS || outcome == ExtractOutcome.SUCCESS_EMPTY)
        val responseRef = rawResponse.takeIf { it.isNotEmpty() }?.let { artifacts.put(it) }.orEmpty()
        val now = System.currentTimeMillis()
        dao.withTx {
            insertClaimDrafts(sessionId, runId, claims, now)
            val result = ingestDrafts(drafts)
            markConsumedIds(graphId, eventIds)
            finishExtractionRun(
                id = runId,
                status = outcome.name,
                finishedAt = now,
                responseRef = responseRef,
                error = result.errors.joinToString("\n") { "${it.p}: ${it.reason}" },
            )
            result
        }
    }

    override suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int,
        at: Long,
    ): MemoryHit = lock.withLock {
        requireGraph(graphId)
        val tokens = queryTokens(text)
        if (tokens.isEmpty() && text.isBlank()) return@withLock MemoryHit()
        dao.withTx {
            val qn = normalizeText(text)
            val nodeIds = matchNodes(graphId, tokens, qn)
            val predicateHits = graphPredicates(graphId).filter { pred ->
                val label = nfkcCompact(predicateLabel(pred))
                label.isNotEmpty() && label in qn
            }
            val edges = validEdges(graphId, at).map { it.toRec() }.filter { edge ->
                edge.srcId in nodeIds ||
                    edge.dstId in nodeIds ||
                    edge.relation in predicateHits
            }
            val ranked = edges.sortedByDescending { score(it) }
            val facts = mutableListOf<Fact>()
            var used = 0
            for (edge in ranked) {
                val fact = toFact(edge)
                val line = fact.line()
                if (facts.isNotEmpty() && used + line.length + 1 > budgetChars) break
                facts += fact
                used += line.length + 1
            }
            MemoryHit(facts)
        }
    }

    override suspend fun forget(graphId: String, now: Long) = lock.withLock {
        requireGraph(graphId)
        val horizon = now - THIRTY_DAYS_MS
        dao.withTx { forgetStale(graphId, now, horizon) }
    }

    override suspend fun pendingReview(graphId: String): List<ReviewItem> = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            reviews(graphId).map {
                ReviewItem(it.edgeId, it.reason, it.confidence, it.s, it.p, it.o)
            }
        }
    }

    override suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean) = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            deleteReview(edgeId)
            if (!accept) expireEdgeInGraph(graphId, edgeId, System.currentTimeMillis())
        }
    }

    override suspend fun rebuildFromFactLog(graphId: String) = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            val replay = factLog(graphId)
            deleteReviewsForGraph(graphId)
            deleteFtsGraph(graphId)
            deleteAliases(graphId)
            deleteEdges(graphId)
            deleteNodes(graphId)
            ensureUser(graphId)
            for (row in replay) {
                val triple = CleanTriple(row.s, row.p, row.o, retract = row.retract != 0)
                val ts = row.ts
                val validAt = row.validAt.takeIf { it > 0 } ?: ts
                if (triple.retract) {
                    retractOne(
                        graphId,
                        triple,
                        decodeIds(row.rawEventIds),
                        row.confidence,
                        log = false,
                        at = ts,
                        invalidAt = row.invalidAt,
                    )
                } else {
                    ingestOne(
                        graphId,
                        triple,
                        decodeIds(row.rawEventIds),
                        row.confidence,
                        log = false,
                        at = ts,
                        validAt = validAt,
                        invalidAt = row.invalidAt,
                    )
                }
            }
        }
    }

    override suspend fun unconsumed(graphId: String): List<RawEvent> = lock.withLock {
        requireGraph(graphId)
        dao.withTx { unconsumed(graphId).map { it.toModel() } }
    }

    override suspend fun unconsumed(graphId: String, sessionId: String): List<RawEvent> = lock.withLock {
        requireGraph(graphId)
        dao.withTx { unconsumedInSession(graphId, sessionId).map { it.toModel() } }
    }

    override suspend fun contextBefore(
        graphId: String,
        sessionId: String,
        beforeTs: Long,
        limit: Int,
    ): List<RawEvent> = lock.withLock {
        requireGraph(graphId)
        if (limit <= 0) return@withLock emptyList()
        dao.withTx {
            eventsBefore(graphId, sessionId, beforeTs, limit)
                .asReversed()
                .map { it.toModel() }
        }
    }

    override suspend fun markConsumed(graphId: String, ids: List<String>) = lock.withLock {
        requireGraph(graphId)
        dao.withTx { markConsumedIds(graphId, ids) }
    }

    override suspend fun facts(
        graphId: String,
        at: Long,
        p: String?,
        node: String?,
    ): MemoryHit = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            val wantNode = node?.let { normalizeText(it) }?.takeIf { it.isNotEmpty() }
            val edges = validEdges(graphId, at).map { it.toRec() }
                .filter { edge ->
                    (p == null || edge.relation == p) &&
                        (wantNode == null || nameOf(edge.srcId) == wantNode || nameOf(edge.dstId) == wantNode)
                }
                .sortedByDescending { it.updatedAt }
            MemoryHit(edges.map { toFact(it) })
        }
    }

    override suspend fun recent(graphId: String, since: Long): MemoryHit = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            MemoryHit(recentEdges(graphId, since).map { toFact(it.toRec()) })
        }
    }

    override suspend fun neighborhood(graphId: String, nodeNames: List<String>): MemoryHit = lock.withLock {
        requireGraph(graphId)
        val names = nodeNames.map { normalizeText(it) }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return@withLock MemoryHit()
        dao.withTx {
            val at = System.currentTimeMillis()
            val edges = validEdges(graphId, at).map { it.toRec() }.filter { edge ->
                nameOf(edge.srcId) in names || nameOf(edge.dstId) in names
            }
            MemoryHit(edges.map { toFact(it) })
        }
    }

    override suspend fun mergeNodes(graphId: String, keep: String, drop: String) = lock.withLock {
        requireGraph(graphId)
        dao.withTx {
            val kn = normalizeText(keep)
            val dn = normalizeText(drop)
            if (kn.isEmpty() || dn.isEmpty() || kn == dn) return@withTx
            val dropId = findNode(graphId, dn) ?: return@withTx
            val keepId = resolveNode(graphId, kn)
            if (keepId == dropId) return@withTx
            val now = System.currentTimeMillis()
            val live = validEdges(graphId, now).map { it.toRec() }.filter { it.srcId == dropId || it.dstId == dropId }
            for (edge in live) {
                expireEdge(edge.id, now)
                val newSrc = if (edge.srcId == dropId) keepId else edge.srcId
                val newDst = if (edge.dstId == dropId) keepId else edge.dstId
                if (newSrc == newDst) continue
                if (liveTriple(graphId, newSrc, newDst, edge.relation, now) != null) continue
                val edgeId = newId()
                insertEdge(
                    EdgeEntity(
                        id = edgeId,
                        graphId = graphId,
                        src = newSrc,
                        dst = newDst,
                        relation = edge.relation,
                        confidence = edge.confidence,
                        createdAt = now,
                        expiredAt = null,
                        validAt = edge.validAt,
                        invalidAt = edge.invalidAt,
                        updatedAt = now,
                        provenance = encodeIds(edge.provenance),
                    ),
                )
                insertFact(
                    FactLogEntity(
                        id = newId(),
                        graphId = graphId,
                        ts = now,
                        s = nameOf(newSrc),
                        p = edge.relation,
                        o = nameOf(newDst),
                        confidence = edge.confidence,
                        rawEventIds = encodeIds(edge.provenance),
                        retract = 0,
                        validAt = edge.validAt,
                        invalidAt = edge.invalidAt,
                    ),
                )
            }
            upsertAlias(NodeAliasEntity(graphId, dn, keepId))
            refreshFts(graphId, keepId, nameOf(keepId))
        }
    }

    suspend fun snapshot(): String = lock.withLock {
        dao.withTx {
            SNAPSHOT_JSON.encodeToString(
                Snapshot(
                    nodes = allNodes().map { NodeRec(it.id, it.graphId, it.type, it.canonicalName) },
                    aliases = allAliases().map { AliasRec(it.graphId, it.alias, it.nodeId) },
                    edges = allEdges().map { it.toRec() },
                    raw = allRaw().map { it.toModel() },
                    factLog = allFactLog().map {
                        FactLogRec(
                            it.id, it.graphId, it.ts, it.s, it.p, it.o, it.confidence,
                            decodeIds(it.rawEventIds), it.retract != 0, it.validAt, it.invalidAt,
                        )
                    },
                    reviews = allReviews().map {
                        ReviewItem(it.edgeId, it.reason, it.confidence, it.s, it.p, it.o)
                    },
                    claims = allClaims().map { it.toModel() },
                    extractionRuns = allExtractionRuns().map {
                        ExtractionRunRec(
                            id = it.id,
                            graphId = it.graphId,
                            sessionId = it.sessionId,
                            status = it.status,
                            eventIds = decodeIds(it.eventIds),
                            contextEventIds = decodeIds(it.contextEventIds),
                            startedAt = it.startedAt,
                            finishedAt = it.finishedAt,
                            response = artifacts.get(it.responseRef).orEmpty(),
                            error = it.error,
                        )
                    },
                ),
            )
        }
    }

    suspend fun restore(json: String) = lock.withLock {
        val snap = SNAPSHOT_JSON.decodeFromString<Snapshot>(json)
        dao.withTx {
            deleteAllReviews()
            deleteAllClaimFts()
            deleteAllFts()
            deleteAllAliases()
            deleteAllEdges()
            deleteAllNodes()
            deleteAllFactLog()
            deleteAllClaims()
            deleteAllExtractionRuns()
            deleteAllRaw()
            for (node in snap.nodes) {
                insertNode(NodeEntity(node.id, node.graphId, node.type, node.canonical))
            }
            for (alias in snap.aliases) {
                upsertAlias(NodeAliasEntity(alias.graphId, alias.alias, alias.nodeId))
            }
            for (edge in snap.edges) {
                insertEdge(
                    EdgeEntity(
                        id = edge.id,
                        graphId = edge.graphId,
                        src = edge.srcId,
                        dst = edge.dstId,
                        relation = edge.relation,
                        confidence = edge.confidence,
                        createdAt = edge.createdAt,
                        expiredAt = edge.expiredAt,
                        validAt = edge.validAt,
                        invalidAt = edge.invalidAt,
                        updatedAt = edge.updatedAt,
                        provenance = encodeIds(edge.provenance),
                    ),
                )
            }
            for (event in snap.raw) {
                val textRef = if (event.text.isNotEmpty()) artifacts.put(event.text) else event.textRef
                insertRaw(
                    RawEventEntity(
                        id = event.id,
                        graphId = event.graphId,
                        ts = event.ts,
                        sessionId = event.sessionId,
                        role = event.role,
                        textRef = textRef,
                        source = event.source,
                        consumed = if (event.consumed) 1 else 0,
                    ),
                )
            }
            for (row in snap.factLog) {
                insertFact(
                    FactLogEntity(
                        id = row.id,
                        graphId = row.graphId,
                        ts = row.ts,
                        s = row.s,
                        p = row.p,
                        o = row.o,
                        confidence = row.confidence,
                        rawEventIds = encodeIds(row.rawEventIds),
                        retract = if (row.retract) 1 else 0,
                        validAt = row.validAt,
                        invalidAt = row.invalidAt,
                    ),
                )
            }
            for (review in snap.reviews) {
                upsertReview(
                    PendingReviewEntity(review.edgeId, review.reason, review.confidence, review.s, review.p, review.o),
                )
            }
            for (claim in snap.claims) {
                val row = ClaimLogEntity(
                    id = claim.id,
                    graphId = claim.graphId,
                    sessionId = claim.sessionId,
                    runId = claim.runId,
                    subject = claim.subject,
                    text = claim.text,
                    confidence = claim.confidence,
                    rawEventIds = encodeIds(claim.rawEventIds),
                    createdAt = claim.createdAt,
                )
                insertClaim(row)
                insertClaimFts(
                    claimId = row.id,
                    graphId = row.graphId,
                    subject = ftsIndexText(row.subject),
                    text = ftsIndexText(row.text),
                )
            }
            for (run in snap.extractionRuns) {
                insertExtractionRun(
                    ExtractionRunEntity(
                        id = run.id,
                        graphId = run.graphId,
                        sessionId = run.sessionId,
                        status = run.status,
                        eventIds = encodeIds(run.eventIds),
                        contextEventIds = encodeIds(run.contextEventIds),
                        startedAt = run.startedAt,
                        finishedAt = run.finishedAt,
                        responseRef = run.response.takeIf { it.isNotEmpty() }?.let { artifacts.put(it) }.orEmpty(),
                        error = run.error,
                    ),
                )
            }
            for (node in snap.nodes) refreshFts(node.graphId, node.id, node.canonical)
        }
    }

    fun close() = room.close()

    private suspend fun MemoryDao.insertClaimDrafts(
        sessionId: String,
        runId: String,
        drafts: List<ClaimDraft>,
        now: Long,
    ): List<OpenClaim> = drafts.map { draft ->
        requireGraph(draft.graphId)
        require(draft.subject.isNotBlank()) { "claim subject required" }
        require(draft.text.isNotBlank()) { "claim text required" }
        val row = ClaimLogEntity(
            id = newId(),
            graphId = draft.graphId,
            sessionId = sessionId,
            runId = runId,
            subject = draft.subject.trim(),
            text = draft.text.trim(),
            confidence = draft.confidence,
            rawEventIds = encodeIds(draft.rawEventIds),
            createdAt = now,
        )
        insertClaim(row)
        insertClaimFts(
            claimId = row.id,
            graphId = row.graphId,
            subject = ftsIndexText(row.subject),
            text = ftsIndexText(row.text),
        )
        row.toModel()
    }

    private suspend fun MemoryDao.ingestDrafts(drafts: List<TripleDraft>): IngestResult {
        val errors = mutableListOf<IngestError>()
        for (draft in drafts) {
            requireGraph(draft.graphId)
            val triple = CleanTriple(draft.s.trim(), draft.p.trim(), draft.o.trim(), draft.retract)
            val reason = when {
                triple.s.isEmpty() || triple.p.isEmpty() || triple.o.isEmpty() -> "empty field"
                triple.p !in graphPredicates(draft.graphId) -> "unknown predicate"
                else -> null
            }
            if (reason != null) {
                errors += IngestError(draft.graphId, draft.s, draft.p, draft.o, reason)
                continue
            }
            if (triple.retract) {
                retractOne(draft.graphId, triple, draft.rawEventIds, draft.confidence, invalidAt = draft.invalidAt)
            } else {
                ingestOne(
                    draft.graphId,
                    triple,
                    draft.rawEventIds,
                    draft.confidence,
                    validAt = draft.validAt,
                    invalidAt = draft.invalidAt,
                )
            }
            if (draft.rawEventIds.isNotEmpty()) {
                markConsumedIds(draft.graphId, draft.rawEventIds)
            }
        }
        return IngestResult(errors)
    }

    private suspend fun MemoryDao.ingestOne(
        graphId: String,
        triple: CleanTriple,
        rawEventIds: List<String>,
        confidence: Double,
        log: Boolean = true,
        at: Long = System.currentTimeMillis(),
        validAt: Long? = null,
        invalidAt: Long? = null,
    ) {
        val srcId = resolveNode(graphId, triple.s)
        val dstId = resolveNode(graphId, triple.o)
        val worldStart = validAt ?: at
        var superseded = false
        if (triple.p in graphFunctionalPredicates(graphId)) {
            for (old in superseded(graphId, srcId, triple.p, dstId, at)) {
                archiveWorld(old.id, at, worldStart)
                superseded = true
            }
        }
        val existing = liveTriple(graphId, srcId, dstId, triple.p, at)
        val edgeId: String
        if (existing != null) {
            edgeId = existing.id
            val provenance = (decodeIds(existing.provenance) + rawEventIds).distinct()
            touchEdge(edgeId, maxOf(existing.confidence, confidence), at, encodeIds(provenance))
        } else {
            edgeId = newId()
            insertEdge(
                EdgeEntity(
                    id = edgeId,
                    graphId = graphId,
                    src = srcId,
                    dst = dstId,
                    relation = triple.p,
                    confidence = confidence,
                    createdAt = at,
                    expiredAt = null,
                    validAt = worldStart,
                    invalidAt = invalidAt,
                    updatedAt = at,
                    provenance = encodeIds(rawEventIds),
                ),
            )
        }
        if (log) {
            insertFact(
                FactLogEntity(
                    id = newId(),
                    graphId = graphId,
                    ts = at,
                    s = triple.s,
                    p = triple.p,
                    o = triple.o,
                    confidence = confidence,
                    rawEventIds = encodeIds(rawEventIds),
                    retract = 0,
                    validAt = worldStart,
                    invalidAt = invalidAt,
                ),
            )
        }
        if (superseded) {
            deleteReview(edgeId)
            upsertReview(PendingReviewEntity(edgeId, "supersedes", confidence, triple.s, triple.p, triple.o))
        }
    }

    private suspend fun MemoryDao.retractOne(
        graphId: String,
        triple: CleanTriple,
        rawEventIds: List<String>,
        confidence: Double,
        log: Boolean = true,
        at: Long = System.currentTimeMillis(),
        invalidAt: Long? = null,
    ) {
        val worldEnd = invalidAt ?: at
        val srcId = nodeByName(graphId, normalizeText(triple.s))?.id
        val dstId = nodeByName(graphId, normalizeText(triple.o))?.id
        if (srcId != null && dstId != null) {
            for (edge in openTriple(graphId, srcId, dstId, triple.p)) {
                archiveWorld(edge.id, at, worldEnd)
            }
        }
        if (log) {
            insertFact(
                FactLogEntity(
                    id = newId(),
                    graphId = graphId,
                    ts = at,
                    s = triple.s,
                    p = triple.p,
                    o = triple.o,
                    confidence = confidence,
                    rawEventIds = encodeIds(rawEventIds),
                    retract = 1,
                    validAt = at,
                    invalidAt = worldEnd,
                ),
            )
        }
    }

    private suspend fun MemoryDao.resolveNode(graphId: String, name: String): String {
        val n = normalizeText(name)
        if (n == "用户" || n == "user") return ensureUser(graphId)
        aliasNodeId(graphId, n)?.let { return it }
        nodeByName(graphId, n)?.let { return it.id }
        val id = newId()
        insertNode(NodeEntity(id, graphId, "other", n))
        upsertAlias(NodeAliasEntity(graphId, n, id))
        refreshFts(graphId, id, n)
        return id
    }

    private suspend fun MemoryDao.findNode(graphId: String, name: String): String? =
        aliasNodeId(graphId, name) ?: nodeByName(graphId, name)?.id

    private suspend fun MemoryDao.ensureUser(graphId: String): String {
        val id = userId(graphId)
        if (nodeById(id) == null) {
            insertNode(NodeEntity(id, graphId, "person", "用户"))
            upsertAlias(NodeAliasEntity(graphId, "用户", id))
            upsertAlias(NodeAliasEntity(graphId, "user", id))
            refreshFts(graphId, id, "用户")
        }
        return id
    }

    private suspend fun MemoryDao.matchNodes(graphId: String, tokens: Set<String>, qn: String): Set<String> {
        val match = ftsMatch(tokens, qn) ?: return emptySet()
        return ftsNodeIds(match, graphId).map { it.nodeId }.toSet()
    }

    private fun ftsMatch(tokens: Set<String>, qn: String): String? {
        val parts = (tokens + qn).map { it.trim() }.filter { it.length >= 2 }.distinct()
        if (parts.isEmpty()) return null
        return parts.joinToString(" OR ") { token ->
            val safe = token.replace("\"", "").replace("*", "")
            "\"$safe\""
        }
    }

    private suspend fun MemoryDao.refreshFts(graphId: String, nodeId: String, canonical: String) {
        val aliases = aliasesOf(graphId, nodeId).joinToString(" ")
        deleteFts(nodeId)
        insertFts(nodeId, graphId, ftsIndexText(canonical), ftsIndexText(aliases))
    }

    private suspend fun MemoryDao.toFact(edge: EdgeRec): Fact = Fact(
        s = nameOf(edge.srcId),
        p = edge.relation,
        o = nameOf(edge.dstId),
        edgeId = edge.id,
    )

    private suspend fun MemoryDao.nameOf(nodeId: String): String =
        nodeById(nodeId)?.canonicalName ?: nodeId

    private suspend fun MemoryDao.markConsumedIds(graphId: String, ids: List<String>) {
        for (id in ids) markConsumed(graphId, id)
    }

    private fun RawEventEntity.toModel(): RawEvent = RawEvent(
        id = id,
        graphId = graphId,
        ts = ts,
        sessionId = sessionId,
        role = role,
        text = artifacts.get(textRef).orEmpty(),
        source = source,
        consumed = consumed != 0,
        textRef = textRef,
    )

    private fun ClaimLogEntity.toModel(): OpenClaim = OpenClaim(
        id = id,
        graphId = graphId,
        sessionId = sessionId,
        runId = runId,
        subject = subject,
        text = text,
        confidence = confidence,
        rawEventIds = decodeIds(rawEventIds),
        createdAt = createdAt,
    )

    private fun claimScore(claim: OpenClaim): Double {
        val ageDays = (System.currentTimeMillis() - claim.createdAt).coerceAtLeast(0) / 86_400_000.0
        return claim.confidence / (1.0 + ageDays)
    }

    private fun score(edge: EdgeRec): Double {
        val recency = 1.0 / (1.0 + (System.currentTimeMillis() - edge.updatedAt).coerceAtLeast(0) / 86_400_000.0)
        return edge.confidence * recency
    }

    private fun requireGraph(graphId: String) {
        require(graphId.isNotBlank()) { "graphId required" }
    }

    private fun userId(graphId: String) = "$graphId:user"

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        val SNAPSHOT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

class InMemoryMemoryStore(context: Context) : SqliteMemoryStore(context)

internal fun EdgeEntity.toRec(): EdgeRec = EdgeRec(
    id = id,
    graphId = graphId,
    srcId = src,
    dstId = dst,
    relation = relation,
    confidence = confidence,
    createdAt = createdAt,
    expiredAt = expiredAt,
    validAt = validAt,
    invalidAt = invalidAt,
    updatedAt = updatedAt,
    provenance = decodeIds(provenance),
)

internal fun encodeIds(ids: List<String>): String = Json.encodeToString(ids)

internal fun decodeIds(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else Json.decodeFromString(raw)
