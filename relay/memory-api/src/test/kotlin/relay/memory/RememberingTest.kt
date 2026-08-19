package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.llm.model.Message
import relay.llm.model.Role

class RememberingTest {

    @Test
    fun padsFactsAsUserMessageAndKeepsTranscript() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val out = store.remembering(GRAPH_ASSISTANT)(listOf(Message.user("今晚能吃花生吗")))
        assertEquals(Role.USER, out.first().role)
        assertTrue(out.first().content.orEmpty().contains("已知事实"))
        assertTrue(out.first().content.orEmpty().contains("花生"))
        assertFalse(out.first().content.orEmpty().contains("今晚能吃花生吗"))
        assertEquals("今晚能吃花生吗", out.last().content)
        assertEquals(2, out.size)
    }

    @Test
    fun missDoesNotInject() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val out = store.remembering(GRAPH_ASSISTANT)(listOf(Message.user("今天天气怎么样")))
        assertEquals(listOf("今天天气怎么样"), out.map { it.content })
    }

    @Test
    fun pinIsAlwaysInjected() = runTest {
        val store = InMemoryMemoryStore()
        val out = store.remembering(GRAPH_ASSISTANT, pin = "你是私人助理")(
            listOf(Message.user("你好")),
        )
        assertTrue(out.first().content.orEmpty().contains("你是私人助理"))
        assertEquals("你好", out.last().content)
    }

    @Test
    fun trimRunsAfterInjection() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val out = store.remembering(GRAPH_ASSISTANT, trim = { msgs -> msgs.takeLast(1) })(
            listOf(Message.user("旧话"), Message.user("今晚能吃花生吗")),
        )
        assertTrue(out.first().content.orEmpty().contains("花生"))
        assertEquals("今晚能吃花生吗", out.last().content)
        assertEquals(2, out.size)
    }

    @Test
    fun doesNotQueryOtherGraph() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft("novel:demo", "林晚", "located_in", "码头")))
        val out = store.remembering(GRAPH_ASSISTANT)(listOf(Message.user("码头见")))
        assertEquals(listOf("码头见"), out.map { it.content })
    }
}
