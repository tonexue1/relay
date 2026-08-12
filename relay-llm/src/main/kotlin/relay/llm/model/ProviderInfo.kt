package relay.llm.model

enum class Capability { STREAMING, TOOLS, VISION, JSON_SCHEMA, EMBEDDING }

/**
 * Per-model metadata.
 *
 * [contextWindow] is counted in tokens and drives history trimming, routing decisions
 * and context-usage metrics. Tokenizers differ per provider, so counts derived locally
 * are estimates; see `relay.llm.token.TokenCounter`.
 */
data class ModelInfo(
    val id: String,
    val contextWindow: Int,
    val maxOutputTokens: Int? = null,
    val capabilities: Set<Capability> = setOf(Capability.STREAMING),
)

/** A provider's self-description. Callers use it to discover models and their capabilities. */
data class ProviderInfo(
    val id: String,
    val models: List<ModelInfo>,
) {
    fun model(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    fun supports(modelId: String, capability: Capability): Boolean =
        model(modelId)?.capabilities?.contains(capability) == true
}
