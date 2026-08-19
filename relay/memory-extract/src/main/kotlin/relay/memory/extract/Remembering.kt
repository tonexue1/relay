package relay.memory.extract

import relay.llm.model.Message
import relay.llm.model.Role
import relay.memory.MemoryStore
import relay.memory.recallPad

fun MemoryStore.remembering(
    graphId: String,
    trim: suspend (List<Message>) -> List<Message> = { it },
    pin: String = "",
    budgetChars: Int = 2000,
    principal: String = "user",
): suspend (List<Message>) -> List<Message> = { msgs ->
    val q = msgs.lastOrNull { it.role == Role.USER }?.content.orEmpty()
    val prefix = recallPad(graphId, q, budgetChars, principal, pin)
    val injected = if (prefix.isBlank()) emptyList() else listOf(Message.user(prefix))
    injected + trim(msgs)
}
