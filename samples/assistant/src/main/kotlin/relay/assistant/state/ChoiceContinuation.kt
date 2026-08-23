package relay.assistant.state

import relay.uiagent.ChatTurn
import relay.uiagent.TurnItem
import relay.uikit.ChoiceFormSpec

internal object ChoiceContinuation {
    fun taskAnchor(
        turns: List<ChatTurn>,
        formTurnId: String,
        spec: ChoiceFormSpec,
    ): String {
        val formIndex = turns.indexOfFirst { it.id == formTurnId }.let { if (it < 0) turns.size else it }
        val originalRequest = turns.take(formIndex)
            .asReversed()
            .filter { it.role == "user" }
            .mapNotNull { turn ->
                turn.items.filterIsInstance<TurnItem.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                    .takeIf { it.isNotBlank() && !it.startsWith("原始任务：") }
            }
            .firstOrNull()
            ?.take(400)
        val modelAnchor = spec.taskAnchor.trim().take(180)
        return when {
            originalRequest != null && modelAnchor.isNotBlank() ->
                "用户原始请求：$originalRequest；任务摘要：$modelAnchor"
            originalRequest != null -> originalRequest
            modelAnchor.isNotBlank() -> modelAnchor
            else -> "回答当前选择表单对应的问题"
        }
    }

    fun message(
        spec: ChoiceFormSpec,
        answers: Map<String, List<String>>,
        taskAnchor: String,
    ): String = buildString {
        appendLine("原始任务：$taskAnchor")
        appendLine("我已提交选择表单「${spec.title}」：")
        spec.questions.forEach { question ->
            val labels = answers[question.id].orEmpty().mapNotNull { answerId ->
                question.options.firstOrNull { it.id == answerId }?.label
            }
            if (labels.isNotEmpty()) appendLine("- ${question.title}：${labels.joinToString("、")}")
        }
        append(
            "表单已经完成，不要再次调用 render_choice_form 重复相同问题。" +
                "请仅围绕上述原始任务和选择完成下一步，不要引入与该任务无关的历史话题；" +
                "如果原始任务只要求收集选择，简短确认后结束。",
        )
    }
}
