package relay.memory.engine

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
abstract class MemoryDao {
    @Transaction
    open suspend fun <T> withTx(block: suspend MemoryDao.() -> T): T = block()

    @Insert
    abstract suspend fun insertRaw(row: RawEventEntity)

    @Query("SELECT * FROM raw_event WHERE graph_id = :graphId AND consumed = 0 ORDER BY ts, id")
    abstract suspend fun unconsumed(graphId: String): List<RawEventEntity>

    @Query(
        """
        SELECT * FROM raw_event
        WHERE graph_id = :graphId AND session_id = :sessionId AND consumed = 0
        ORDER BY ts, id
        """,
    )
    abstract suspend fun unconsumedInSession(graphId: String, sessionId: String): List<RawEventEntity>

    @Query(
        """
        SELECT * FROM raw_event
        WHERE graph_id = :graphId AND session_id = :sessionId AND ts < :beforeTs
        ORDER BY ts DESC, id DESC LIMIT :limit
        """,
    )
    abstract suspend fun eventsBefore(
        graphId: String,
        sessionId: String,
        beforeTs: Long,
        limit: Int,
    ): List<RawEventEntity>

    @Query("SELECT * FROM raw_event")
    abstract suspend fun allRaw(): List<RawEventEntity>

    @Query("UPDATE raw_event SET consumed = 1 WHERE graph_id = :graphId AND id = :id")
    abstract suspend fun markConsumed(graphId: String, id: String)

    @Query("DELETE FROM raw_event")
    abstract suspend fun deleteAllRaw()

    @Insert
    abstract suspend fun insertFact(row: FactLogEntity)

    @Query("SELECT * FROM fact_log WHERE graph_id = :graphId ORDER BY ts")
    abstract suspend fun factLog(graphId: String): List<FactLogEntity>

    @Query("SELECT * FROM fact_log")
    abstract suspend fun allFactLog(): List<FactLogEntity>

    @Query("DELETE FROM fact_log")
    abstract suspend fun deleteAllFactLog()

    @Insert
    abstract suspend fun insertClaim(row: ClaimLogEntity)

    @Query("SELECT * FROM claim_log WHERE id IN (:ids)")
    abstract suspend fun claimsByIds(ids: List<String>): List<ClaimLogEntity>

    @Query("SELECT * FROM claim_log WHERE graph_id = :graphId ORDER BY created_at DESC")
    abstract suspend fun claims(graphId: String): List<ClaimLogEntity>

    @Query("SELECT * FROM claim_log")
    abstract suspend fun allClaims(): List<ClaimLogEntity>

    @Query("DELETE FROM claim_log")
    abstract suspend fun deleteAllClaims()

    @Insert
    abstract suspend fun insertExtractionRun(row: ExtractionRunEntity)

    @Query(
        """
        UPDATE extraction_run
        SET status = :status, finished_at = :finishedAt, response_ref = :responseRef, error = :error
        WHERE id = :id
        """,
    )
    abstract suspend fun finishExtractionRun(
        id: String,
        status: String,
        finishedAt: Long,
        responseRef: String,
        error: String,
    )

    @Query("SELECT * FROM extraction_run ORDER BY started_at")
    abstract suspend fun allExtractionRuns(): List<ExtractionRunEntity>

    @Query("DELETE FROM extraction_run")
    abstract suspend fun deleteAllExtractionRuns()

    @Insert
    abstract suspend fun insertNode(row: NodeEntity)

    @Query("SELECT * FROM node WHERE id = :id")
    abstract suspend fun nodeById(id: String): NodeEntity?

    @Query("SELECT * FROM node WHERE graph_id = :graphId AND canonical_name = :name")
    abstract suspend fun nodeByName(graphId: String, name: String): NodeEntity?

    @Query("SELECT * FROM node")
    abstract suspend fun allNodes(): List<NodeEntity>

    @Query("DELETE FROM node WHERE graph_id = :graphId")
    abstract suspend fun deleteNodes(graphId: String)

    @Query("DELETE FROM node")
    abstract suspend fun deleteAllNodes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAlias(row: NodeAliasEntity)

    @Query("SELECT node_id FROM node_alias WHERE graph_id = :graphId AND alias = :alias")
    abstract suspend fun aliasNodeId(graphId: String, alias: String): String?

    @Query("SELECT alias FROM node_alias WHERE graph_id = :graphId AND node_id = :nodeId")
    abstract suspend fun aliasesOf(graphId: String, nodeId: String): List<String>

    @Query("SELECT * FROM node_alias")
    abstract suspend fun allAliases(): List<NodeAliasEntity>

    @Query("DELETE FROM node_alias WHERE graph_id = :graphId")
    abstract suspend fun deleteAliases(graphId: String)

    @Query("DELETE FROM node_alias")
    abstract suspend fun deleteAllAliases()

    @Insert
    abstract suspend fun insertEdge(row: EdgeEntity)

    @Query(
        """
        UPDATE edge SET confidence = :confidence, updated_at = :updatedAt, provenance = :provenance
        WHERE id = :id
        """,
    )
    abstract suspend fun touchEdge(id: String, confidence: Double, updatedAt: Long, provenance: String)

    @Query(
        """
        UPDATE edge SET expired_at = :now, invalid_at = COALESCE(invalid_at, :invalidAt), updated_at = :now
        WHERE id = :id AND expired_at IS NULL
        """,
    )
    abstract suspend fun archiveWorld(id: String, now: Long, invalidAt: Long)

    @Query("UPDATE edge SET expired_at = :now, updated_at = :now WHERE id = :id AND expired_at IS NULL")
    abstract suspend fun expireEdge(id: String, now: Long)

    @Query(
        """
        UPDATE edge SET expired_at = :now, updated_at = :now
        WHERE id = :id AND graph_id = :graphId AND expired_at IS NULL
        """,
    )
    abstract suspend fun expireEdgeInGraph(graphId: String, id: String, now: Long)

    @Query(
        """
        UPDATE edge SET expired_at = :now, updated_at = :now
        WHERE graph_id = :graphId AND expired_at IS NULL AND confidence < 0.35 AND updated_at < :horizon
        """,
    )
    abstract suspend fun forgetStale(graphId: String, now: Long, horizon: Long)

    @Query(
        """
        SELECT * FROM edge WHERE graph_id = :graphId
          AND created_at <= :at AND (expired_at IS NULL OR expired_at > :at)
          AND valid_at <= :at AND (invalid_at IS NULL OR invalid_at > :at)
        """,
    )
    abstract suspend fun validEdges(graphId: String, at: Long): List<EdgeEntity>

    @Query(
        """
        SELECT * FROM edge WHERE graph_id = :graphId AND src = :src AND relation = :relation AND dst != :dst
          AND created_at <= :at AND (expired_at IS NULL OR expired_at > :at)
          AND valid_at <= :at AND (invalid_at IS NULL OR invalid_at > :at)
        """,
    )
    abstract suspend fun superseded(graphId: String, src: String, relation: String, dst: String, at: Long): List<EdgeEntity>

    @Query(
        """
        SELECT * FROM edge WHERE graph_id = :graphId AND src = :src AND dst = :dst AND relation = :relation
          AND created_at <= :at AND (expired_at IS NULL OR expired_at > :at)
          AND valid_at <= :at AND (invalid_at IS NULL OR invalid_at > :at)
        """,
    )
    abstract suspend fun liveTriple(
        graphId: String,
        src: String,
        dst: String,
        relation: String,
        at: Long,
    ): EdgeEntity?

    @Query(
        """
        SELECT * FROM edge
        WHERE graph_id = :graphId AND src = :src AND dst = :dst AND relation = :relation AND expired_at IS NULL
        """,
    )
    abstract suspend fun openTriple(graphId: String, src: String, dst: String, relation: String): List<EdgeEntity>

    @Query(
        """
        SELECT * FROM edge WHERE graph_id = :graphId
          AND (created_at >= :since OR updated_at >= :since OR expired_at >= :since)
        """,
    )
    abstract suspend fun recentEdges(graphId: String, since: Long): List<EdgeEntity>

    @Query("SELECT * FROM edge")
    abstract suspend fun allEdges(): List<EdgeEntity>

    @Query("DELETE FROM edge WHERE graph_id = :graphId")
    abstract suspend fun deleteEdges(graphId: String)

    @Query("DELETE FROM edge")
    abstract suspend fun deleteAllEdges()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReview(row: PendingReviewEntity)

    @Query("DELETE FROM pending_review WHERE edge_id = :edgeId")
    abstract suspend fun deleteReview(edgeId: String)

    @Query(
        """
        SELECT r.edge_id, r.reason, r.confidence, r.s, r.p, r.o
        FROM pending_review r
        JOIN edge e ON e.id = r.edge_id
        WHERE e.graph_id = :graphId
        """,
    )
    abstract suspend fun reviews(graphId: String): List<PendingReviewEntity>

    @Query("DELETE FROM pending_review WHERE edge_id IN (SELECT id FROM edge WHERE graph_id = :graphId)")
    abstract suspend fun deleteReviewsForGraph(graphId: String)

    @Query("SELECT * FROM pending_review")
    abstract suspend fun allReviews(): List<PendingReviewEntity>

    @Query("DELETE FROM pending_review")
    abstract suspend fun deleteAllReviews()

    @Query("SELECT node_id FROM node_fts WHERE node_fts MATCH :match AND graph_id = :graphId")
    abstract suspend fun ftsNodeIds(match: String, graphId: String): List<FtsNodeId>

    @Query("DELETE FROM node_fts WHERE node_id = :nodeId")
    abstract suspend fun deleteFts(nodeId: String)

    @Query("DELETE FROM node_fts WHERE graph_id = :graphId")
    abstract suspend fun deleteFtsGraph(graphId: String)

    @Query("DELETE FROM node_fts")
    abstract suspend fun deleteAllFts()

    @Query(
        "INSERT INTO node_fts(node_id, graph_id, canonical_name, aliases) VALUES (:nodeId, :graphId, :name, :aliases)",
    )
    abstract suspend fun insertFts(nodeId: String, graphId: String, name: String, aliases: String)

    @Query("SELECT claim_id FROM claim_fts WHERE claim_fts MATCH :match AND graph_id = :graphId")
    abstract suspend fun claimFtsIds(match: String, graphId: String): List<ClaimFtsId>

    @Query("DELETE FROM claim_fts WHERE claim_id = :claimId")
    abstract suspend fun deleteClaimFts(claimId: String)

    @Query("DELETE FROM claim_fts")
    abstract suspend fun deleteAllClaimFts()

    @Query(
        "INSERT INTO claim_fts(claim_id, graph_id, subject, text) VALUES (:claimId, :graphId, :subject, :text)",
    )
    abstract suspend fun insertClaimFts(claimId: String, graphId: String, subject: String, text: String)
}

data class FtsNodeId(
    @ColumnInfo(name = "node_id") val nodeId: String,
)

data class ClaimFtsId(
    @ColumnInfo(name = "claim_id") val claimId: String,
)
