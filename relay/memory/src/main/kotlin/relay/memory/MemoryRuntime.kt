package relay.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import relay.agent.ContextAugmenter
import relay.agent.Tool
import relay.memory.agent.dayTools
import relay.memory.agent.nightTools
import relay.memory.agent.recalling
import relay.memory.dream.ConsolidationReport
import relay.memory.dream.MemoryConsolidator
import relay.memory.extract.CloudTripleExtractor
import relay.memory.extract.MemoryExtractor

data class LearnReport(
    val runId: String? = null,
    val sessionId: String = "",
    val eventIds: List<String> = emptyList(),
    val claims: List<ClaimDraft> = emptyList(),
    val drafts: List<TripleDraft> = emptyList(),
    val errors: List<IngestError> = emptyList(),
    val outcome: ExtractOutcome? = null,
    val extractErrors: List<String> = emptyList(),
)

/**
 * Host-facing lifecycle over a deterministic [store].
 *
 * [extractor] and [consolidator] hold the model. The store never does.
 */
class MemoryRuntime(
    val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val consolidator: MemoryConsolidator? = null,
    private val batchPlanner: LearnBatchPlanner = LearnBatchPlanner(),
) {
    private val learnMutex = Mutex()
    fun recalling(
        graphId: String,
        pin: String = "",
        budgetChars: Int = 2000,
    ): ContextAugmenter = store.recalling(graphId, pin, budgetChars)

    fun dayTools(graphId: String): List<Tool> = store.dayTools(graphId)

    fun nightTools(graphId: String): List<Tool> = store.nightTools(graphId)

    suspend fun learn(graphId: String): LearnReport = learnMutex.withLock {
        val batch = batchPlanner.next(store, graphId) ?: return@withLock LearnReport()
        learnBatchUnlocked(batch)
    }

    suspend fun learn(graphId: String, sessionId: String): LearnReport = learnMutex.withLock {
        val batch = batchPlanner.next(store, graphId, sessionId) ?: return@withLock LearnReport(sessionId = sessionId)
        learnBatchUnlocked(batch)
    }

    suspend fun learnBatch(batch: LearnBatch): LearnReport = learnMutex.withLock {
        learnBatchUnlocked(batch)
    }

    private suspend fun learnBatchUnlocked(batch: LearnBatch): LearnReport {
        require(batch.events.isNotEmpty()) { "learn batch requires events" }
        require(batch.events.all { it.graphId == batch.graphId && it.sessionId == batch.sessionId })
        val runId = store.startExtractionRun(
            graphId = batch.graphId,
            sessionId = batch.sessionId,
            eventIds = batch.eventIds,
            contextEventIds = batch.contextEventIds,
        )
        val extracted = try {
            extractor.extract(
                graphId = batch.graphId,
                dialogue = CloudTripleExtractor.formatTurns(batch.contextEvents + batch.events),
                rawEventIds = batch.eventIds,
                priorFacts = store.facts(batch.graphId).facts,
            )
        } catch (e: Exception) {
            store.finishExtractionRun(
                runId = runId,
                outcome = ExtractOutcome.REJECTED,
                errors = listOf(e.message ?: e.toString()),
            )
            throw e
        }
        if (!extracted.successful) {
            store.finishExtractionRun(
                runId = runId,
                outcome = extracted.outcome,
                rawResponse = extracted.raw,
                errors = extracted.errors,
            )
            return LearnReport(
                runId = runId,
                sessionId = batch.sessionId,
                eventIds = batch.eventIds,
                outcome = extracted.outcome,
                extractErrors = extracted.errors,
            )
        }
        val ingested = store.commitExtraction(
            graphId = batch.graphId,
            sessionId = batch.sessionId,
            runId = runId,
            eventIds = batch.eventIds,
            claims = extracted.claims,
            drafts = extracted.drafts,
            outcome = extracted.outcome,
            rawResponse = extracted.raw,
        )
        return LearnReport(
            runId = runId,
            sessionId = batch.sessionId,
            eventIds = batch.eventIds,
            claims = extracted.claims,
            drafts = extracted.drafts,
            errors = ingested.errors,
            outcome = extracted.outcome,
        )
    }

    suspend fun consolidate(
        graphId: String,
        since: Long = System.currentTimeMillis() - SEVEN_DAYS_MS,
    ): ConsolidationReport = consolidator?.consolidate(graphId, since) ?: ConsolidationReport()

    private companion object {
        const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }
}
