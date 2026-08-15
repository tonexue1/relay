package relay.llm.tool

import relay.llm.model.ToolCall
import relay.llm.model.ToolCallDelta

/**
 * Reassembles streamed tool calls.
 *
 * A streamed tool call arrives as fragments: the id and name once, then the argument JSON
 * split across arbitrarily many chunks. Only the concatenation is parseable, so callers
 * must buffer -- this does that buffering, keyed by [ToolCallDelta.index].
 *
 * Not thread-safe; feed it from a single collector.
 */
class ToolCallAccumulator {

    private class Slot {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val slots = LinkedHashMap<Int, Slot>()

    val isEmpty: Boolean get() = slots.isEmpty()

    fun accept(delta: ToolCallDelta): ToolCallAccumulator {
        val slot = slots.getOrPut(delta.index) { Slot() }
        delta.id?.takeIf { it.isNotEmpty() }?.let { slot.id = it }
        delta.name?.takeIf { it.isNotEmpty() }?.let { slot.name = it }
        delta.argumentsDelta?.let { slot.arguments.append(it) }
        return this
    }

    /** Fragments without a resolved function name are dropped as incomplete. */
    fun build(): List<ToolCall> = slots.entries
        .sortedBy { it.key }
        .mapNotNull { (_, slot) ->
            val name = slot.name ?: return@mapNotNull null
            ToolCall(
                id = slot.id.orEmpty(),
                name = name,
                argumentsJson = slot.arguments.toString().ifEmpty { "{}" },
            )
        }
}
