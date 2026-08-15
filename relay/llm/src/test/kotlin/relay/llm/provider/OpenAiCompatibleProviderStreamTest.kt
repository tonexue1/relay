package relay.llm.provider

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import relay.llm.RelayLlmException
import relay.llm.foldToResponse
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo

class OpenAiCompatibleProviderStreamTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun provider() = OpenAiCompatibleProvider(
        baseUrl = server.url("/").toString(),
        apiKey = "test-key",
        models = listOf(ModelInfo(MODEL, contextWindow = 65_536)),
        providerId = "test-provider",
        httpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build(),
    )

    private fun request() = ChatRequest(model = MODEL, messages = listOf(Message.user("你好")))

    @Test
    fun `emits text deltas then a terminal Done carrying usage`() = runBlocking {
        server.enqueue(sseResponse(TEXT_STREAM))

        val chunks = provider().stream(request()).toList()

        val text = chunks.filterIsInstance<ChatChunk.Text>().map { it.delta }
        assertEquals(listOf("你", "好", ",世界"), text)

        val done = chunks.last() as ChatChunk.Done
        assertEquals(FinishReason.STOP, done.finishReason)
        assertEquals(5, done.usage?.promptTokens)
        assertEquals(4, done.usage?.completionTokens)
        assertEquals(9, done.usage?.totalTokens)
    }

    @Test
    fun `asks for a stream and for usage to be included`() = runBlocking {
        server.enqueue(sseResponse(TEXT_STREAM))
        provider().stream(request()).toList()

        val recorded = server.takeRequest()
        assertEquals("/chat/completions", recorded.path)
        assertEquals("text/event-stream", recorded.getHeader("Accept"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `parses events split across transfer chunks`() = runBlocking {
        // 7-byte HTTP chunks slice events mid-line, which the SSE reader must buffer through.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(TEXT_STREAM, 7),
        )

        val text = provider().stream(request())
            .toList()
            .filterIsInstance<ChatChunk.Text>()
            .joinToString("") { it.delta }

        assertEquals("你好,世界", text)
    }

    @Test
    fun `streams tool call fragments that fold back into one call`() = runBlocking {
        server.enqueue(sseResponse(TOOL_CALL_STREAM))

        val response = provider().stream(request()).foldToResponse()

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        val call = response.message.toolCalls.single()
        assertEquals("call_abc", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"Shenzhen"}""", call.argumentsJson)
    }

    @Test
    fun `folds a text stream into the response a unary call would have returned`() = runBlocking {
        server.enqueue(sseResponse(TEXT_STREAM))

        val response = provider().stream(request()).foldToResponse()

        assertEquals("你好,世界", response.message.content)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(9, response.usage?.totalTokens)
    }

    @Test
    fun `completes when the server ends the stream without a DONE sentinel`() = runBlocking {
        server.enqueue(sseResponse(TEXT_STREAM.substringBefore("data: [DONE]")))

        val chunks = provider().stream(request()).toList()

        assertTrue(chunks.last() is ChatChunk.Done)
        assertEquals("你好,世界", chunks.filterIsInstance<ChatChunk.Text>().joinToString("") { it.delta })
    }

    @Test
    fun `maps an error status on stream open onto the failure model`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val error = assertFailsWith<RelayLlmException.Auth> {
            provider().stream(request()).toList()
        }
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `abandoning collection early cancels the call`() = runBlocking {
        server.enqueue(sseResponse(TEXT_STREAM))

        val firstTwo = provider().stream(request()).take(2).toList()

        assertEquals(2, firstTwo.size)
        assertTrue(firstTwo.all { it is ChatChunk.Text })
    }

    private fun sseResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private companion object {
        const val MODEL = "deepseek-chat"

        fun sse(vararg data: String) = data.joinToString(separator = "") { "data: $it\n\n" }

        val TEXT_STREAM = sse(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":"你"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"好"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":",世界"}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}}""",
            "[DONE]",
        )

        val TOOL_CALL_STREAM = sse(
            """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[
                {"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}"""
                .replace("\n", "").replace("                ", ""),
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Shenzhen\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
    }
}
