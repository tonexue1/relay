package relay.memory.api

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.engine.HashedEmbedder
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.ensureSpace
import relay.memory.testContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Mem0FactTest {

    private val embedder = HashedEmbedder()

    private suspend fun runtime(): MemoryRuntime {
        val mem = SqliteLedgerRuntime(testContext())
        mem.ensureSpace("assistant")
        return mem
    }

    private suspend fun add(
        text: String,
        linked: List<String> = emptyList(),
        at: Long = System.currentTimeMillis(),
    ) = AddMemory(
        spaceId = "assistant",
        ownerId = "user",
        text = text,
        linkedMemoryIds = linked,
        vector = embedder.embed(text),
        embeddingModelId = embedder.modelId,
        at = ClockStamp(ClockDomain.WALL_CLOCK, at),
    )

    @Test
    fun addOnlyKeepsBothFactsAndLatestOnlyHidesLinked() = runTest {
        val mem = runtime()
        val first = mem.addMemory(add("用户喜欢吃西红柿", at = 1))
        val second = mem.addMemory(add("用户不再喜欢吃西红柿", linked = listOf(first.id), at = 2))
        assertTrue(first.created)
        assertTrue(second.created)
        assertFalse(first.id == second.id)

        val all = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "西红柿",
                queryVector = embedder.embed("西红柿"),
                embeddingModelId = embedder.modelId,
                at = ClockStamp(ClockDomain.WALL_CLOCK, 3),
                latestOnly = false,
                minScore = 0.0,
                limit = 10,
            ),
        )
        assertEquals(2, all.size)

        val latest = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "西红柿",
                queryVector = embedder.embed("西红柿"),
                embeddingModelId = embedder.modelId,
                at = ClockStamp(ClockDomain.WALL_CLOCK, 3),
                latestOnly = true,
            ),
        )
        assertEquals(listOf(second.id), latest.map { it.id })
        assertTrue(latest.single().text.contains("不再喜欢"))
    }

    @Test
    fun exactDuplicateIsDeduped() = runTest {
        val mem = runtime()
        val a = mem.addMemory(add("用户花生过敏"))
        val b = mem.addMemory(add("用户花生过敏"))
        assertTrue(a.created)
        assertFalse(b.created)
        assertEquals(a.id, b.id)
    }

    @Test
    fun vectorPrefersCloserFact() = runTest {
        val mem = runtime()
        mem.addMemory(add("用户住在杭州", at = 1))
        mem.addMemory(add("用户今晚想吃火锅", at = 1))
        val hits = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "住在哪里",
                queryVector = embedder.embed("住在杭州"),
                embeddingModelId = embedder.modelId,
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                minScore = 0.0,
                limit = 10,
            ),
        )
        assertTrue(hits.first().text.contains("杭州"))
    }

    @Test
    fun minScoreDropsLowHitsWithoutChangingDefaultLimit() = runTest {
        val mem = runtime()
        mem.addMemory(add("用户花生过敏", at = 1))
        mem.addMemory(add("用户住在杭州", at = 1))
        val all = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "青霉素",
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                minScore = 0.0,
                limit = 10,
                lexical = LexicalScorer.COVERAGE,
            ),
        )
        val strict = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "青霉素",
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                minScore = 0.99,
            ),
        )
        assertTrue(all.size >= strict.size)
        assertTrue(strict.all { it.score >= 0.99 })
    }

    @Test
    fun defaultSearchDoesNotFillUnrelatedFacts() = runTest {
        val mem = runtime()
        mem.addMemory(add("用户花生过敏", at = 1))
        mem.addMemory(add("用户住在杭州", at = 1))
        val hits = mem.searchMemories(
            SearchMemories(
                spaceId = "assistant",
                ownerId = "user",
                query = "今天北京下雨吗",
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
            ),
        )
        assertTrue(hits.isEmpty())
    }
}
