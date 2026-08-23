package relay.memory.agent

import relay.agent.ContextAugmentation
import relay.agent.ContextAugmenter
import relay.llm.model.Message
import relay.llm.model.Role
import relay.memory.MemoryStore
import relay.memory.RecallContext

fun interface RecallQuerySelector {
    fun select(messages: List<Message>): String

    companion object {
        val LatestUser = RecallQuerySelector { messages ->
            messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        }
    }
}

fun MemoryStore.recalling(
    graphId: String,
    pin: String = "",
    budgetChars: Int = 2000,
    querySelector: RecallQuerySelector = RecallQuerySelector.LatestUser,
): ContextAugmenter = ContextAugmenter { msgs ->
    val q = querySelector.select(msgs)
    val prefix = recallPad(graphId, q, budgetChars, pin)
    if (prefix.isBlank()) {
        ContextAugmentation.Empty
    } else {
        ContextAugmentation(listOf(Message.user(prefix)))
    }
}

fun MemoryStore.recalling(
    graphId: String,
    context: RecallContext,
    pin: String = "",
    budgetChars: Int = 2000,
    querySelector: RecallQuerySelector = RecallQuerySelector.LatestUser,
): ContextAugmenter = ContextAugmenter { msgs ->
    val q = querySelector.select(msgs)
    val prefix = recallPad(graphId, q, context, budgetChars, pin)
    if (prefix.isBlank()) {
        ContextAugmentation.Empty
    } else {
        ContextAugmentation(listOf(Message.user(prefix)))
    }
}
