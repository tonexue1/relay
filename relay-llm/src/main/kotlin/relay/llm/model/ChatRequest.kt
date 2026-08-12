package relay.llm.model

import kotlinx.serialization.json.JsonElement

/**
 * A provider-agnostic chat request.
 *
 * [extra] is an escape hatch for provider-specific fields; entries are merged into the
 * request body verbatim, so callers opting into it give up cross-provider portability.
 */
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<ToolDef> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val timeoutMillis: Long? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
)
