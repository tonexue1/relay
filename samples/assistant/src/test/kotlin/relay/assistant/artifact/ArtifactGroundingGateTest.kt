package relay.assistant.artifact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.llm.model.ToolCall
import relay.uiagent.UiToolNames

class ArtifactGroundingGateTest {
    @Test
    fun `blocks unsupported numbers in markdown`() {
        val call = ToolCall(
            id = "write",
            name = UiToolNames.WRITE_MARKDOWN,
            argumentsJson = """{"name":"复盘.md","body":"能力评分从 62 提升到 92"}""",
        )

        val result = ArtifactGroundingGate.check(call, "用户给出的评分是 62 和 89")

        assertTrue(result?.block == true)
        assertTrue(result?.reason.orEmpty().contains("92"))
    }

    @Test
    fun `allows source and directly derived numbers`() {
        val call = ToolCall(
            id = "write",
            name = UiToolNames.WRITE_MARKDOWN,
            argumentsJson = """{"name":"复盘.md","body":"评分从 62 提升到 89，净增 27"}""",
        )

        assertNull(ArtifactGroundingGate.check(call, "评分 62，后来达到 89"))
    }

    @Test
    fun `does not gate non markdown tools`() {
        val call = ToolCall("chart", UiToolNames.CHART, """{"title":"92 分"}""")

        assertFalse(ArtifactGroundingGate.check(call, "62 89")?.block == true)
    }
}
