package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InMemoryMemoryStoreTest {

    @Test
    fun remembersMomLikesPeanutsAndRecallsFrom我妈() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生")))
        val hit = store.query(GRAPH_ASSISTANT, "我妈爱吃什么")
        assertTrue(hit.facts.any { it.s == "妈妈" && it.p == "likes" && it.o == "花生" })
        assertTrue(hit.facts.any { it.p == "child_of" && it.o == "妈妈" })
    }

    @Test
    fun ingestThenQueryByObjectAndPredicate() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生酱")))
        val byName = store.query(GRAPH_ASSISTANT, "火锅别放花生")
        assertTrue(byName.facts.any { it.p == "allergic_to" && it.o == "花生" })
        val byPred = store.query(GRAPH_ASSISTANT, "我过敏什么")
        assertEquals(listOf("花生"), byPred.facts.map { it.o })
        assertEquals("- 用户 过敏 花生", byPred.render())
    }

    @Test
    fun remembersUnfinishedHomework() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "has_task", "作业")))
        val hit = store.query(GRAPH_ASSISTANT, "我作业做完了吗")
        assertTrue(hit.facts.any { it.p == "has_task" && it.o == "作业" })
        assertEquals("- 用户 待办 作业", hit.facts.single { it.p == "has_task" }.line())
    }

    @Test
    fun blankQueryReturnsNothing() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        assertTrue(store.query(GRAPH_ASSISTANT, "").isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "   ").isEmpty)
    }

    @Test
    fun graphIdIsAHardGate() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
                TripleDraft("novel:demo", "林晚", "located_in", "码头"),
            ),
        )
        assertTrue(store.query(GRAPH_ASSISTANT, "码头").isEmpty)
        assertTrue(store.query("novel:demo", "花生").isEmpty)
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.s == "林晚" })
        assertEquals(1, store.facts("novel:demo").facts.size)
    }

    @Test
    fun blankGraphIdIsRejected() = runTest {
        val store = InMemoryMemoryStore()
        assertFailsWith<IllegalArgumentException> {
            store.capture(RawTurn(graphId = "", role = "user", text = "hi"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.query("", "花生")
        }
        assertFailsWith<IllegalArgumentException> {
            store.ingest(listOf(TripleDraft("", "用户", "likes", "茶")))
        }
    }

    @Test
    fun functionalEdgeSupersedesOldObject() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海"),
            ),
        )
        assertEquals(listOf("上海"), store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "lives_in" }.map { it.o })
        assertTrue(store.pendingReview(GRAPH_ASSISTANT).any { it.reason == "supersedes" && it.o == "上海" })
    }

    @Test
    fun setPredicatesCanCoexist() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "拿铁"),
            ),
        )
        val likes = store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "likes" }.map { it.o }.toSet()
        assertEquals(setOf("美式", "拿铁"), likes)
        assertTrue(store.pendingReview(GRAPH_ASSISTANT).isEmpty())
    }

    @Test
    fun allergicToInvalidatesMatchingTasteEdge() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
            ),
        )
        val facts = store.facts(GRAPH_ASSISTANT).facts
        assertTrue(facts.any { it.p == "allergic_to" && it.o == "花生" })
        assertTrue(facts.none { it.p == "likes" && it.o == "花生" })
    }

    @Test
    fun duplicateIngestDoesNotDuplicateFacts() = runTest {
        val store = InMemoryMemoryStore()
        val draft = TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "阿里")
        store.ingest(listOf(draft, draft))
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.count { it.p == "works_at" })
    }

    @Test
    fun queryRespectsCharBudget() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            (1..8).map { i -> TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "饮品$i") },
        )
        val tight = store.query(GRAPH_ASSISTANT, "喜欢", budgetChars = 24)
        val wide = store.query(GRAPH_ASSISTANT, "喜欢", budgetChars = 2000)
        assertTrue(tight.facts.isNotEmpty())
        assertTrue(tight.facts.size < wide.facts.size)
        assertTrue(tight.render().length <= 24 + "- 用户 喜欢 饮品8".length)
    }

    @Test
    fun captureStaysUnconsumedUntilMarked() = runTest {
        val store = InMemoryMemoryStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        store.capture(RawTurn("novel:demo", "user", "林晚到了码头"))
        assertEquals(listOf(id), store.unconsumed(GRAPH_ASSISTANT).map { it.id })
        assertEquals(1, store.unconsumed("novel:demo").size)
        store.markConsumed(GRAPH_ASSISTANT, listOf(id))
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
        assertEquals(1, store.unconsumed("novel:demo").size)
    }

    @Test
    fun ingestWithRawEventIdsMarksConsumed() = runTest {
        val store = InMemoryMemoryStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生", rawEventIds = listOf(id))),
        )
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
    }

    @Test
    fun rebuildReplaysFactLogAndDoesNotLeakOtherGraph() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "阿里"),
                TripleDraft("novel:demo", "林晚", "located_in", "码头"),
            ),
        )
        store.rebuildFromFactLog(GRAPH_ASSISTANT)
        assertEquals("阿里", store.facts(GRAPH_ASSISTANT).facts.single { it.p == "works_at" }.o)
        assertEquals("码头", store.facts("novel:demo").facts.single().o)
    }

    @Test
    fun forgetArchivesStaleLowConfidenceEdges() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "香菜", confidence = 0.2)),
        )
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.size)
        val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        store.forget(GRAPH_ASSISTANT, now = later)
        assertTrue(store.facts(GRAPH_ASSISTANT).isEmpty)
    }

    @Test
    fun forgetLeavesFreshAndHighConfidenceEdges() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式", confidence = 0.9)))
        val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        store.forget(GRAPH_ASSISTANT, now = later)
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.size)
    }

    @Test
    fun resolveReviewRejectArchivesEdge() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        val review = store.pendingReview(GRAPH_ASSISTANT).single()
        store.resolveReview(GRAPH_ASSISTANT, review.edgeId, accept = false)
        assertTrue(store.pendingReview(GRAPH_ASSISTANT).isEmpty())
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.p == "lives_in" })
    }

    @Test
    fun resolveReviewAcceptKeepsEdge() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        val review = store.pendingReview(GRAPH_ASSISTANT).single()
        store.resolveReview(GRAPH_ASSISTANT, review.edgeId, accept = true)
        assertEquals("上海", store.facts(GRAPH_ASSISTANT).facts.single { it.p == "lives_in" }.o)
    }

    @Test
    fun snapshotRoundTripPreservesUnconsumedAndFacts() = runTest {
        val store = InMemoryMemoryStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "先记着"))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式咖啡")))
        val other = InMemoryMemoryStore()
        other.restore(store.snapshot())
        assertEquals("美式", other.facts(GRAPH_ASSISTANT).facts.single { it.p == "likes" }.o)
        assertEquals(1, other.unconsumed(GRAPH_ASSISTANT).size)
        assertEquals("先记着", other.unconsumed(GRAPH_ASSISTANT).single().text)
        assertFalse(store.snapshot().contains("novel:"))
    }

    @Test
    fun remembersYearsWorking() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "work_years", "两年")))
        val hit = store.query(GRAPH_ASSISTANT, "我工作几年了")
        assertTrue(hit.facts.any { it.p == "work_years" && it.o == "两年" })
        assertEquals("- 用户 工龄 两年", hit.facts.single { it.p == "work_years" }.line())
    }
}
