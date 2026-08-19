package relay.memory

import relay.llm.model.Message
import relay.llm.model.Role

fun MemoryStore.remembering(
    graphId: String,
    trim: suspend (List<Message>) -> List<Message> = { it },
    pin: String = "",
    budgetChars: Int = 2000,
    principal: String = "user",
): suspend (List<Message>) -> List<Message> = { msgs ->
    val q = msgs.lastOrNull { it.role == Role.USER }?.content.orEmpty()
    val bullets = query(graphId, q, budgetChars, principal).render()
    val prefix = buildString {
        if (pin.isNotBlank()) append(pin.trim()).append('\n')
        if (bullets.isNotBlank()) {
            append("已知事实:\n")
            append(bullets)
        }
    }
    val injected = if (prefix.isBlank()) emptyList() else listOf(Message.user(prefix.trim()))
    injected + trim(msgs)
}
