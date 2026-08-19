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

    override suspend fun ingest(drafts: List<TripleDraft>) = lock.withLock {
        db.transaction {
            for (draft in drafts) {
                requireGraph(draft.graphId)
                val cleaned = cleanTriples(listOf(draft), chunk = "")
                for (triple in cleaned) {
                    ingestOne(this, draft.graphId, triple, draft.rawEventIds, draft.confidence)
                }
                if (cleaned.isNotEmpty() && draft.rawEventIds.isNotEmpty()) {
                    markConsumed(this, draft.graphId, draft.rawEventIds)
                }
            }
        }
    }

    override suspend fun query(
        graphId: String,
        text: String,
        budgetChars: Int,
        principal: String,
    ): MemoryHit = lock.withLock {
        requireGraph(graphId)
        val tokens = queryTokens(text)
        if (tokens.isEmpty() && text.isBlank()) return@withLock MemoryHit()
        db.transaction {
            val qn = normalizeText(text)
            val nodeIds = matchNodes(this, graphId, tokens, qn)
            val predicateHits = graphPredicates(graphId).filter { pred ->
                val labels = listOfNotNull(predicateLabel(pred)) + PREDICATE_QUERY_HINTS[pred].orEmpty()
                labels.any { hint ->
                    val h = nfkcCompact(hint)
                    h.isNotEmpty() && h in qn
                }
            }
            val edges = loadValidEdges(this, graphId).filter { edge ->
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
                UPDATE edge SET valid_to = ?
                WHERE graph_id = ? AND valid_to IS NULL AND confidence < 0.35 AND updated_at < ?
                """.trimIndent(),
                now, graphId, horizon,
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
                exec(
                    "UPDATE edge SET valid_to = ? WHERE id = ? AND graph_id = ?",
                    System.currentTimeMillis(), edgeId, graphId,
                )
            }
        }
    }

    override suspend fun rebuildFromFactLog(graphId: String) = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val replay = query(
                "SELECT s, p, o, confidence, raw_event_ids FROM fact_log WHERE graph_id = ? ORDER BY ts",
                graphId,
            )
            exec("DELETE FROM pending_review WHERE edge_id IN (SELECT id FROM edge WHERE graph_id = ?)", graphId)
            exec("DELETE FROM node_fts WHERE graph_id = ?", graphId)
            exec("DELETE FROM node_alias WHERE graph_id = ?", graphId)
            exec("DELETE FROM edge WHERE graph_id = ?", graphId)
            exec("DELETE FROM node WHERE graph_id = ?", graphId)
            ensureUser(this, graphId)
            for (row in replay) {
                ingestOne(
                    this,
                    graphId,
                    CleanTriple(row.str("s"), row.str("p"), row.str("o")),
                    decodeIds(row.str("raw_event_ids")),
                    row.double("confidence"),
                    log = false,
                )
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

    override suspend fun facts(graphId: String): MemoryHit = lock.withLock {
        requireGraph(graphId)
        db.transaction {
            val edges = loadValidEdges(this, graphId).sortedByDescending { it.updatedAt }
            MemoryHit(edges.map { toFact(this, it) })
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
                    factLog = query("SELECT id, graph_id, ts, s, p, o, confidence, raw_event_ids FROM fact_log").map {
                        FactLogRec(
                            it.str("id"), it.str("graph_id"), it.long("ts"), it.str("s"), it.str("p"), it.str("o"),
                            it.double("confidence"), decodeIds(it.str("raw_event_ids")),
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
                    INSERT INTO edge(id, graph_id, src, dst, relation, confidence, valid_from, valid_to, updated_at, scope, provenance)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    edge.id, edge.graphId, edge.srcId, edge.dstId, edge.relation, edge.confidence,
                    edge.validFrom, edge.validTo, edge.updatedAt, edge.scope, encodeIds(edge.provenance),
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
                    INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    row.id, row.graphId, row.ts, row.s, row.p, row.o, row.confidence, encodeIds(row.rawEventIds),
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
    ) {
        val srcId = resolveNode(tx, graphId, triple.s, hint = triple.p)
        val dstId = resolveNode(tx, graphId, triple.o, hint = triple.p, asObject = true)
        val now = System.currentTimeMillis()
        var superseded = false
        if (triple.p in graphFunctionalPredicates(graphId)) {
            val old = tx.query(
                """
                SELECT id FROM edge
                WHERE graph_id = ? AND src = ? AND relation = ? AND valid_to IS NULL AND dst != ?
                """.trimIndent(),
                graphId, srcId, triple.p, dstId,
            )
            for (row in old) {
                tx.exec("UPDATE edge SET valid_to = ? WHERE id = ?", now, row.str("id"))
                superseded = true
            }
        }
        val existing = tx.query(
            """
            SELECT id, confidence, provenance FROM edge
            WHERE graph_id = ? AND src = ? AND dst = ? AND relation = ? AND valid_to IS NULL
            """.trimIndent(),
            graphId, srcId, dstId, triple.p,
        ).firstOrNull()
        val edgeId: String
        if (existing != null) {
            edgeId = existing.str("id")
            val provenance = (decodeIds(existing.str("provenance")) + rawEventIds).distinct()
            tx.exec(
                "UPDATE edge SET confidence = ?, updated_at = ?, provenance = ? WHERE id = ?",
                maxOf(existing.double("confidence"), confidence), now, encodeIds(provenance), edgeId,
            )
        } else {
            edgeId = newId()
            tx.exec(
                """
                INSERT INTO edge(id, graph_id, src, dst, relation, confidence, valid_from, valid_to, updated_at, scope, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                """.trimIndent(),
                edgeId, graphId, srcId, dstId, triple.p, confidence, now, now, defaultScope(triple.p), encodeIds(rawEventIds),
            )
        }
        if (triple.p == "allergic_to") {
            tx.exec(
                """
                UPDATE edge SET valid_to = ?
                WHERE graph_id = ? AND src = ? AND valid_to IS NULL
                  AND relation IN ('likes', 'prefers', 'dislikes')
                  AND dst IN (SELECT id FROM node WHERE canonical_name = ?)
                """.trimIndent(),
                now, graphId, srcId, triple.o,
            )
        }
        if (log) {
            tx.exec(
                """
                INSERT INTO fact_log(id, graph_id, ts, s, p, o, confidence, raw_event_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                newId(), graphId, now, triple.s, triple.p, triple.o, confidence, encodeIds(rawEventIds),
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

    private fun resolveNode(
        tx: MemoryTx,
        graphId: String,
        name: String,
        hint: String,
        asObject: Boolean = false,
    ): String {
        val n = normalizeText(name)
        if (n == "用户" || n == "user") return ensureUser(tx, graphId)
        tx.query("SELECT node_id FROM node_alias WHERE graph_id = ? AND alias = ?", graphId, n)
            .firstOrNull()?.let { return it.str("node_id") }
        tx.query("SELECT id FROM node WHERE graph_id = ? AND canonical_name = ?", graphId, n)
            .firstOrNull()?.let { return it.str("id") }
        if (!isNovelGraph(graphId)) {
            val hits = tx.query(
                "SELECT id, canonical_name FROM node WHERE graph_id = ? AND id != ?",
                graphId, userId(graphId),
            ).map { it.str("id") to it.str("canonical_name") }
                .filter { (_, canonical) -> n in canonical || (canonical.length >= 2 && canonical in n) }
            if (hits.size == 1) {
                val id = hits[0].first
                tx.exec("INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, ?, ?)", graphId, n, id)
                refreshFts(tx, graphId, id, hits[0].second)
                return id
            }
        }
        val id = newId()
        val type = when {
            n in PETS || hint == "has_pet" -> "pet"
            hint in setOf("related_to", "status", "is_a") && !asObject -> "person"
            hint == "related_to" && asObject -> "person"
            hint == "has_item" && asObject -> "thing"
            hint in setOf("lives_in", "located_in", "born_in", "work_location") && asObject -> "place"
            hint in setOf("works_at", "alumni_of", "member_of") && asObject -> "org"
            else -> "other"
        }
        tx.exec("INSERT INTO node(id, graph_id, type, canonical_name) VALUES (?, ?, ?, ?)", id, graphId, type, n)
        tx.exec("INSERT OR REPLACE INTO node_alias(graph_id, alias, node_id) VALUES (?, ?, ?)", graphId, n, id)
        refreshFts(tx, graphId, id, n)
        return id
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

    private fun loadValidEdges(tx: MemoryTx, graphId: String): List<EdgeRec> =
        tx.query(
            """
            SELECT id, graph_id, src, dst, relation, confidence, valid_from, valid_to, updated_at, scope, provenance
            FROM edge WHERE graph_id = ? AND valid_to IS NULL
            """.trimIndent(),
            graphId,
        ).map(::toEdge)

    private fun loadAllEdges(tx: MemoryTx): List<EdgeRec> =
        tx.query(
            """
            SELECT id, graph_id, src, dst, relation, confidence, valid_from, valid_to, updated_at, scope, provenance
            FROM edge
            """.trimIndent(),
        ).map(::toEdge)

    private fun toEdge(row: SqlRow): EdgeRec = EdgeRec(
        id = row.str("id"),
        graphId = row.str("graph_id"),
        srcId = row.str("src"),
        dstId = row.str("dst"),
        relation = row.str("relation"),
        confidence = row.double("confidence"),
        validFrom = row.long("valid_from"),
        validTo = row.longOrNull("valid_to"),
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
