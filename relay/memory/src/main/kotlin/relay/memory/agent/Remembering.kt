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
import relay.llm.embed.TextEmbedder
import relay.memory.api.SearchMemories
import relay.memory.engine.HashedEmbedder

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

fun MemoryRuntime.recallingFacts(
    spaceId: String,
    ownerId: String,
    embedder: TextEmbedder = HashedEmbedder(),
    pin: String = "",
    budgetChars: Int = 2_000,
    clock: () -> ClockStamp = { ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()) },
    querySelector: RecallQuerySelector = RecallQuerySelector.LatestUser,
): ContextAugmenter = ContextAugmenter { msgs ->
    val query = querySelector.select(msgs)
    val prefix = factPad(
        spaceId = spaceId,
        ownerId = ownerId,
        query = query,
        at = clock(),
        embedder = embedder,
        pin = pin,
        budgetChars = budgetChars,
    )
    if (prefix.isBlank()) ContextAugmentation.Empty else ContextAugmentation(listOf(Message.user(prefix)))
}

suspend fun MemoryRuntime.factPad(
    spaceId: String,
    ownerId: String,
    query: String,
    at: ClockStamp,
    embedder: TextEmbedder = HashedEmbedder(),
    pin: String = "",
    budgetChars: Int = 2_000,
): String {
    val queryVector = if (query.isBlank()) {
        null
    } else {
        runCatching { embedder.embed(query) }.getOrNull()
    }
    val hits = searchMemories(
        SearchMemories(
            spaceId = spaceId,
            ownerId = ownerId,
            query = query,
            queryVector = queryVector,
            embeddingModelId = embedder.modelId,
            at = at,
        ),
    )
    var used = 0
    val lines = buildList {
        if (pin.isNotBlank()) add(pin.trim())
        for (hit in hits) {
            val line = "- ${hit.text}"
            if (used + line.length > budgetChars) break
            add(line)
            used += line.length
        }
    }
    return if (lines.isEmpty()) "" else (listOf("相关记忆:") + lines).joinToString("\n")
}
