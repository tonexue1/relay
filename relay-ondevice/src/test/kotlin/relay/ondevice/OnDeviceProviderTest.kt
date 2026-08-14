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
import relay.ondevice.cpu.CpuPlan
import relay.ondevice.engine.GenerateResult
import relay.ondevice.engine.GenerateTimings
import relay.ondevice.engine.LlamaEngine
import relay.ondevice.model.OnDeviceModels

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
                model = OnDeviceModels.default.id,
                messages = listOf(Message.user("什么是端云协同")),
                maxTokens = 32,
            ),
        ).toList()

        val text = chunks.filterIsInstance<ChatChunk.Text>().joinToString("") { it.delta }
        assertEquals("端云协同", text)
        val done = chunks.filterIsInstance<ChatChunk.Done>().single()
        assertEquals(12, done.usage?.promptTokens)
        assertEquals(3, done.usage?.completionTokens)
        assertTrue(done.extra.isEmpty())
    }

    @Test
    fun streamForwardsNativeTimingsOnDone() = runTest {
        val engine = FakeLlamaEngine(
            pieces = listOf("ok"),
            result = GenerateResult.Ok(
                promptTokens = 8,
                completionTokens = 1,
                timings = GenerateTimings(prefillMs = 120, ttftMs = 135, decodeMs = 800),
            ),
        )
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)

        val done = provider.stream(
            ChatRequest(
                model = OnDeviceModels.default.id,
                messages = listOf(Message.user("hi")),
            ),
        ).toList().filterIsInstance<ChatChunk.Done>().single()

        assertEquals("120", done.extra[OnDeviceProvider.EXTRA_PREFILL_MS])
        assertEquals("135", done.extra[OnDeviceProvider.EXTRA_TTFT_MS])
        assertEquals("800", done.extra[OnDeviceProvider.EXTRA_DECODE_MS])
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
                model = OnDeviceModels.default.id,
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
                    model = OnDeviceModels.default.id,
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
    fun streamFormatsMessagesThroughTheEngineTemplate() = runTest {
        val engine = FakeLlamaEngine(pieces = listOf("ok"))
        engine.load("/tmp/fake.gguf")
        OnDeviceProvider(engine).stream(
            ChatRequest(
                model = OnDeviceModels.default.id,
                messages = listOf(Message.system("brief"), Message.user("hi")),
            ),
        ).toList()

        assertEquals("formatted:2", engine.lastPrompt)
    }

    @Test
    fun rejectsToolTurnsWhenFormatting() = runBlocking {
        val engine = FakeLlamaEngine()
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)
        try {
            provider.stream(
                ChatRequest(
                    model = OnDeviceModels.default.id,
                    messages = listOf(
                        Message.user("call a tool"),
                        Message.toolResult("1", "{}"),
                    ),
                ),
            ).toList()
            fail("expected InvalidRequest")
        } catch (e: RelayLlmException.InvalidRequest) {
            assertTrue(e.message!!.contains("TOOL"))
        }
    }

    @Test
    fun rejectsEmptyMessagesWhenFormatting() = runBlocking {
        val engine = FakeLlamaEngine()
        engine.load("/tmp/fake.gguf")
        val provider = OnDeviceProvider(engine)
        try {
            provider.stream(
                ChatRequest(
                    model = OnDeviceModels.default.id,
                    messages = emptyList(),
                ),
            ).toList()
            fail("expected InvalidRequest")
        } catch (e: RelayLlmException.InvalidRequest) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test
    fun requiresALoadedModel() = runBlocking {
        val provider = OnDeviceProvider(FakeLlamaEngine())
        try {
            provider.stream(
                ChatRequest(
                    model = OnDeviceModels.default.id,
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
    var lastPrompt: String? = null
        private set

    override val isLoaded: Boolean get() = loaded

    override fun load(modelPath: String, nCtx: Int, cpu: CpuPlan) {
        loaded = true
        cancelled = false
    }

    override fun unload() {
        loaded = false
    }

    override fun cancel() {
        cancelled = true
    }

    override fun formatChat(messages: List<Message>): String {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        check(messages.none { it.role == relay.llm.model.Role.TOOL }) {
            "on-device chat template does not support TOOL turns"
        }
        return "formatted:${messages.size}"
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): GenerateResult {
        check(loaded)
        lastPrompt = prompt
        if (cancelled) return GenerateResult.Cancelled
        pieces.forEach(onToken)
        return if (cancelled) GenerateResult.Cancelled else result
    }
}
