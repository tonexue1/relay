package relay.memory.agent

import relay.agent.ContextAugmentation
import relay.agent.ContextAugmenter
import relay.llm.model.Message
import relay.llm.model.Role
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.MemoryKind
import relay.memory.api.MemoryRuntime
import relay.memory.api.RecallRequest
import relay.memory.api.RecallStatus

fun interface RecallQuerySelector {
    fun select(messages: List<Message>): String

    companion object {
        val LatestUser = RecallQuerySelector { messages ->
            messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        }
    }
}

fun MemoryRuntime.recalling(
    spaceId: String,
    ownerId: String,
    sessionId: () -> String = { "" },
    taskScopeId: () -> String = { "" },
    includeOwners: List<String> = emptyList(),
    pin: String = "",
    budgetChars: Int = 2_000,
    clock: () -> ClockStamp = { ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()) },
    querySelector: RecallQuerySelector = RecallQuerySelector.LatestUser,
): ContextAugmenter = ContextAugmenter { msgs ->
    val query = querySelector.select(msgs)
    val prefix = recallPad(
        spaceId = spaceId,
        ownerId = ownerId,
        query = query,
        at = clock(),
        sessionId = sessionId(),
        taskScopeId = taskScopeId(),
        includeOwners = includeOwners,
        pin = pin,
        budgetChars = budgetChars,
    )
    if (prefix.isBlank()) {
        ContextAugmentation.Empty
    } else {
        ContextAugmentation(listOf(Message.user(prefix)))
    }
}

suspend fun MemoryRuntime.recallPad(
    spaceId: String,
    ownerId: String,
    query: String,
    at: ClockStamp,
    sessionId: String = "",
    taskScopeId: String = "",
    includeOwners: List<String> = emptyList(),
    pin: String = "",
    budgetChars: Int = 2_000,
): String {
    val result = recall(
        RecallRequest(
            spaceId = spaceId,
            ownerId = ownerId,
            query = query,
            at = at,
            sessionId = sessionId,
            taskScopeId = taskScopeId,
            includeOwners = includeOwners,
            budgetChars = budgetChars,
        ),
    )
    if (result.status == RecallStatus.BLOCKED && result.required.isEmpty() && result.hits.isEmpty()) {
        return pin.trim()
    }
    val states = result.required.values
        .joinToString("\n") { "- ${it.fieldId}: ${it.text}" }
    val hits = result.hits
        .filter { it.kind != MemoryKind.STATE || it.itemId !in result.required.values.map { state -> state.itemId } }
        .joinToString("\n") { "- ${it.text}" }
    return buildString {
        if (pin.isNotBlank()) append(pin.trim()).append('\n')
        if (states.isNotBlank()) {
            append("已知状态:\n")
            append(states)
        }
        if (hits.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("相关记忆:\n")
            append(hits)
        }
    }.trim()
}
