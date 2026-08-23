package relay.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import relay.uikit.ProcessStatus
import relay.uikit.TurnItem
import relay.uikit.UiToolNames

class ProcessDisplayPolicyTest {
    @Test
    fun `successful presentation calls stay out of reading flow`() {
        val process = process(UiToolNames.CHART)

        assertEquals(ProcessDisplay.HIDDEN, processDisplay(process))
    }

    @Test
    fun `presentation failures remain visible`() {
        val process = process(UiToolNames.TABLE, ProcessStatus.FAILED)

        assertEquals(ProcessDisplay.ERROR, processDisplay(process))
    }

    @Test
    fun `memory calls collapse into one readable summary`() {
        val processes = listOf(process("memory_query"), process("memory_facts"))

        assertEquals("参考了记忆 · 2 次查询", processSummary(processes))
    }

    private fun process(
        name: String,
        status: ProcessStatus = ProcessStatus.SUCCEEDED,
    ) = TurnItem.Process(
        id = name,
        callId = name,
        label = name,
        argumentsSummary = "{}",
        status = status,
    )
}
