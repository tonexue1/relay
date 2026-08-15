package relay.agent

import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.token.HeuristicTokenCounter
import relay.llm.token.TokenCounter

/**
 * Drops the oldest non-system messages until the projected prompt fits in [contextWindow].
 *
 * This is a view over the transcript: it does not mutate the caller's list. System
 * messages are never dropped. An assistant turn that requested tools takes its following
 * tool-result turns with it so the projection stays a valid conversation.
 */
class WindowTrim(
    private val contextWindow: Int,
    private val reserveOutputTokens: Int = 0,
    private val tokenCounter: TokenCounter = HeuristicTokenCounter(),
    private val model: String = "",
    private val extraTokens: () -> Int = { 0 },
) {
    operator fun invoke(messages: List<Message>): List<Message> {
        val budget = (contextWindow - reserveOutputTokens - extraTokens()).coerceAtLeast(1)
        if (tokenCounter.count(messages, model) <= budget) return messages

        val kept = messages.toMutableList()
        while (kept.isNotEmpty() && tokenCounter.count(kept, model) > budget) {
            val idx = kept.indexOfFirst { it.role != Role.SYSTEM }
            if (idx < 0) break
            val removed = kept.removeAt(idx)
            if (removed.role == Role.ASSISTANT && removed.toolCalls.isNotEmpty()) {
                while (idx < kept.size && kept[idx].role == Role.TOOL) {
                    kept.removeAt(idx)
                }
            }
        }
        return kept
    }
}
