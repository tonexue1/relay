package relay.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import relay.agent.AgentEvent
import relay.artifacts.FileArtifactRepository
import relay.llm.model.ToolCall

class OrderedTurnReducerTest {
    @Test
    fun `continuation opens assistant turn without synthetic user bubble`() {
        val turns = OrderedTurnReducer.beginContinuation(emptyList(), id = "choice")

        assertEquals(1, turns.size)
        assertEquals("assistant", turns.single().role)
        assertFalse(turns.single().complete)
    }

    @Test
    fun `invalid renderer call does not add a fallback widget to the chat`() {
        val turns = OrderedTurnReducer.beginContinuation(emptyList(), id = "bad-widget")
        val call = ToolCall(
            id = "chart-1",
            name = UiToolNames.CHART,
            argumentsJson = """{"series":[{"name":"收入","kind":"BAR","points":[{"label":"Q1","value":1}]},{"name":"转化","kind":"LINE","points":[{"label":"Q2","value":2}]}]}""",
        )

        val reduced = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(call))

        assertTrue(reduced.single().items.none { it is TurnItem.Widget })
        assertTrue(reduced.single().items.single() is TurnItem.Process)
    }

    @Test
    fun `renderer adds its widget only after a successful execution`() {
        val turns = OrderedTurnReducer.beginContinuation(emptyList(), id = "pending-widget")
        val call = ToolCall(
            id = "chart-2",
            name = UiToolNames.CHART,
            argumentsJson = """{"points":[{"label":"Q1","value":1}]}""",
        )
        val chart = uiArtifactTools(FileArtifactRepository(Files.createTempDirectory("ui-kit-test").toFile()))
            .first { it.def.name == UiToolNames.CHART }
        val output = runBlocking { chart.executeOutput(call.id, call.argumentsJson) }

        val started = OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(call))
        val completed = OrderedTurnReducer.reduce(
            started,
            AgentEvent.ToolExecutionEnd(call, output.content, isError = false, eventData = output.eventData),
        )

        assertTrue(started.single().items.none { it is TurnItem.Widget })
        assertTrue(completed.single().items.single { it is TurnItem.Widget } is TurnItem.Widget)
    }

    @Test
    fun `resuming a submitted choice form does not append its original editable widget`() {
        val turns = OrderedTurnReducer.beginContinuation(emptyList(), id = "choice-resume")
        val call = ToolCall(
            id = "choice-1",
            name = UiToolNames.CHOICE_FORM,
            argumentsJson = """{"title":"方案","questions":[{"id":"plan","kind":"SINGLE","title":"选方案","options":[{"id":"a","label":"方案 A"}]}]}""",
        )
        val tool = uiArtifactTools(FileArtifactRepository(Files.createTempDirectory("ui-kit-test").toFile()))
            .first { it.def.name == UiToolNames.CHOICE_FORM }
        val output = runBlocking { tool.executeOutput(call.id, call.argumentsJson) }

        val waiting = OrderedTurnReducer.reduce(
            OrderedTurnReducer.reduce(turns, AgentEvent.ToolExecutionStart(call)),
            AgentEvent.ToolExecutionWaiting(call, output.eventData),
        )
        val submitted = waiting.map { turn ->
            turn.copy(items = turn.items.map { item ->
                val widget = item as? TurnItem.Widget
                if (widget?.callId == call.id) {
                    widget.copy(spec = (widget.spec as ChoiceFormSpec).copy(submittedAnswers = mapOf("plan" to listOf("a"))))
                } else {
                    item
                }
            })
        }

        val resumed = OrderedTurnReducer.reduce(
            submitted,
            AgentEvent.ToolExecutionEnd(call, "{\"ok\":true}", isError = false, eventData = output.eventData),
        )

        val widgets = resumed.single().items.filterIsInstance<TurnItem.Widget>()
        assertEquals(1, widgets.size)
        assertTrue((widgets.single().spec as ChoiceFormSpec).submittedAnswers != null)
    }
}
