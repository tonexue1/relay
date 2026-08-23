package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryStorePrimitiveTest {

    @Test
    fun ingestRejectsUnknownPredicate() = runTest {
        val store = testStore()
        val result = store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "teleports_to", "月球")),
        )
        assertTrue(result.errors.any { it.p == "teleports_to" })
        assertTrue(store.facts(GRAPH_ASSISTANT).isEmpty)
    }

    @Test
    fun ingestRejectsEmptyFields() = runTest {
        val store = testStore()
        val result = store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "")),
        )
        assertTrue(result.errors.isNotEmpty())
        assertTrue(store.facts(GRAPH_ASSISTANT).isEmpty)
    }

    @Test
    fun ingestKeepsValidInSameBatch() = runTest {
        val store = testStore()
        val result = store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "teleports_to", "月球"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶"),
                TripleDraft(GRAPH_LINWAN, "林晚", "allergic_to", "花生"),
            ),
        )
        assertEquals(listOf("茶"), store.facts(GRAPH_ASSISTANT).facts.map { it.o })
        assertTrue(store.facts(GRAPH_LINWAN).isEmpty)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { it.p == "teleports_to" })
        assertTrue(result.errors.any { it.p == "allergic_to" })
    }

    @Test
    fun factsFilterByPredicate() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
            ),
        )
        val likes = store.facts(GRAPH_ASSISTANT, p = "likes")
        assertEquals(listOf("茶"), likes.facts.map { it.o })
        val allergy = store.facts(GRAPH_ASSISTANT, p = "allergic_to")
        assertEquals(listOf("花生"), allergy.facts.map { it.o })
    }

    @Test
    fun factsFilterByNode() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶"),
            ),
        )
        val mom = store.facts(GRAPH_ASSISTANT, node = "妈妈")
        assertEquals(listOf("花生"), mom.facts.map { it.o })
        assertTrue(mom.facts.all { it.s == "妈妈" || it.o == "妈妈" })
        val peanut = store.facts(GRAPH_ASSISTANT, node = "花生")
        assertEquals(2, peanut.facts.size)
        assertTrue(peanut.facts.all { it.s == "花生" || it.o == "花生" })
    }

    @Test
    fun recentSeesCreateUpdateAndExpireOnSystemClock() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶")))
        Thread.sleep(15)
        val afterTea = System.currentTimeMillis()
        Thread.sleep(15)
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        Thread.sleep(15)
        val beforeMove = System.currentTimeMillis()
        Thread.sleep(15)
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        val recentMove = store.recent(GRAPH_ASSISTANT, since = beforeMove)
        assertTrue(recentMove.facts.any { it.p == "lives_in" && it.o == "上海" })
        assertTrue(recentMove.facts.any { it.p == "lives_in" && it.o == "杭州" })
        assertTrue(recentMove.facts.none { it.p == "likes" })
        val sinceTea = store.recent(GRAPH_ASSISTANT, since = afterTea)
        assertTrue(sinceTea.facts.any { it.p == "likes" && it.o == "茶" }.not())
        assertTrue(sinceTea.facts.any { it.p == "lives_in" })
    }

    @Test
    fun recentIncludesForgottenEdge() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "香菜", confidence = 0.2)))
        val afterIngest = System.currentTimeMillis()
        val later = afterIngest + 31L * 24 * 60 * 60 * 1000
        store.forget(GRAPH_ASSISTANT, now = later)
        val recent = store.recent(GRAPH_ASSISTANT, since = afterIngest)
        assertTrue(recent.facts.any { it.p == "likes" && it.o == "香菜" })
        assertTrue(store.facts(GRAPH_ASSISTANT, at = later).isEmpty)
    }

    @Test
    fun neighborhoodReturnsLiveNeighborsOnly() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "child_of", "妈妈"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶"),
                TripleDraft("novel:demo", "林晚", "located_in", "码头"),
            ),
        )
        val mom = store.neighborhood(GRAPH_ASSISTANT, listOf("妈妈"))
        assertTrue(mom.facts.any { it.s == "妈妈" && it.p == "likes" && it.o == "花生" })
        assertTrue(mom.facts.any { it.p == "child_of" && it.o == "妈妈" })
        assertTrue(mom.facts.none { it.p == "likes" && it.o == "茶" })
        assertTrue(store.neighborhood(GRAPH_ASSISTANT, listOf("林晚")).isEmpty)
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生", retract = true)))
        assertTrue(store.neighborhood(GRAPH_ASSISTANT, listOf("妈妈")).facts.none { it.p == "likes" })
    }

    @Test
    fun mergeNodesExpiresDropAndInsertsOnKeep() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职")))
        val before = System.currentTimeMillis()
        Thread.sleep(15)
        store.mergeNodes(GRAPH_ASSISTANT, keep = "跳槽", drop = "离职")
        val live = store.facts(GRAPH_ASSISTANT)
        assertEquals(listOf("跳槽"), live.facts.filter { it.p == "plans" }.map { it.o })
        assertEquals("离职", store.facts(GRAPH_ASSISTANT, at = before).facts.single { it.p == "plans" }.o)
        assertTrue(store.snapshot().contains("离职"))
        assertTrue(store.snapshot().contains("跳槽"))
    }

    @Test
    fun mergeNodesDoesNotDuplicateExistingKeepEdge() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "跳槽"),
            ),
        )
        store.mergeNodes(GRAPH_ASSISTANT, keep = "跳槽", drop = "离职")
        val plans = store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "plans" }
        assertEquals(listOf("跳槽"), plans.map { it.o })
    }

    @Test
    fun mergeNodesDoesNotSetWorldInvalidAt() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职")))
        val before = System.currentTimeMillis()
        Thread.sleep(15)
        store.mergeNodes(GRAPH_ASSISTANT, keep = "跳槽", drop = "离职")
        assertEquals("离职", store.facts(GRAPH_ASSISTANT, at = before).facts.single { it.p == "plans" }.o)
        val live = store.facts(GRAPH_ASSISTANT)
        assertEquals("跳槽", live.facts.single { it.p == "plans" }.o)
        assertTrue(live.facts.none { it.o == "离职" })
    }
}
