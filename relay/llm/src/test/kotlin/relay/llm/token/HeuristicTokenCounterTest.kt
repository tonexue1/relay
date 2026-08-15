package relay.llm.token

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import relay.llm.model.Message
import relay.llm.model.ToolDef

class HeuristicTokenCounterTest {

    private val counter = HeuristicTokenCounter()

    @Test
    fun `empty text costs nothing`() {
        assertEquals(0, counter.count(""))
        assertEquals(0, counter.count(emptyList<Message>()))
    }

    @Test
    fun `latin text uses roughly four characters per token`() {
        // 40 characters / 4.0
        assertEquals(10, counter.count("a".repeat(40)))
    }

    @Test
    fun `chinese text costs more per character than latin`() {
        val chinese = "端侧人工智能推理引擎"
        val latin = "a".repeat(chinese.length)
        assertTrue(
            counter.count(chinese) > counter.count(latin),
            "CJK should be denser in tokens than Latin of equal length",
        )
    }

    @Test
    fun `mixed text counts each character class separately`() {
        // 10 CJK / 1.7 = 5.88, 40 latin / 4.0 = 10 -> ceil(15.88)
        assertEquals(16, counter.count("端侧人工智能推理引擎" + "a".repeat(40)))
    }

    @Test
    fun `messages add per-message overhead and reply priming`() {
        val messages = listOf(Message.user("a".repeat(40)))
        // 4 overhead + 10 content + 3 priming
        assertEquals(17, counter.count(messages))
    }

    @Test
    fun `tool schemas are counted as prompt input`() {
        val tool = ToolDef(
            name = "get_weather",
            description = "Look up the weather",
            parameters = buildJsonObject { put("type", "object") },
        )
        assertTrue(counter.countTools(listOf(tool)) > 0)
    }
}
