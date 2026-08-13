package relay.ondevice

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.llm.model.ToolDef
import relay.ondevice.engine.GenerateResult
import relay.ondevice.engine.LlamaEngine

class OnDeviceProviderTest {

    @Test
    fun streamEmitsTextPiecesThenDoneWithUsage() = runTest {
        val engine = FakeLlamaEngine(
            pieces = listOf("端", "云", "协同"),
            result = GenerateResult.Ok(promptTokens = 12, completionTokens = 3),
        )
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)

        val chunks = provider.stream(
            ChatRequest(
                model = "qwen2.5-0.5b-instruct",
                messages = listOf(Message.user("什么是端云协同")),
                maxTokens = 32,
            ),
        ).toList()

        val text = chunks.filterIsInstance<ChatChunk.Text>().joinToString("") { it.delta }
        assertEquals("端云协同", text)
        val done = chunks.filterIsInstance<ChatChunk.Done>().single()
        assertEquals(12, done.usage?.promptTokens)
        assertEquals(3, done.usage?.completionTokens)
    }

    @Test
    fun chatFoldsTheStreamIntoAResponse() = runTest {
        val engine = FakeLlamaEngine(
            pieces = listOf("ok"),
            result = GenerateResult.Ok(promptTokens = 4, completionTokens = 1),
        )
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)

        val response = provider.chat(
            ChatRequest(
                model = "qwen2.5-0.5b-instruct",
                messages = listOf(Message.user("ping")),
            ),
        )

        assertEquals("ok", response.message.content)
        assertEquals(OnDeviceProvider.PROVIDER_ID, response.providerId)
    }

    @Test
    fun rejectsTools() = runBlocking {
        val engine = FakeLlamaEngine()
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)

        try {
            provider.stream(
                ChatRequest(
                    model = "qwen2.5-0.5b-instruct",
                    messages = listOf(Message.user("use a tool")),
                    tools = listOf(
                        ToolDef(
                            name = "lookup",
                            description = "x",
                            parameters = buildJsonObject {},
                        ),
                    ),
                ),
            ).toList()
            fail("expected InvalidRequest")
        } catch (e: RelayLlmException.InvalidRequest) {
            assertTrue(e.message!!.contains("tools"))
        }
    }

    @Test
    fun requiresALoadedModel() = runBlocking {
        val provider = OnDeviceProvider(FakeLlamaEngine())
        try {
            provider.stream(
                ChatRequest(
                    model = "qwen2.5-0.5b-instruct",
                    messages = listOf(Message.user("hi")),
                ),
            ).toList()
            fail("expected InvalidRequest")
        } catch (_: RelayLlmException.InvalidRequest) {
            // expected
        }
    }
}

private class FakeLlamaEngine(
    private val pieces: List<String> = emptyList(),
    private val result: GenerateResult = GenerateResult.Ok(1, 1),
) : LlamaEngine {
    private var loaded = false
    private var cancelled = false

    override val isLoaded: Boolean get() = loaded

    override fun load(modelPath: String, nCtx: Int, nThreads: Int) {
        loaded = true
        cancelled = false
    }

    override fun unload() {
        loaded = false
    }

    override fun cancel() {
        cancelled = true
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): GenerateResult {
        check(loaded)
        if (cancelled) return GenerateResult.Cancelled
        pieces.forEach(onToken)
        return if (cancelled) GenerateResult.Cancelled else result
    }
}
