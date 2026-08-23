package relay.demo.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.agent.AgentEvent
import relay.llm.model.ChatChunk
import relay.llm.model.ToolCall
import relay.uikit.OrderedTurnReducer
import relay.uikit.ProcessStatus
import relay.uikit.TurnItem
import relay.uikit.UiToolNames

class OrderedTurnReducerTest {
    @Test
    fun `text tool text ordering is deterministic`() {
        val call = ToolCall("c1", UiToolNames.KV, """{"items":[{"key":"状态","value":"正常"}]}""")
        var turns = OrderedTurnReducer.begin(emptyList(), "检查", "turn")
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.MessageUpdate(ChatChunk.Text("先看")))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(call))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionEnd(call, """{"ok":true}""", false))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.MessageUpdate(ChatChunk.Text("完成")))

        val items = turns.last().items
        assertTrue(items[0] is TurnItem.Text)
        assertTrue(items[1] is TurnItem.Process)
        assertTrue(items[2] is TurnItem.Widget)
        assertTrue(items[3] is TurnItem.Text)
        assertEquals("先看\n状态: 正常\n完成", OrderedTurnReducer.visibleProjection(turns.last()))
    }

    @Test
    fun `parallel ends update by call id`() {
        val a = ToolCall("a", "memory_query", "{}")
        val b = ToolCall("b", "memory_facts", "{}")
        var turns = OrderedTurnReducer.begin(emptyList(), "查", "turn")
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(a))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(b))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionEnd(b, "B", false))
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionEnd(a, "A", true))

        val processes = turns.last().items.filterIsInstance<TurnItem.Process>()
        assertEquals(ProcessStatus.FAILED, processes.single { it.callId == "a" }.status)
        assertEquals(ProcessStatus.SUCCEEDED, processes.single { it.callId == "b" }.status)
    }

    @Test
    fun `cancellation seals running process`() {
        val call = ToolCall("a", "slow", "{}")
        var turns = OrderedTurnReducer.begin(emptyList(), "跑", "turn")
        turns = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(call))
        turns = OrderedTurnReducer.complete(turns)
        assertEquals(ProcessStatus.CANCELLED, (turns.last().items.single() as TurnItem.Process).status)
    }
}
