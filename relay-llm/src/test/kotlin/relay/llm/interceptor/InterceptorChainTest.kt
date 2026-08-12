package relay.llm.interceptor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import relay.llm.FakeProvider
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.Message

class InterceptorChainTest {

    private val request = ChatRequest(model = "fake-model", messages = listOf(Message.user("hi")))

    @Test
    fun `chat runs interceptors outermost first and unwinds in reverse`() = runTest {
        val trace = mutableListOf<String>()
        val provider = tracingProvider(trace).intercept(Tracer("A", trace), Tracer("B", trace))

        provider.chat(request)

        assertEquals(listOf("A>", "B>", "provider", "B<", "A<"), trace)
    }

    @Test
    fun `stream runs interceptors in the same order as chat`() = runTest {
        val trace = mutableListOf<String>()
        val provider = tracingProvider(trace).intercept(Tracer("A", trace), Tracer("B", trace))

        provider.stream(request).toList()

        assertEquals(listOf("A>", "B>", "provider", "B<", "A<"), trace)
    }

    @Test
    fun `a request rewritten by an interceptor reaches the provider`() = runTest {
        val fake = FakeProvider()
        val rewriter = object : Interceptor {
            override suspend fun intercept(chain: Chain): ChatResponse =
                chain.proceed(chain.request.copy(temperature = 0.9))
        }

        fake.intercept(rewriter).chat(request)

        assertEquals(0.9, fake.receivedRequests.single().temperature)
    }

    @Test
    fun `an interceptor can short-circuit without reaching the provider`() = runTest {
        val fake = FakeProvider()
        val canned = object : Interceptor {
            override suspend fun intercept(chain: Chain): ChatResponse = FakeProvider.okResponse("cached")
        }

        val response = fake.intercept(canned).chat(request)

        assertEquals("cached", response.message.content)
        assertEquals(0, fake.receivedRequests.size)
    }

    @Test
    fun `interceptors see the backend they wrap`() = runTest {
        val fake = FakeProvider()
        var seenProviderId: String? = null
        val inspector = object : Interceptor {
            override suspend fun intercept(chain: Chain): ChatResponse {
                seenProviderId = chain.providerInfo.id
                return chain.proceed(chain.request)
            }
        }

        fake.intercept(inspector).chat(request)

        assertEquals("fake", seenProviderId)
    }

    @Test
    fun `wrapping with no interceptors returns the provider untouched`() {
        val fake = FakeProvider()
        assertSame(fake, fake.intercept())
    }

    @Test
    fun `an interceptor that overrides only chat leaves streaming pass-through`() = runTest {
        val trace = mutableListOf<String>()
        val chatOnly = object : Interceptor {
            override suspend fun intercept(chain: Chain): ChatResponse {
                trace += "chat-only"
                return chain.proceed(chain.request)
            }
        }

        val chunks = FakeProvider().intercept(chatOnly).stream(request).toList()

        assertEquals(emptyList(), trace)
        assertEquals(3, chunks.size)
    }

    private fun tracingProvider(trace: MutableList<String>) = FakeProvider(
        onChat = {
            trace += "provider"
            FakeProvider.okResponse()
        },
        onStream = {
            flow {
                trace += "provider"
                emit(ChatChunk.Done())
            }
        },
    )

    private class Tracer(
        private val name: String,
        private val trace: MutableList<String>,
    ) : Interceptor {

        override suspend fun intercept(chain: Chain): ChatResponse {
            trace += "$name>"
            try {
                return chain.proceed(chain.request)
            } finally {
                trace += "$name<"
            }
        }

        override fun interceptStream(chain: StreamChain): Flow<ChatChunk> = flow {
            trace += "$name>"
            try {
                chain.proceed(chain.request).collect { emit(it) }
            } finally {
                trace += "$name<"
            }
        }
    }
}
