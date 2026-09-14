package relay.llm.embed

/**
 * Turns text into a dense vector. Memory stores whatever the host returns.
 *
 * Swap implementations without touching the ledger: cloud OpenAI-compatible now,
 * on-device GGUF later. [modelId] is the index key — changing it orphans old vectors.
 */
interface TextEmbedder {
    val modelId: String

    suspend fun embed(text: String): FloatArray
}
