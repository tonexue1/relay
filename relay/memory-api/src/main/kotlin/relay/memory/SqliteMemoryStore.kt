package relay.memory

import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

open class SqliteMemoryStore(
    private val db: MemoryDb,
    private val artifacts: ArtifactStore = MemoryArtifactStore(),
) : MemoryStore {
    private val lock = Mutex()

    constructor() : this(JdbcMemoryDb.inMemory(), MemoryArtifactStore())
    constructor(file: File) : this(
        JdbcMemoryDb.file(file),
        FileArtifactStore(File((file.absoluteFile.parentFile ?: File(".")), "${file.name}.artifacts")),
    )

    init {
        db.transaction {
            for (stmt in MEMORY_SCHEMA) exec(stmt)
        }
    }

    override suspend fun capture(turn: RawTurn): String = lock.withLock {
        requireGraph(turn.graphId)
        val id = newId()
        val textRef = artifacts.put(turn.text)
        db.transaction {
            exec(
                """
                INSERT INTO raw_event(id, graph_id, ts, session_id, role, text_ref, source, consumed, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
                """.trimIndent(),
                id, turn.graphId, turn.ts, turn.sessionId, turn.role, textRef, turn.source, turn.scope,
            )
            ensureUser(this, turn.graphId)
        }
        id
    }

    override suspend fun ingest(drafts: List<TripleDraft>): IngestResult = lock.withLock {
        val errors = mutableListOf<IngestError>()
        db.transaction {
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
                    retractOne(
                        this,
                        draft.graphId,
                        triple,
                        draft.rawEventIds,
                        draft.confidence,
                        invalidAt = draft.invalidAt,
                    )
                } else {
                    ingestOne(
                        this,
                        draft.graphId,
                        triple,
                        draft.rawEventIds,
                        draft.confidence,
                        validAt = draft.validAt,
                        invalidAt = draft.invalidAt,
                    )
                }
                if (draft.rawEventIds.isNotEmpty()) {
                    markConsumed(this, draft.graphId, draft.rawEventIds)
                }
            }
        }
        IngestResult(errors)
    }

    override suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int,
        principal: String,
        at: Long,
    ): MemoryHit = lock.withLock {
        requireGraph(graphId)
        val tokens = queryTokens(text)
        if (tokens.isEmpty() && text.isBlank()) return@withLock MemoryHit()
        db.transaction {
            val qn = normalizeText(text)
            val nodeIds = matchNodes(this, graphId, tokens, qn)
            val predicateHits = graphPredicates(graphId).filter { pred ->
                val label = nfkcCompact(predicateLabel(pred))
                label.isNotEmpty() && label in qn
            }
            val edges = loadValidEdges(this, graphId, at).filter { edge ->
                scopeAllowed(edge.scope, principal) &&
                    (
                        edge.srcId in nodeIds ||
                            edge.dstId in nodeIds ||
                            edge.relation in predicateHits
                        )
            }
            val ranked = edges.sortedByDescending { score(it) }
            val facts = mutableListOf<Fact>()
            var used = 0
            for (edge in ranked) {
                val fact = toFact(this, edge)
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
        db.transaction {
            exec(
                """
                UPDATE edge SET expired_at = ?, updated_at = ?
                WHERE graph_id = ? AND expired_at IS NULL AND confidence < 0.35 AND updated_at < ?
                """.trimIndent(),
                now, now, graphId, horizon,
            )
        }
    }

    override suspend fun pendingReview(graphId: String): List<ReviewItem> = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            query(
                """
                SELECT r.edge_id, r.reason, r.confidence, r.s, r.p, r.o
                FROM pending_review r
                JOIN edge e ON e.id = r.edge_id
                WHERE e.graph_id = ?
                """.trimIndent(),
                graphId,
            ).map {
                ReviewItem(it.str("edge_id"), it.str("reason"), it.double("confidence"), it.str("s"), it.str("p"), it.str("o"))
            }
        }
    }

    override suspend fun resolveReview(graphId: String, edgeId: String, accept: Boolean) = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            exec("DELETE FROM pending_review WHERE edge_id = ?", edgeId)
            if (!accept) {
                val t = System.currentTimeMillis()
                exec(
                    "UPDATE edge SET expired_at = ?, updated_at = ? WHERE id = ? AND graph_id = ? AND expired_at IS NULL",
                    t, t, edgeId, graphId,
                )
            }
        }
    }

    override suspend fun rebuildFromFactLog(graphId: String) = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val replay = query(
                "SELECT s, p, o, confidence, raw_event_ids, retract, ts, valid_at, invalid_at FROM fact_log WHERE graph_id = ? ORDER BY ts",
                graphId,
            )
            exec("DELETE FROM pending_review WHERE edge_id IN (SELECT id FROM edge WHERE graph_id = ?)", graphId)
            exec("DELETE FROM node_fts WHERE graph_id = ?", graphId)
            exec("DELETE FROM node_alias WHERE graph_id = ?", graphId)
            exec("DELETE FROM edge WHERE graph_id = ?", graphId)
            exec("DELETE FROM node WHERE graph_id = ?", graphId)
            ensureUser(this, graphId)
            for (row in replay) {
                val triple = CleanTriple(row.str("s"), row.str("p"), row.str("o"), retract = row.int("retract") != 0)
                val ts = row.long("ts")
                val validAt = row.long("valid_at").takeIf { it > 0 } ?: ts
                val invalidAt = row.longOrNull("invalid_at")
                if (triple.retract) {
                    retractOne(
                        this,
                        graphId,
                        triple,
                        decodeIds(row.str("raw_event_ids")),
                        row.double("confidence"),
                        log = false,
                        at = ts,
                        invalidAt = invalidAt,
                    )
                } else {
                    ingestOne(
                        this,
                        graphId,
                        triple,
                        decodeIds(row.str("raw_event_ids")),
                        row.double("confidence"),
                        log = false,
                        at = ts,
                        validAt = validAt,
                        invalidAt = invalidAt,
                    )
                }
            }
        }
    }

    override suspend fun unconsumed(graphId: String, principal: String): List<RawEvent> = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            query(
                """
                SELECT id, graph_id, ts, session_id, role, text_ref, source, consumed, scope
                FROM raw_event WHERE graph_id = ? AND consumed = 0
                """.trimIndent(),
                graphId,
            ).map(::toRaw).filter { scopeAllowed(it.scope, principal) }
        }
    }

    override suspend fun markConsumed(graphId: String, ids: List<String>) = lock.withLock {
        requireGraph(graphId)
        db.transaction { markConsumed(this, graphId, ids) }
    }

    override suspend fun markScope(graphId: String, ids: List<String>, scope: String) = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            for (id in ids) {
                exec("UPDATE raw_event SET scope = ? WHERE graph_id = ? AND id = ?", scope, graphId, id)
            }
        }
    }

    override suspend fun facts(
        graphId: String,
        at: Long,
        p: String?,
        node: String?,
    ): MemoryHit = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val wantNode = node?.let { normalizeText(it) }?.takeIf { it.isNotEmpty() }
            val edges = loadValidEdges(this, graphId, at)
                .filter { edge ->
                    (p == null || edge.relation == p) &&
                        (wantNode == null || nameOf(this, edge.srcId) == wantNode || nameOf(this, edge.dstId) == wantNode)
                }
                .sortedByDescending { it.updatedAt }
            MemoryHit(edges.map { toFact(this, it) })
        }
    }

    override suspend fun recent(graphId: String, since: Long): MemoryHit = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val edges = txRecentEdges(this, graphId, since)
            MemoryHit(edges.map { toFact(this, it) })
        }
    }

    override suspend fun neighborhood(graphId: String, nodeNames: List<String>): MemoryHit = lock.withLock {
        requireGraph(graphId)
        val names = nodeNames.map { normalizeText(it) }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return@withLock MemoryHit()
        db.transaction {
            val at = System.currentTimeMillis()
            val edges = loadValidEdges(this, graphId, at).filter { edge ->
                nameOf(this, edge.srcId) in names || nameOf(this, edge.dstId) in names
            }
            MemoryHit(edges.map { toFact(this, it) })
        }
    }

    override suspend fun mergeNodes(graphId: String, keep: String, drop: String) = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val kn = normalizeText(keep)
            val dn = normalizeText(drop)
            if (kn.isEmpty() || dn.isEmpty() || kn == dn) return@transaction
            val dropId = findNode(this, graphId, dn) ?: return@transaction
            val keepId = resolveNode(this, graphId, kn)
            if (keepId == dropId) return@transaction
            val now = System.currentTimeMillis()
            val live = loadValidEdges(this, graphId, now).filter { it.srcId == dropId || it.dstId == dropId }
            for (edge in live) {
                exec(
                    "UPDATE edge SET expired_at = ?, updated_at = ? WHERE id = ? AND expired_at IS NULL",
                    now, now, edge.id,
                )
                val newSrc = if (edge.srcId == dropId) keepId else edge.srcId
                val newDst = if (edge.dstId == dropId) keepId else edge.dstId
                if (newSrc == newDst) continue
                val exists = query(
                    """
                    SELECT id FROM edge
                    WHERE graph_id = ? AND src = ? AND dst = ? AND relation = ? AND $CURRENT_EDGE
                    """.trimIndent(),
                    graphId, newSrc, newDst, edge.relation, now, now, now, now,
                ).isNotEmpty()
                if (exists) continue
                val edgeId = newId()
                exec(
                    """
                    INSERT INTO edge(id, graph_id, src, dst, relation, confidence, created_at, expired_at, valid_at, invalid_at, updated_at, scope, provenance)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    edgeId, graphId, newSrc, newDst, edge.relation, edge.confidence,
                    now, edge.validAt, edge.invalidAt, now, edge.scope, encodeIds(edge.provenance),
                )
                exec(
                    """
                    INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids, retract, valid_at, invalid_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """.trimIndent(),
                    newId(), graphId, now, nameOf(this, newSrc), edge.relation, nameOf(this, newDst),
                    edge.confidence, encodeIds(edge.provenance), edge.validAt, edge.invalidAt,
                )
            }
            exec(
                "INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, ?, ?)",
                graphId, dn, keepId,
            )
            refreshFts(this, graphId, keepId, nameOf(this, keepId))
        }
    }

    suspend fun snapshot(): String = lock.withLock {
        db.transaction {
            SNAPSHOT_JSON.encodeToString(
                Snapshot(
                    nodes = query("SELECT id, graph_id, type, canonical_name FROM node").map {
                        NodeRec(it.str("id"), it.str("graph_id"), it.str("type"), it.str("canonical_name"))
                    },
                    aliases = query("SELECT graph_id, alias, node_id FROM node_alias").map {
                        AliasRec(it.str("graph_id"), it.str("alias"), it.str("node_id"))
                    },
                    edges = loadAllEdges(this),
                    raw = query("SELECT id, graph_id, ts, session_id, role, text_ref, source, consumed, scope FROM raw_event").map(::toRaw),
                    factLog = query("SELECT id, graph_id, ts, s, p, o, confidence, raw_event_ids, retract, valid_at, invalid_at FROM fact_log").map {
                        FactLogRec(
                            it.str("id"), it.str("graph_id"), it.long("ts"), it.str("s"), it.str("p"), it.str("o"),
                            it.double("confidence"), decodeIds(it.str("raw_event_ids")), it.int("retract") != 0,
                            it.long("valid_at"), it.longOrNull("invalid_at"),
                        )
                    },
                    reviews = query("SELECT edge_id, reason, confidence, s, p, o FROM pending_review").map {
                        ReviewItem(it.str("edge_id"), it.str("reason"), it.double("confidence"), it.str("s"), it.str("p"), it.str("o"))
                    },
                ),
            )
        }
    }

    suspend fun restore(json: String) = lock.withLock {
        val snap = SNAPSHOT_JSON.decodeFromString<Snapshot>(json)
        db.transaction {
            exec("DELETE FROM pending_review")
            exec("DELETE FROM node_fts")
            exec("DELETE FROM node_alias")
            exec("DELETE FROM edge")
            exec("DELETE FROM node")
            exec("DELETE FROM fact_log")
            exec("DELETE FROM raw_event")
            for (node in snap.nodes) {
                exec(
                    "INSERT INTO node(id, graph_id, type, canonical_name) VALUES (?, ?, ?, ?)",
                    node.id, node.graphId, node.type, node.canonical,
                )
            }
            for (alias in snap.aliases) {
                exec(
                    "INSERT INTO node_alias(graph_id, alias, node_id) VALUES (?, ?, ?)",
                    alias.graphId, alias.alias, alias.nodeId,
                )
            }
            for (edge in snap.edges) {
                exec(
                    """
                    INSERT INTO edge(id, graph_id, src, dst, relation, confidence, created_at, expired_at, valid_at, invalid_at, updated_at, scope, provenance)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    edge.id, edge.graphId, edge.srcId, edge.dstId, edge.relation, edge.confidence,
                    edge.createdAt, edge.expiredAt, edge.validAt, edge.invalidAt, edge.updatedAt, edge.scope,
                    encodeIds(edge.provenance),
                )
            }
            for (event in snap.raw) {
                val textRef = if (event.text.isNotEmpty()) artifacts.put(event.text) else event.textRef
                exec(
                    """
                    INSERT INTO raw_event(id, graph_id, ts, session_id, role, text_ref, source, consumed, scope)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    event.id, event.graphId, event.ts, event.sessionId, event.role, textRef, event.source,
                    if (event.consumed) 1 else 0, event.scope,
                )
            }
            for (row in snap.factLog) {
                exec(
                    """
                    INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids, retract, valid_at, invalid_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    row.id, row.graphId, row.ts, row.s, row.p, row.o, row.confidence, encodeIds(row.rawEventIds),
                    if (row.retract) 1 else 0, row.validAt, row.invalidAt,
                )
            }
            for (review in snap.reviews) {
                exec(
                    "INSERT INTO pending_review(edge_id, reason, confidence, s, p, o) VALUES (?, ?, ?, ?, ?, ?)",
                    review.edgeId, review.reason, review.confidence, review.s, review.p, review.o,
                )
            }
            for (node in snap.nodes) refreshFts(this, node.graphId, node.id, node.canonical)
        }
    }

    fun close() = db.close()

    private fun ingestOne(
        tx: MemoryTx,
        graphId: String,
        triple: CleanTriple,
        rawEventIds: List<String>,
        confidence: Double,
        log: Boolean = true,
        at: Long = System.currentTimeMillis(),
        validAt: Long? = null,
        invalidAt: Long? = null,
    ) {
        val srcId = resolveNode(tx, graphId, triple.s)
        val dstId = resolveNode(tx, graphId, triple.o)
        val worldStart = validAt ?: at
        var superseded = false
        if (triple.p in graphFunctionalPredicates(graphId)) {
            val old = tx.query(
                """
                SELECT id FROM edge
                WHERE graph_id = ? AND src = ? AND relation = ? AND dst != ?
                  AND $CURRENT_EDGE
                """.trimIndent(),
                graphId, srcId, triple.p, dstId, at, at, at, at,
            )
            for (row in old) {
                archiveWorld(tx, at, worldStart, row.str("id"))
                superseded = true
            }
        }
        val existing = tx.query(
            """
            SELECT id, confidence, provenance FROM edge
            WHERE graph_id = ? AND src = ? AND dst = ? AND relation = ?
              AND $CURRENT_EDGE
            """.trimIndent(),
            graphId, srcId, dstId, triple.p, at, at, at, at,
        ).firstOrNull()
        val edgeId: String
        if (existing != null) {
            edgeId = existing.str("id")
            val provenance = (decodeIds(existing.str("provenance")) + rawEventIds).distinct()
            tx.exec(
                "UPDATE edge SET confidence = ?, updated_at = ?, provenance = ? WHERE id = ?",
                maxOf(existing.double("confidence"), confidence), at, encodeIds(provenance), edgeId,
            )
        } else {
            edgeId = newId()
            tx.exec(
                """
                INSERT INTO edge(id, graph_id, src, dst, relation, confidence, created_at, expired_at, valid_at, invalid_at, updated_at, scope, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """.trimIndent(),
                edgeId, graphId, srcId, dstId, triple.p, confidence, at, worldStart, invalidAt, at,
                defaultScope(triple.p), encodeIds(rawEventIds),
            )
        }
        if (log) {
            tx.exec(
                """
                INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids, retract, valid_at, invalid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """.trimIndent(),
                newId(), graphId, at, triple.s, triple.p, triple.o, confidence, encodeIds(rawEventIds),
                worldStart, invalidAt,
            )
        }
        if (superseded) {
            tx.exec("DELETE FROM pending_review WHERE edge_id = ?", edgeId)
            tx.exec(
                "INSERT INTO pending_review(edge_id, reason, confidence, s, p, o) VALUES (?, 'supersedes', ?, ?, ?, ?)",
                edgeId, confidence, triple.s, triple.p, triple.o,
            )
        }
    }

    private fun retractOne(
        tx: MemoryTx,
        graphId: String,
        triple: CleanTriple,
        rawEventIds: List<String>,
        confidence: Double,
        log: Boolean = true,
        at: Long = System.currentTimeMillis(),
        invalidAt: Long? = null,
    ) {
        val worldEnd = invalidAt ?: at
        val srcId = tx.query(
            "SELECT id FROM node WHERE graph_id = ? AND canonical_name = ?",
            graphId, normalizeText(triple.s),
        ).firstOrNull()?.str("id")
        val dstId = tx.query(
            "SELECT id FROM node WHERE graph_id = ? AND canonical_name = ?",
            graphId, normalizeText(triple.o),
        ).firstOrNull()?.str("id")
        if (srcId != null && dstId != null) {
            tx.query(
                """
                SELECT id FROM edge
                WHERE graph_id = ? AND src = ? AND dst = ? AND relation = ? AND expired_at IS NULL
                """.trimIndent(),
                graphId, srcId, dstId, triple.p,
            ).forEach { archiveWorld(tx, at, worldEnd, it.str("id")) }
        }
        if (log) {
            tx.exec(
                """
                INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids, retract, valid_at, invalid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """.trimIndent(),
                newId(), graphId, at, triple.s, triple.p, triple.o, confidence, encodeIds(rawEventIds),
                at, worldEnd,
            )
        }
    }

    private fun archiveWorld(tx: MemoryTx, now: Long, invalidAt: Long, edgeId: String) {
        tx.exec(
            """
            UPDATE edge SET expired_at = ?, invalid_at = COALESCE(invalid_at, ?), updated_at = ?
            WHERE id = ? AND expired_at IS NULL
            """.trimIndent(),
            now, invalidAt, now, edgeId,
        )
    }

    private fun resolveNode(
        tx: MemoryTx,
        graphId: String,
        name: String,
    ): String {
        val n = normalizeText(name)
        if (n == "用户" || n == "user") return ensureUser(tx, graphId)
        tx.query("SELECT node_id FROM node_alias WHERE graph_id = ? AND alias = ?", graphId, n)
            .firstOrNull()?.let { return it.str("node_id") }
        tx.query("SELECT id FROM node WHERE graph_id = ? AND canonical_name = ?", graphId, n)
            .firstOrNull()?.let { return it.str("id") }
        val id = newId()
        val type = "other"
        tx.exec("INSERT INTO node(id, graph_id, type, canonical_name) VALUES (?, ?, ?, ?)", id, graphId, type, n)
        tx.exec("INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, ?, ?)", graphId, n, id)
        refreshFts(tx, graphId, id, n)
        return id
    }

    private fun findNode(tx: MemoryTx, graphId: String, name: String): String? {
        tx.query("SELECT node_id FROM node_alias WHERE graph_id = ? AND alias = ?", graphId, name)
            .firstOrNull()?.let { return it.str("node_id") }
        return tx.query("SELECT id FROM node WHERE graph_id = ? AND canonical_name = ?", graphId, name)
            .firstOrNull()?.str("id")
    }

    private fun ensureUser(tx: MemoryTx, graphId: String): String {
        val id = userId(graphId)
        val exists = tx.query("SELECT id FROM node WHERE id = ?", id).isNotEmpty()
        if (!exists) {
            tx.exec("INSERT INTO node(id, graph_id, type, canonical_name) VALUES (?, ?, 'person', '用户')", id, graphId)
            tx.exec("INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, '用户', ?)", graphId, id)
            tx.exec("INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, 'user', ?)", graphId, id)
            refreshFts(tx, graphId, id, "用户")
        }
        return id
    }

    private fun matchNodes(tx: MemoryTx, graphId: String, tokens: Set<String>, qn: String): Set<String> {
        val match = ftsMatch(tokens, qn) ?: return emptySet()
        return tx.query(
            "SELECT node_id FROM node_fts WHERE node_fts MATCH ? AND graph_id = ?",
            match, graphId,
        ).map { it.str("node_id") }.toSet()
    }

    private fun ftsMatch(tokens: Set<String>, qn: String): String? {
        val parts = (tokens + qn).map { it.trim() }.filter { it.length >= 2 }.distinct()
        if (parts.isEmpty()) return null
        return parts.joinToString(" OR ") { token ->
            val safe = token.replace("\"", "").replace("*", "")
            "\"$safe\""
        }
    }

    private fun refreshFts(tx: MemoryTx, graphId: String, nodeId: String, canonical: String) {
        val aliases = tx.query(
            "SELECT alias FROM node_alias WHERE graph_id = ? AND node_id = ?",
            graphId, nodeId,
        ).joinToString(" ") { it.str("alias") }
        tx.exec("DELETE FROM node_fts WHERE node_id = ?", nodeId)
        tx.exec(
            "INSERT INTO node_fts(node_id, graph_id, canonical_name, aliases) VALUES (?, ?, ?, ?)",
            nodeId, graphId, ftsIndexText(canonical), ftsIndexText(aliases),
        )
    }

    private fun loadValidEdges(tx: MemoryTx, graphId: String, at: Long): List<EdgeRec> =
        tx.query(
            """
            SELECT $EDGE_COLS
            FROM edge WHERE graph_id = ? AND $CURRENT_EDGE
            """.trimIndent(),
            graphId, at, at, at, at,
        ).map(::toEdge)

    private fun txRecentEdges(tx: MemoryTx, graphId: String, since: Long): List<EdgeRec> =
        tx.query(
            """
            SELECT $EDGE_COLS
            FROM edge
            WHERE graph_id = ?
              AND (created_at >= ? OR updated_at >= ? OR expired_at >= ?)
            """.trimIndent(),
            graphId, since, since, since,
        ).map(::toEdge)

    private fun loadAllEdges(tx: MemoryTx): List<EdgeRec> =
        tx.query(
            """
            SELECT $EDGE_COLS FROM edge
            """.trimIndent(),
        ).map(::toEdge)

    private fun toEdge(row: SqlRow): EdgeRec = EdgeRec(
        id = row.str("id"),
        graphId = row.str("graph_id"),
        srcId = row.str("src"),
        dstId = row.str("dst"),
        relation = row.str("relation"),
        confidence = row.double("confidence"),
        createdAt = row.long("created_at"),
        expiredAt = row.longOrNull("expired_at"),
        validAt = row.long("valid_at"),
        invalidAt = row.longOrNull("invalid_at"),
        updatedAt = row.long("updated_at"),
        scope = row.str("scope"),
        provenance = decodeIds(row.str("provenance")),
    )

    private fun toRaw(row: SqlRow): RawEvent {
        val textRef = row.str("text_ref")
        return RawEvent(
            id = row.str("id"),
            graphId = row.str("graph_id"),
            ts = row.long("ts"),
            sessionId = row.str("session_id"),
            role = row.str("role"),
            text = artifacts.get(textRef).orEmpty(),
            source = row.str("source"),
            consumed = row.int("consumed") != 0,
            scope = row.str("scope"),
            textRef = textRef,
        )
    }

    private fun toFact(tx: MemoryTx, edge: EdgeRec): Fact = Fact(
        s = nameOf(tx, edge.srcId),
        p = edge.relation,
        o = nameOf(tx, edge.dstId),
        edgeId = edge.id,
    )

    private fun nameOf(tx: MemoryTx, nodeId: String): String =
        tx.query("SELECT canonical_name FROM node WHERE id = ?", nodeId).firstOrNull()?.str("canonical_name") ?: nodeId

    private fun score(edge: EdgeRec): Double {
        val recency = 1.0 / (1.0 + (System.currentTimeMillis() - edge.updatedAt).coerceAtLeast(0) / 86_400_000.0)
        return edge.confidence * recency
    }

    private fun markConsumed(tx: MemoryTx, graphId: String, ids: List<String>) {
        for (id in ids) {
            tx.exec("UPDATE raw_event SET consumed = 1 WHERE graph_id = ? AND id = ?", graphId, id)
        }
    }

    private fun requireGraph(graphId: String) {
        require(graphId.isNotBlank()) { "graphId required" }
    }

    private fun userId(graphId: String) = "$graphId:user"

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        const val EDGE_COLS =
            "id, graph_id, src, dst, relation, confidence, created_at, expired_at, valid_at, invalid_at, updated_at, scope, provenance"
        const val CURRENT_EDGE =
            "created_at <= ? AND (expired_at IS NULL OR expired_at > ?) AND valid_at <= ? AND (invalid_at IS NULL OR invalid_at > ?)"
        val SNAPSHOT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

class InMemoryMemoryStore : SqliteMemoryStore()

internal fun encodeIds(ids: List<String>): String = Json.encodeToString(ids)

internal fun decodeIds(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else Json.decodeFromString(raw)
