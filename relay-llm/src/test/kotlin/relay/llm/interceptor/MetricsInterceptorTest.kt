package relay.llm.interceptor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import relay.llm.FakeProvider
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message

class MetricsInterceptorTest {

    private val request = ChatRequest(model = "fake-model", messages = listOf(Message.user("hi")))
    private val recorded = mutableListOf<CallMetrics>()
    private val metrics = MetricsInterceptor { recorded += it }

    @Test
    fun `records usage and outcome for a unary call`() = runTest {
        FakeProvider().intercept(metrics).chat(request)

        val call = recorded.single()
        assertEquals("fake", call.providerId)
        assertEquals("fake-model", call.model)
        assertFalse(call.streaming)
        assertTrue(call.succeeded)
        assertEquals(FinishReason.STOP, call.finishReason)
        assertEquals(4, call.usage?.totalTokens)
    }

    @Test
    fun `records time to first token for a stream`() = runTest {
        FakeProvider().intercept(metrics).stream(request).toList()

        val call = recorded.single()
        assertTrue(call.streaming)
        assertNotNull(call.timeToFirstTokenMillis)
        assertEquals(5, call.usage?.totalTokens)
    }

    @Test
    fun `records failures instead of swallowing them`() = runTest {
        val provider = FakeProvider(onChat = { throw RelayLlmException.Server("boom", 500) })

        assertFailsWith<RelayLlmException.Server> {
            provider.intercept(metrics).chat(request)
        }

        val call = recorded.single()
        assertFalse(call.succeeded)
        assertTrue(call.error is RelayLlmException.Server)
    }

    @Test
    fun `records a stream that failed after partial output`() = runTest {
        val provider = FakeProvider(
            onStream = {
                flow {
                    emit(ChatChunk.Text("par"))
                    throw RelayLlmException.Network("dropped")
                }
            },
        )

        assertFailsWith<RelayLlmException.Network> {
            provider.intercept(metrics).stream(request).toList()
        }

        val call = recorded.single()
        assertTrue(call.streaming)
        assertFalse(call.succeeded)
        assertNotNull(call.timeToFirstTokenMillis)
    }
}
