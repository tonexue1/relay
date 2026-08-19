package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RecallPadTest {

    @Test
    fun padsFactsAndKeepsQueryOutOfPad() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val pad = store.recallPad(GRAPH_ASSISTANT, "今晚能吃花生吗")
        assertTrue(pad.contains("已知事实"))
        assertTrue(pad.contains("花生"))
        assertTrue("今晚能吃花生吗" !in pad)
    }

    @Test
    fun missIsBlank() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        assertEquals("", store.recallPad(GRAPH_ASSISTANT, "今天天气怎么样"))
    }

    @Test
    fun pinIsAlwaysPresent() = runTest {
        val store = InMemoryMemoryStore()
        val pad = store.recallPad(GRAPH_ASSISTANT, "你好", pin = "你是私人助理")
        assertTrue(pad.contains("你是私人助理"))
    }

    @Test
    fun doesNotQueryOtherGraph() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft("novel:demo", "林晚", "located_in", "码头")))
        assertEquals("", store.recallPad(GRAPH_ASSISTANT, "码头见"))
    }
}
