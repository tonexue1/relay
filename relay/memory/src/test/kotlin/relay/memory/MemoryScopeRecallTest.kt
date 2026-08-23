package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryScopeRecallTest {

    @Test
    fun recallUsesConfirmedProfileAndOnlyCurrentTaskAndSession() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "头孢"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "手冲"),
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "plans",
                    "上海面试",
                    scope = MemoryScope.TASK,
                    scopeId = "task-a",
                ),
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "plans",
                    "北京面试",
                    scope = MemoryScope.TASK,
                    scopeId = "task-b",
                ),
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "has_task",
                    "整理发票",
                    scope = MemoryScope.SESSION,
                    scopeId = "session-a",
                ),
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "has_task",
                    "提交周报",
                    scope = MemoryScope.SESSION,
                    scopeId = "session-b",
                ),
            ),
        )

        val current = RecallContext(sessionId = "session-a", taskScopeId = "task-a")
        assertEquals(MemoryState.CONFIRMED, store.query(GRAPH_ASSISTANT, "头孢", current).facts.single().state)
        assertTrue(store.query(GRAPH_ASSISTANT, "上海面试", current).facts.isNotEmpty())
        assertTrue(store.query(GRAPH_ASSISTANT, "整理发票", current).facts.isNotEmpty())
        assertTrue(store.query(GRAPH_ASSISTANT, "手冲", current).isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "北京面试", current).isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "提交周报", current).isEmpty)

        val crossTask = current.copy(allowCrossTask = true)
        assertTrue(store.query(GRAPH_ASSISTANT, "北京面试", crossTask).facts.isNotEmpty())
        assertTrue(store.query(GRAPH_ASSISTANT, "提交周报", crossTask).isEmpty)
    }

    @Test
    fun assistantOnlyEvidenceCannotPromoteProfileFact() = runTest {
        val store = testStore()
        val assistantEvent = store.capture(
            RawTurn(GRAPH_ASSISTANT, "assistant", "用户对青霉素过敏", sessionId = "s1"),
        )
        store.ingest(
            listOf(
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "allergic_to",
                    "青霉素",
                    rawEventIds = listOf(assistantEvent),
                    state = MemoryState.CONFIRMED,
                ),
            ),
        )

        val stored = store.facts(GRAPH_ASSISTANT).facts.single()
        assertEquals(MemoryState.CANDIDATE, stored.state)
        assertTrue(store.query(GRAPH_ASSISTANT, "青霉素", RecallContext(sessionId = "s1")).isEmpty)
    }

    @Test
    fun openClaimsDefaultToCurrentSession() = runTest {
        val store = testStore()
        val claim = store.ingestClaims(
            sessionId = "session-a",
            runId = "run-a",
            drafts = listOf(
                ClaimDraft(GRAPH_ASSISTANT, "车管家", "车管家通过云端卡片动态渲染主界面"),
            ),
        ).single()

        assertEquals(MemoryScope.SESSION, claim.scope)
        assertEquals(MemoryState.CANDIDATE, claim.state)
        assertEquals("session-a", claim.scopeId)
        assertTrue(
            store.queryClaims(
                GRAPH_ASSISTANT,
                "云端卡片",
                RecallContext(sessionId = "session-a"),
            ).isNotEmpty(),
        )
        assertTrue(
            store.queryClaims(
                GRAPH_ASSISTANT,
                "云端卡片",
                RecallContext(sessionId = "session-b"),
            ).isEmpty(),
        )
    }

    @Test
    fun researchRelationsStayTaskScopedAndRepeatedPreferencePromotes() = runTest {
        val store = testStore()
        val first = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我喜欢蓝色", sessionId = "s1"))
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "Android", "has_component", "Binder", scopeId = "task-android"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "蓝色", rawEventIds = listOf(first)),
            ),
        )

        val initial = store.facts(GRAPH_ASSISTANT).facts
        assertEquals(MemoryScope.TASK, initial.single { it.p == "has_component" }.scope)
        assertEquals(MemoryState.CANDIDATE, initial.single { it.p == "likes" }.state)

        val second = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "蓝色仍然是我的偏好", sessionId = "s2"))
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "蓝色", rawEventIds = listOf(second)),
            ),
        )

        assertEquals(
            MemoryState.CONFIRMED,
            store.facts(GRAPH_ASSISTANT).facts.single { it.p == "likes" }.state,
        )
    }

    @Test
    fun stableRelationAboutNonUserDoesNotBecomeGlobalProfile() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "张三", "allergic_to", "花生")))

        val fact = store.facts(GRAPH_ASSISTANT).facts.single()

        assertEquals(MemoryScope.SESSION, fact.scope)
        assertEquals(MemoryState.CANDIDATE, fact.state)
        assertTrue(store.query(GRAPH_ASSISTANT, "张三花生", RecallContext(sessionId = "other")).isEmpty)
    }

    @Test
    fun colorFormAndClientDevelopmentCannotPullAndroidResearchAcrossTasks() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "worked_on",
                    "安卓开发",
                    scopeId = "task-android",
                ),
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "头孢"),
            ),
        )
        val colorSession = RecallContext(sessionId = "session-color", taskScopeId = "task-color")

        assertTrue(store.query(GRAPH_ASSISTANT, "我选择蓝色", colorSession).isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "客户端开发", colorSession).isEmpty)
        assertFalse(
            store.query(
                GRAPH_ASSISTANT,
                "安卓开发",
                RecallContext(sessionId = "session-android", taskScopeId = "task-android"),
            ).isEmpty,
        )
        assertFalse(store.query(GRAPH_ASSISTANT, "头孢过敏", colorSession).isEmpty)
    }

    @Test
    fun extractionDefaultsPlansToTaskBoundToSession() = runTest {
        val store = testStore()
        store.commitExtraction(
            graphId = GRAPH_ASSISTANT,
            sessionId = "session-a",
            runId = "run-a",
            eventIds = emptyList(),
            claims = emptyList(),
            drafts = listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "准备答辩")),
            outcome = ExtractOutcome.SUCCESS,
        )

        val fact = store.facts(GRAPH_ASSISTANT).facts.single()
        assertEquals(MemoryScope.TASK, fact.scope)
        assertEquals(MemoryState.CANDIDATE, fact.state)
        assertEquals("session-a", fact.scopeId)
        assertFalse(
            store.query(
                GRAPH_ASSISTANT,
                "准备答辩",
                RecallContext(taskScopeId = "session-a"),
            ).isEmpty,
        )
        assertTrue(
            store.query(
                GRAPH_ASSISTANT,
                "准备答辩",
                RecallContext(taskScopeId = "session-b"),
            ).isEmpty,
        )
    }

    @Test
    fun shortCjkSingleBigramOverlapDoesNotRecall() = runTest {
        val store = testStore()
        store.ingest(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生酱"),
                TripleDraft(
                    GRAPH_ASSISTANT,
                    "用户",
                    "worked_on",
                    "云端卡片引擎",
                    scopeId = "task-ui",
                ),
            ),
        )

        assertTrue(store.query(GRAPH_ASSISTANT, "生酱油怎么用", RecallContext()).isEmpty)
        assertFalse(store.query(GRAPH_ASSISTANT, "别放花生酱", RecallContext()).isEmpty)
        assertFalse(store.query(GRAPH_ASSISTANT, "花生酱", RecallContext()).isEmpty)
        assertTrue(
            store.query(
                GRAPH_ASSISTANT,
                "云端卡片怎么做",
                RecallContext(taskScopeId = "task-ui"),
            ).facts.any {
                it.o == "云端卡片引擎"
            },
        )
    }
}
