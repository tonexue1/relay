package relay.llm.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import relay.llm.model.ToolCallDelta

class ToolCallAccumulatorTest {

    @Test
    fun `concatenates argument fragments belonging to one call`() {
        val accumulator = ToolCallAccumulator()
            .accept(ToolCallDelta(index = 0, id = "call_1", name = "get_weather", argumentsDelta = ""))
            .accept(ToolCallDelta(index = 0, argumentsDelta = """{"city":"""))
            .accept(ToolCallDelta(index = 0, argumentsDelta = """"Shenzhen"}"""))

        val calls = accumulator.build()

        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"city":"Shenzhen"}""", calls[0].argumentsJson)
    }

    @Test
    fun `keeps parallel calls apart by index and returns them in index order`() {
        val calls = ToolCallAccumulator()
            .accept(ToolCallDelta(index = 1, id = "b", name = "second", argumentsDelta = "{}"))
            .accept(ToolCallDelta(index = 0, id = "a", name = "first", argumentsDelta = "{}"))
            .build()

        assertEquals(listOf("first", "second"), calls.map { it.name })
    }

    @Test
    fun `a call whose name never arrived is dropped as incomplete`() {
        val calls = ToolCallAccumulator()
            .accept(ToolCallDelta(index = 0, argumentsDelta = "{}"))
            .build()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a call with no arguments defaults to an empty json object`() {
        val calls = ToolCallAccumulator()
            .accept(ToolCallDelta(index = 0, id = "call_1", name = "now"))
            .build()

        assertEquals("{}", calls.single().argumentsJson)
    }
}
