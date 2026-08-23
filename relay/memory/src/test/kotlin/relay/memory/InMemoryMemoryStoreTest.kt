package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InMemoryMemoryStoreTest {

    @Test
    fun remembersMomLikesPeanuts() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "child_of", "妈妈"),
            ),
        )
        val hit = store.query(GRAPH_ASSISTANT, "花生")
        assertTrue(hit.facts.any { it.s == "妈妈" && it.p == "likes" && it.o == "花生" })
        val mom = store.query(GRAPH_ASSISTANT, "妈妈")
        assertTrue(mom.facts.any { it.p == "child_of" && it.o == "妈妈" })
    }

    @Test
    fun ingestThenQueryByObjectAndPredicateLabel() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val byName = store.query(GRAPH_ASSISTANT, "花生")
        assertTrue(byName.facts.any { it.p == "allergic_to" && it.o == "花生" })
        val byPred = store.query(GRAPH_ASSISTANT, "过敏")
        assertEquals(listOf("花生"), byPred.facts.map { it.o })
        assertEquals("- 用户 过敏 花生", byPred.render())
        assertTrue(store.query(GRAPH_ASSISTANT, "火锅").isEmpty)
    }

    @Test
    fun remembersUnfinishedHomework() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "has_task", "作业")))
        val hit = store.query(GRAPH_ASSISTANT, "作业")
        assertTrue(hit.facts.any { it.p == "has_task" && it.o == "作业" })
        assertEquals("- 用户 待办 作业", hit.facts.single { it.p == "has_task" }.line())
    }

    @Test
    fun blankQueryReturnsNothing() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        assertTrue(store.query(GRAPH_ASSISTANT, "").isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "   ").isEmpty)
    }

    @Test
    fun graphIdIsAHardGate() = runTest {
        val store = testStore()
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
        val store = testStore()
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
        val store = testStore()
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
        val store = testStore()
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
    fun allergicToDoesNotWipeMatchingTasteEdge() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
            ),
        )
        val facts = store.facts(GRAPH_ASSISTANT).facts
        assertTrue(facts.any { it.p == "allergic_to" && it.o == "花生" })
        assertTrue(facts.any { it.p == "likes" && it.o == "花生" })
    }

    @Test
    fun unknownPredicateDoesNotWrite() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "teleports_to", "月球"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "茶"),
                TripleDraft(GRAPH_LINWAN, "林晚", "allergic_to", "花生"),
            ),
        )
        assertEquals(listOf("茶"), store.facts(GRAPH_ASSISTANT).facts.map { it.o })
        assertTrue(store.facts(GRAPH_LINWAN).isEmpty)
    }

    @Test
    fun duplicateIngestDoesNotDuplicateFacts() = runTest {
        val store = testStore()
        val draft = TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "阿里")
        store.ingest(listOf(draft, draft))
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.count { it.p == "works_at" })
    }

    @Test
    fun queryRespectsCharBudget() = runTest {
        val store = testStore()
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
        val store = testStore()
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
        val store = testStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生", rawEventIds = listOf(id))),
        )
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
    }

    @Test
    fun rebuildReplaysFactLogAndDoesNotLeakOtherGraph() = runTest {
        val store = testStore()
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
        val store = testStore()
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "香菜", confidence = 0.2)),
        )
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.size)
        val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        store.forget(GRAPH_ASSISTANT, now = later)
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.size)
        assertTrue(store.facts(GRAPH_ASSISTANT, at = later).isEmpty)
    }

    @Test
    fun forgetLeavesFreshAndHighConfidenceEdges() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式", confidence = 0.9)))
        val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        store.forget(GRAPH_ASSISTANT, now = later)
        assertEquals(1, store.facts(GRAPH_ASSISTANT).facts.size)
    }

    @Test
    fun resolveReviewRejectArchivesEdge() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        val review = store.pendingReview(GRAPH_ASSISTANT).single()
        store.resolveReview(GRAPH_ASSISTANT, review.edgeId, accept = false)
        assertTrue(store.pendingReview(GRAPH_ASSISTANT).isEmpty())
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.p == "lives_in" })
    }

    @Test
    fun resolveReviewAcceptKeepsEdge() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        val review = store.pendingReview(GRAPH_ASSISTANT).single()
        store.resolveReview(GRAPH_ASSISTANT, review.edgeId, accept = true)
        assertEquals("上海", store.facts(GRAPH_ASSISTANT).facts.single { it.p == "lives_in" }.o)
    }

    @Test
    fun snapshotRoundTripPreservesUnconsumedAndFacts() = runTest {
        val store = testStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "先记着"))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式")))
        val runId = store.startExtractionRun(GRAPH_ASSISTANT, "s", emptyList())
        store.ingestClaims(
            "s",
            runId,
            listOf(ClaimDraft(GRAPH_ASSISTANT, "车管家", "车管家采用动态卡片引擎")),
        )
        val other = testStore()
        other.restore(store.snapshot())
        assertEquals("美式", other.facts(GRAPH_ASSISTANT).facts.single { it.p == "likes" }.o)
        assertEquals(1, other.unconsumed(GRAPH_ASSISTANT).size)
        assertEquals("先记着", other.unconsumed(GRAPH_ASSISTANT).single().text)
        assertEquals("车管家采用动态卡片引擎", other.claims(GRAPH_ASSISTANT).single().text)
        assertFalse(store.snapshot().contains("novel:"))
    }

    @Test
    fun remembersYearsWorking() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "work_years", "两年")))
        val hit = store.query(GRAPH_ASSISTANT, "两年")
        assertTrue(hit.facts.any { it.p == "work_years" && it.o == "两年" })
        assertEquals("- 用户 工龄 两年", hit.facts.single { it.p == "work_years" }.line())
    }

    @Test
    fun retractPlansArchivesOnlyMatchingEdge() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "美国"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "跳槽"),
            ),
        )
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "美国", retract = true)),
        )
        val plans = store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "plans" }.map { it.o }
        assertEquals(listOf("跳槽"), plans)
        store.rebuildFromFactLog(GRAPH_ASSISTANT)
        assertEquals(
            listOf("跳槽"),
            store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "plans" }.map { it.o },
        )
    }

    @Test
    fun factsHideWorldInvalidatedEdges() = runTest {
        val store = testStore()
        val now = System.currentTimeMillis()
        store.ingest(
            listOf(
                TripleDraft(
                    GRAPH_ASSISTANT, "用户", "plans", "美国",
                    validAt = now - 86_400_000L,
                    invalidAt = now - 1_000L,
                ),
            ),
        )
        assertTrue(store.facts(GRAPH_ASSISTANT).isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "美国").isEmpty)
    }

    @Test
    fun factsAsOfSeesEdgeBeforeRetract() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "美国")))
        val learned = System.currentTimeMillis()
        Thread.sleep(15)
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "美国", retract = true)))
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.p == "plans" })
        assertEquals("美国", store.facts(GRAPH_ASSISTANT, at = learned).facts.single { it.p == "plans" }.o)
        store.rebuildFromFactLog(GRAPH_ASSISTANT)
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.p == "plans" })
        assertEquals("美国", store.facts(GRAPH_ASSISTANT, at = learned).facts.single { it.p == "plans" }.o)
    }

    @Test
    fun functionalSupersedeKeepsAsOfHistory() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州")))
        val hangzhou = System.currentTimeMillis()
        Thread.sleep(15)
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "上海")))
        assertEquals(listOf("上海"), store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "lives_in" }.map { it.o })
        assertEquals("杭州", store.facts(GRAPH_ASSISTANT, at = hangzhou).facts.single { it.p == "lives_in" }.o)
    }

    @Test
    fun openClaimsPersistAndRecallByCjkText() = runTest {
        val store = testStore()
        val eventId = store.capture(
            RawTurn(GRAPH_ASSISTANT, "user", "车管家使用卡片引擎", sessionId = "s"),
        )
        val runId = store.startExtractionRun(GRAPH_ASSISTANT, "s", listOf(eventId))
        store.commitExtraction(
            graphId = GRAPH_ASSISTANT,
            sessionId = "s",
            runId = runId,
            eventIds = listOf(eventId),
            claims = listOf(
                ClaimDraft(
                    GRAPH_ASSISTANT,
                    "车管家",
                    "车管家通过云端下发卡片并由车端动态渲染",
                    listOf(eventId),
                ),
            ),
            drafts = emptyList(),
            outcome = ExtractOutcome.SUCCESS,
        )

        assertEquals(1, store.claims(GRAPH_ASSISTANT).size)
        assertTrue(store.queryClaims(GRAPH_ASSISTANT, "云端卡片").single().text.contains("动态渲染"))
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
    }
}
