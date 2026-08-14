package relay.agent

import relay.llm.model.Message

/**
 * Mutable working memory for an [Agent].
 *
 * [messages] is the full transcript (source of truth). Assigning a new list copies the
 * top-level array so callers cannot mutate storage through a snapshot they still hold.
 * Window trimming happens in `transformContext` and does not write back here.
 */
class AgentState(
    systemPrompt: String,
    model: String,
    tools: List<Tool>,
    messages: List<Message> = emptyList(),
) {
    var systemPrompt: String = systemPrompt
    var model: String = model

    var tools: List<Tool> = tools.toList()
        set(value) {
            field = value.toList()
        }

    var messages: List<Message> = messages.toList()
        set(value) {
            field = value.toList()
        }

    var isRunning: Boolean = false
        internal set
}
