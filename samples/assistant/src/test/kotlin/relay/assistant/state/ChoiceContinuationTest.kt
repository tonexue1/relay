package relay.assistant.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.uikit.ChatTurn
import relay.uikit.TurnItem
import relay.uikit.ChoiceFormSpec
import relay.uikit.ChoiceOption
import relay.uikit.ChoiceQuestion

class ChoiceContinuationTest {
    private val form = ChoiceFormSpec(
        title = "确定学习方式",
        taskAnchor = "制定 Kotlin 学习计划",
        questions = listOf(
            ChoiceQuestion(
                id = "pace",
                title = "学习节奏？",
                options = listOf(ChoiceOption("steady", "稳步推进")),
            ),
        ),
    )

    @Test
    fun `anchor always retains original user request`() {
        val turns = listOf(
            ChatTurn("user", "user", listOf(TurnItem.Text("text", "帮我制定 Kotlin 学习计划"))),
            ChatTurn("form", "assistant", listOf(TurnItem.Widget("widget", "call", form))),
        )

        val anchor = ChoiceContinuation.taskAnchor(turns, "form", form)

        assertTrue(anchor.contains("制定 Kotlin 学习计划"))
        assertTrue(anchor.contains("帮我制定 Kotlin 学习计划"))
    }

    @Test
    fun `long model anchor cannot displace real request or reuse synthetic submission`() {
        val longForm = form.copy(taskAnchor = "模型摘要".repeat(200))
        val turns = listOf(
            ChatTurn("real", "user", listOf(TurnItem.Text("real-text", "帮我选择衣服颜色"))),
            ChatTurn(
                "synthetic",
                "user",
                listOf(TurnItem.Text("synthetic-text", "原始任务：旧任务\n请继续")),
            ),
            ChatTurn("form", "assistant", listOf(TurnItem.Widget("widget", "call", longForm))),
        )

        val anchor = ChoiceContinuation.taskAnchor(turns, "form", longForm)

        assertTrue(anchor.contains("帮我选择衣服颜色"))
        assertFalse(anchor.contains("旧任务"))
    }

    @Test
    fun `submission is anchored and contains no generic continue instruction`() {
        val message = ChoiceContinuation.message(
            form,
            mapOf("pace" to listOf("steady")),
            "制定 Kotlin 学习计划",
        )

        assertTrue(message.contains("原始任务：制定 Kotlin 学习计划"))
        assertTrue(message.contains("学习节奏？：稳步推进"))
        assertTrue(message.contains("不要引入与该任务无关的历史话题"))
        assertTrue(message.contains("不要再次调用 render_choice_form"))
        assertFalse(message.contains("请根据这些选择继续"))
    }
}
