package relay.memory.agent

import relay.memory.MemoryStore
import relay.memory.RecallContext

/** Pad text for the host to inject. No chat messages here. */
suspend fun MemoryStore.recallPad(
    graphId: String,
    query: String,
    budgetChars: Int = 2000,
    pin: String = "",
): String {
    val bullets = query(graphId, query, budgetChars).render()
    val remaining = (budgetChars - bullets.length - pin.length - 32).coerceAtLeast(0)
    val claims = if (remaining == 0) {
        emptyList()
    } else {
        queryClaims(graphId, query, remaining)
    }
    return buildString {
        if (pin.isNotBlank()) append(pin.trim()).append('\n')
        if (bullets.isNotBlank()) {
            append("已知事实:\n")
            append(bullets)
        }
        if (claims.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("相关经历:\n")
            append(claims.joinToString("\n") { "- ${it.text}" })
        }
    }.trim()
}

suspend fun MemoryStore.recallPad(
    graphId: String,
    query: String,
    context: RecallContext,
    budgetChars: Int = 2000,
    pin: String = "",
): String {
    val bullets = query(graphId, query, context, budgetChars).render()
    val remaining = (budgetChars - bullets.length - pin.length - 32).coerceAtLeast(0)
    val claims = if (remaining == 0) {
        emptyList()
    } else {
        queryClaims(graphId, query, context, remaining)
    }
    return buildString {
        if (pin.isNotBlank()) append(pin.trim()).append('\n')
        if (bullets.isNotBlank()) {
            append("已知事实:\n")
            append(bullets)
        }
        if (claims.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("相关经历:\n")
            append(claims.joinToString("\n") { "- ${it.text}" })
        }
    }.trim()
}
