package relay.memory.agent

import relay.agent.ContextAugmentation
import relay.agent.ContextAugmenter
import relay.llm.model.Message
import relay.llm.model.Role
import relay.memory.MemoryStore

fun MemoryStore.recalling(
    graphId: String,
    pin: String = "",
    budgetChars: Int = 2000,
): ContextAugmenter = ContextAugmenter { msgs ->
    val q = msgs.lastOrNull { it.role == Role.USER }?.content.orEmpty()
    val prefix = recallPad(graphId, q, budgetChars, pin)
    if (prefix.isBlank()) {
        ContextAugmentation.Empty
    } else {
        ContextAugmentation(listOf(Message.user(prefix)))
    }
}
