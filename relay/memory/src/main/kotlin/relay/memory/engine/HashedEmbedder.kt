package relay.memory.engine

import kotlin.math.sqrt
import relay.llm.embed.TextEmbedder

/** Bag-of-hashed-tokens vector for tests and offline fallback. */
class HashedEmbedder(
    val dimensions: Int = 64,
    override val modelId: String = "hash-64",
) : TextEmbedder {
    override suspend fun embed(text: String): FloatArray {
        val vec = FloatArray(dimensions)
        for (token in queryTokens(text)) {
            val h = token.hashCode()
            val i = kotlin.math.abs(h) % dimensions
            vec[i] += if (h < 0) -1f else 1f
        }
        var n = 0.0
        for (v in vec) n += v * v
        val denom = sqrt(n).toFloat()
        if (denom == 0f) return vec
        for (i in vec.indices) vec[i] /= denom
        return vec
    }
}
