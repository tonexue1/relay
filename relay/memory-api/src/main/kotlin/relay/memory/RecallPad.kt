package relay.memory

/** Pad text for the host to inject. No chat messages here. */
suspend fun MemoryStore.recallPad(
    graphId: String,
    query: String,
    budgetChars: Int = 2000,
    principal: String = "user",
    pin: String = "",
): String {
    val bullets = query(graphId, query, budgetChars, principal).render()
    return buildString {
        if (pin.isNotBlank()) append(pin.trim()).append('\n')
        if (bullets.isNotBlank()) {
            append("已知事实:\n")
            append(bullets)
        }
    }.trim()
}
