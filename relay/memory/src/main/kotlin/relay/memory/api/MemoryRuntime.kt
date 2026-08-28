package relay.memory.api

interface MemoryRuntime {
    suspend fun capture(event: RawEventDraft): RawEventId
    suspend fun registerStateSchema(snapshot: StateSchemaSnapshot): SchemaRegistration
    suspend fun ensureStateField(spec: StateFieldSpec): FieldRegistration
    suspend fun putFieldAlias(spaceId: String, alias: String, canonicalFieldId: String)
    suspend fun commit(batch: MemoryBatch): CommitResult
    suspend fun recall(request: RecallRequest): RecallResult
    suspend fun getStates(request: StateReadRequest): StateReadResult
    suspend fun getStateHistory(request: StateHistoryRequest): List<StateVersion>
    suspend fun putEmbedding(put: EmbeddingPut): Boolean
    suspend fun indexHealth(spaceId: String): IndexHealth
    suspend fun listItems(spaceId: String, ownerId: String): List<MemoryRecord>
    suspend fun pendingRawCount(spaceId: String): Int
}
