package relay.llm.provider

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import relay.llm.RelayLlmException
import relay.llm.model.Capability
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.Role
import relay.llm.model.ToolDef

class OpenAiCompatibleProviderChatTest {

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

    private fun provider(apiKey: String = "test-key") = OpenAiCompatibleProvider(
        baseUrl = server.url("/").toString(),
        apiKey = apiKey,
        models = listOf(
            ModelInfo(
                id = MODEL,
                contextWindow = 65_536,
                maxOutputTokens = 8_192,
                capabilities = setOf(Capability.STREAMING, Capability.TOOLS),
            ),
        ),
        providerId = "test-provider",
        httpClient = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build(),
    )

    @Test
    fun `maps a successful completion onto ChatResponse`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        val response = provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("你好"))))

        assertEquals(Role.ASSISTANT, response.message.role)
        assertEquals("你好,有什么可以帮你?", response.message.content)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(11, response.usage?.promptTokens)
        assertEquals(9, response.usage?.completionTokens)
        assertEquals(20, response.usage?.totalTokens)
        assertEquals(MODEL, response.model)
        assertEquals("test-provider", response.providerId)
    }

    @Test
    fun `sends OpenAI-shaped request with bearer auth and no stream flag`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        provider().chat(
            ChatRequest(
                model = MODEL,
                messages = listOf(Message.system("be brief"), Message.user("hi")),
                temperature = 0.3,
                maxTokens = 128,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(MODEL, body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.3, body["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(128, body["max_tokens"]?.jsonPrimitive?.int)
        assertNull(body["stream"], "unary calls must not ask for a stream")

        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hi", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `omits the auth header when no api key is configured`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        provider(apiKey = "").chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `merges extra fields into the request body`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        provider().chat(
            ChatRequest(
                model = MODEL,
                messages = listOf(Message.user("hi")),
                extra = mapOf("logprobs" to JsonPrimitive(true)),
            ),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["logprobs"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `maps a tool call response onto ToolCall`() = runBlocking {
        server.enqueue(jsonResponse(TOOL_CALL_BODY))

        val response = provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("weather?"))))

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        val call = response.message.toolCalls.single()
        assertEquals("call_abc", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"Shenzhen"}""", call.argumentsJson)
    }

    @Test
    fun `serialises a tool result turn with its tool_call_id`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        provider().chat(
            ChatRequest(
                model = MODEL,
                messages = listOf(Message.toolResult(toolCallId = "call_abc", content = "26C")),
            ),
        )

        val message = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals("tool", message["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call_abc", message["tool_call_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `declares tools with their type discriminator and json schema`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY))

        provider().chat(
            ChatRequest(
                model = MODEL,
                messages = listOf(Message.user("weather?")),
                tools = listOf(
                    ToolDef(
                        name = "get_weather",
                        description = "Look up the weather",
                        parameters = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject { put("city", buildJsonObject { put("type", "string") }) })
                        },
                    ),
                ),
            ),
        )

        val tool = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.contentOrNull)

        val function = tool["function"]!!.jsonObject
        assertEquals("get_weather", function["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Look up the weather", function["description"]?.jsonPrimitive?.contentOrNull)
        assertEquals("object", function["parameters"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `maps 401 to Auth and does not mark it retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("Invalid API key")))

        val error = assertFailsWith<RelayLlmException.Auth> {
            provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        assertEquals(401, error.statusCode)
        assertEquals("test-provider", error.providerId)
        assertTrue(error.message!!.contains("Invalid API key"))
        assertTrue(!error.retryable)
    }

    @Test
    fun `maps 429 to RateLimited and reads Retry-After`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody(errorBody("slow down")),
        )

        val error = assertFailsWith<RelayLlmException.RateLimited> {
            provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        assertEquals(7_000, error.retryAfterMillis)
        assertTrue(error.retryable)
    }

    @Test
    fun `maps 500 to Server and marks it retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(errorBody("boom")))

        val error = assertFailsWith<RelayLlmException.Server> {
            provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        assertEquals(500, error.statusCode)
        assertTrue(error.retryable)
    }

    @Test
    fun `maps 400 to InvalidRequest`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody("model not found")))

        val error = assertFailsWith<RelayLlmException.InvalidRequest> {
            provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("model not found"))
    }

    @Test
    fun `maps a malformed body to InvalidRequest`() = runBlocking {
        server.enqueue(jsonResponse("not json at all"))

        assertFailsWith<RelayLlmException.InvalidRequest> {
            provider().chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        Unit
    }

    @Test
    fun `honours the per-request timeout`() = runBlocking {
        server.enqueue(jsonResponse(COMPLETION_BODY).setBodyDelay(3, TimeUnit.SECONDS))

        assertFailsWith<RelayLlmException.Timeout> {
            provider().chat(
                ChatRequest(model = MODEL, messages = listOf(Message.user("hi")), timeoutMillis = 300),
            )
        }
        Unit
    }

    @Test
    fun `maps a dead endpoint to Network`() = runBlocking {
        val deadProvider = OpenAiCompatibleProvider(
            baseUrl = "http://127.0.0.1:1/",
            apiKey = "k",
            models = listOf(ModelInfo(MODEL, contextWindow = 4_096)),
            providerId = "dead",
            httpClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(),
        )

        assertFailsWith<RelayLlmException.Network> {
            deadProvider.chat(ChatRequest(model = MODEL, messages = listOf(Message.user("hi"))))
        }
        Unit
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun errorBody(message: String) = JsonObject(
        mapOf("error" to JsonObject(mapOf("message" to JsonPrimitive(message)))),
    ).toString()

    private companion object {
        const val MODEL = "deepseek-chat"

        val COMPLETION_BODY = """
            {
              "id": "chatcmpl-1",
              "model": "deepseek-chat",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "你好,有什么可以帮你?"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 11, "completion_tokens": 9, "total_tokens": 20}
            }
        """.trimIndent()

        val TOOL_CALL_BODY = """
            {
              "id": "chatcmpl-2",
              "model": "deepseek-chat",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_abc",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": "{\"city\":\"Shenzhen\"}"}
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
        """.trimIndent()
    }
}
