package relay.llm.interceptor

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import relay.llm.FakeProvider
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message

class RetryInterceptorTest {

    private val request = ChatRequest(model = "fake-model", messages = listOf(Message.user("hi")))
    private val noJitter = RetryInterceptor(jitterRatio = 0.0)

    @Test
    fun `retries a transient failure and returns the eventual success`() = runTest {
        val provider = FakeProvider.failingTimes(2) { RelayLlmException.Network("flaky") }

        val response = provider.intercept(noJitter).chat(request)

        assertEquals("ok", response.message.content)
        assertEquals(3, provider.receivedRequests.size)
    }

    @Test
    fun `gives up once the attempt budget is spent`() = runTest {
        val provider = FakeProvider.failingTimes(5) { RelayLlmException.Network("flaky") }

        assertFailsWith<RelayLlmException.Network> {
            provider.intercept(RetryInterceptor(maxAttempts = 3, jitterRatio = 0.0)).chat(request)
        }
        assertEquals(3, provider.receivedRequests.size)
    }

    @Test
    fun `does not retry failures that a second attempt cannot fix`() = runTest {
        val provider = FakeProvider.failingTimes(5) { RelayLlmException.Auth("bad key", 401) }

        assertFailsWith<RelayLlmException.Auth> {
            provider.intercept(noJitter).chat(request)
        }
        assertEquals(1, provider.receivedRequests.size)
    }

    @Test
    fun `retries a stream that failed before emitting anything`() = runTest {
        val provider = FakeProvider.failingTimes(1) { RelayLlmException.Server("boom", 503) }

        val chunks = provider.intercept(noJitter).stream(request).toList()

        assertEquals(2, chunks.size)
        assertEquals(2, provider.receivedRequests.size)
    }

    @Test
    fun `does not retry a stream that already delivered output`() = runTest {
        val provider = FakeProvider(
            onStream = {
                flow {
                    emit(ChatChunk.Text("par"))
                    throw RelayLlmException.Network("dropped mid-stream")
                }
            },
        )

        val delivered = mutableListOf<ChatChunk>()
        assertFailsWith<RelayLlmException.Network> {
            provider.intercept(noJitter).stream(request).collect { delivered += it }
        }

        assertEquals(1, delivered.size)
        assertEquals(1, provider.receivedRequests.size)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val interceptor = RetryInterceptor(
            initialBackoffMillis = 100,
            maxBackoffMillis = 300,
            jitterRatio = 0.0,
        )
        val error = RelayLlmException.Network("flaky")

        assertEquals(100, interceptor.backoffMillis(1, error))
        assertEquals(200, interceptor.backoffMillis(2, error))
        assertEquals(300, interceptor.backoffMillis(3, error))
        assertEquals(300, interceptor.backoffMillis(9, error))
    }

    @Test
    fun `a Retry-After hint overrides the computed backoff`() {
        val interceptor = RetryInterceptor(initialBackoffMillis = 100, maxBackoffMillis = 10_000)
        val error = RelayLlmException.RateLimited("slow down", retryAfterMillis = 4_000)

        assertEquals(4_000, interceptor.backoffMillis(1, error))
    }

    @Test
    fun `jitter only ever extends the wait`() {
        val interceptor = RetryInterceptor(
            initialBackoffMillis = 100,
            jitterRatio = 0.5,
            random = Random(42),
        )
        val error = RelayLlmException.Network("flaky")

        repeat(20) {
            val backoff = interceptor.backoffMillis(1, error)
            assertTrue(backoff in 100..150, "backoff $backoff outside [100, 150]")
        }
    }
}
