package relay.memory.engine

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
abstract class LedgerDao {
    @Transaction
    open suspend fun <T> withTx(block: suspend LedgerDao.() -> T): T = block()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSpace(row: SpaceEntity)

    @Query("SELECT * FROM memory_space WHERE id = :id")
    abstract suspend fun space(id: String): SpaceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertField(row: StateFieldEntity)

    @Query("SELECT * FROM state_field WHERE space_id = :spaceId AND field_id = :fieldId")
    abstract suspend fun field(spaceId: String, fieldId: String): StateFieldEntity?

    @Query("SELECT * FROM state_field WHERE space_id = :spaceId")
    abstract suspend fun fields(spaceId: String): List<StateFieldEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAlias(row: StateFieldAliasEntity)

    @Query("SELECT * FROM state_field_alias WHERE space_id = :spaceId AND alias = :alias")
    abstract suspend fun alias(spaceId: String, alias: String): StateFieldAliasEntity?

    @Insert
    abstract suspend fun insertRaw(row: LedgerRawEntity)

    @Query("SELECT * FROM ledger_raw_event WHERE id = :id")
    abstract suspend fun raw(id: String): LedgerRawEntity?

    @Query("SELECT * FROM ledger_raw_event WHERE space_id = :spaceId AND idempotency_key = :key LIMIT 1")
    abstract suspend fun rawByKey(spaceId: String, key: String): LedgerRawEntity?

    @Query("UPDATE ledger_raw_event SET processing_state = :state WHERE id = :id")
    abstract suspend fun setRawState(id: String, state: String)

    @Insert
    abstract suspend fun insertItem(row: MemoryItemEntity)

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id = :ownerId AND kind = 'STATE'
          AND field_id = :fieldId AND is_current = 1
        """,
    )
    abstract suspend fun currentStates(spaceId: String, ownerId: String, fieldId: String): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id = :ownerId AND kind = 'STATE'
          AND scope = :scope AND scope_id = :scopeId AND field_id = :fieldId AND is_current = 1
        LIMIT 1
        """,
    )
    abstract suspend fun currentState(
        spaceId: String,
        ownerId: String,
        scope: String,
        scopeId: String,
        fieldId: String,
    ): MemoryItemEntity?

    @Query(
        """
        UPDATE memory_item
        SET is_current = 0, valid_to = :validTo, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    abstract suspend fun closeCurrent(id: String, validTo: Long, updatedAt: Long)

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id = :ownerId AND kind = 'STATE' AND field_id = :fieldId
        ORDER BY valid_from, created_at
        """,
    )
    abstract suspend fun stateVersions(spaceId: String, ownerId: String, fieldId: String): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id = :ownerId AND kind = 'EPISODE'
          AND idempotency_key = :key
        LIMIT 1
        """,
    )
    abstract suspend fun episodeByKey(spaceId: String, ownerId: String, key: String): MemoryItemEntity?

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id IN (:owners)
        """,
    )
    abstract suspend fun itemsForOwners(spaceId: String, owners: List<String>): List<MemoryItemEntity>

    @Insert
    abstract suspend fun insertSource(row: MemorySourceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTag(row: MemoryTagEntity)

    @Query("INSERT INTO memory_fts (`memory_id`, `text`) VALUES (:memoryId, :text)")
    abstract suspend fun insertFts(memoryId: String, text: String)

    @Query("DELETE FROM memory_fts WHERE memory_id = :memoryId")
    abstract suspend fun deleteFts(memoryId: String)

    @Query("SELECT * FROM memory_item WHERE id = :id")
    abstract suspend fun item(id: String): MemoryItemEntity?

    @Query(
        """
        SELECT * FROM memory_item
        WHERE space_id = :spaceId AND owner_id = :ownerId AND kind = 'REFLECTION'
          AND scope = :scope AND scope_id = :scopeId AND memory_key = :memoryKey AND is_current = 1
        LIMIT 1
        """,
    )
    abstract suspend fun currentReflection(
        spaceId: String,
        ownerId: String,
        scope: String,
        scopeId: String,
        memoryKey: String,
    ): MemoryItemEntity?

    @Insert
    abstract suspend fun insertEvidence(row: MemoryEvidenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertModel(row: EmbeddingModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertEmbedding(row: MemoryEmbeddingEntity)

    @Insert
    abstract suspend fun insertJob(row: IndexJobEntity)

    @Query("SELECT * FROM index_job WHERE memory_id = :memoryId AND kind = 'EMBEDDING' LIMIT 1")
    abstract suspend fun embeddingJob(memoryId: String): IndexJobEntity?

    @Query("UPDATE index_job SET status = :status, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun setJobStatus(id: String, status: String, updatedAt: Long)

    @Query(
        """
        SELECT COUNT(*) FROM index_job
        INNER JOIN memory_item ON memory_item.id = index_job.memory_id
        WHERE memory_item.space_id = :spaceId AND index_job.status = 'PENDING'
        """,
    )
    abstract suspend fun pendingJobs(spaceId: String): Int

    @Query("SELECT * FROM memory_embedding WHERE model_id = :modelId AND memory_id IN (:ids)")
    abstract suspend fun embeddings(modelId: String, ids: List<String>): List<MemoryEmbeddingEntity>

    @Query("DELETE FROM memory_embedding")
    abstract suspend fun deleteEmbeddings()

    @Query("SELECT memory_id FROM memory_fts WHERE memory_fts MATCH :q")
    abstract suspend fun ftsIds(q: String): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM ledger_raw_event
        WHERE space_id = :spaceId AND processing_state = 'PENDING'
        """,
    )
    abstract suspend fun pendingRawCount(spaceId: String): Int
}
