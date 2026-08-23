package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.agent.recallPad

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecallPadTest {

    @Test
    fun padsFactsAndKeepsQueryOutOfPad() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val pad = store.recallPad(GRAPH_ASSISTANT, "今晚能吃花生吗")
        assertTrue(pad.contains("已知事实"))
        assertTrue(pad.contains("花生"))
        assertTrue("今晚能吃花生吗" !in pad)
    }

    @Test
    fun missIsBlank() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        assertEquals("", store.recallPad(GRAPH_ASSISTANT, "今天天气怎么样"))
    }

    @Test
    fun pinIsAlwaysPresent() = runTest {
        val store = testStore()
        val pad = store.recallPad(GRAPH_ASSISTANT, "你好", pin = "你是私人助理")
        assertTrue(pad.contains("你是私人助理"))
    }

    @Test
    fun doesNotQueryOtherGraph() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft("novel:demo", "林晚", "located_in", "码头")))
        assertEquals("", store.recallPad(GRAPH_ASSISTANT, "码头见"))
    }

    @Test
    fun padsOpenClaimsAsRelatedExperience() = runTest {
        val store = testStore()
        val runId = store.startExtractionRun(GRAPH_ASSISTANT, "s", emptyList())
        store.ingestClaims(
            sessionId = "s",
            runId = runId,
            drafts = listOf(
                ClaimDraft(GRAPH_ASSISTANT, "车管家", "车管家通过云端下发卡片并由车端动态渲染"),
            ),
        )

        val pad = store.recallPad(GRAPH_ASSISTANT, "卡片怎么渲染")

        assertTrue(pad.contains("相关经历"))
        assertTrue(pad.contains("云端下发卡片"))
    }
}
