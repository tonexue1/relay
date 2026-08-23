package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.llm.model.Message
import relay.memory.GRAPH_ASSISTANT
import relay.memory.TripleDraft
import relay.memory.agent.RecallQuerySelector
import relay.memory.agent.recalling

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RememberingTest {

    @Test
    fun padsFactsAsUserMessageAndKeepsTranscriptOut() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val out = store.recalling(GRAPH_ASSISTANT)
            .augment(listOf(Message.user("今晚能吃花生吗")))
            .messages
        assertEquals(1, out.size)
        assertTrue(out.single().content.orEmpty().contains("已知事实"))
        assertTrue(out.single().content.orEmpty().contains("花生"))
    }

    @Test
    fun missDoesNotInject() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val out = store.recalling(GRAPH_ASSISTANT)
            .augment(listOf(Message.user("今天天气怎么样")))
        assertTrue(out.messages.isEmpty())
    }

    @Test
    fun pinIsAlwaysInjected() = runTest {
        val store = testStore()
        val out = store.recalling(GRAPH_ASSISTANT, pin = "你是私人助理")
            .augment(listOf(Message.user("你好")))
            .messages
        assertTrue(out.single().content.orEmpty().contains("你是私人助理"))
    }

    @Test
    fun doesNotQueryOtherGraph() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft("novel:demo", "林晚", "located_in", "码头")))
        val out = store.recalling(GRAPH_ASSISTANT)
            .augment(listOf(Message.user("码头见")))
        assertTrue(out.messages.isEmpty())
    }

    @Test
    fun hostCanSelectTheRecallQuery() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val selector = RecallQuerySelector { messages ->
            messages.first { it.content.orEmpty().contains("花生") }.content.orEmpty()
        }

        val out = store.recalling(GRAPH_ASSISTANT, querySelector = selector)
            .augment(listOf(Message.user("花生过敏"), Message.user("今天天气怎么样")))

        assertTrue(out.messages.single().content.orEmpty().contains("花生"))
    }
}
