package relay.assistant.memory

import okhttp3.OkHttpClient
import relay.assistant.BuildConfig
import relay.llm.embed.OpenAiCompatibleEmbedder
import relay.llm.embed.TextEmbedder
import relay.memory.engine.HashedEmbedder

fun assistantEmbedder(httpClient: OkHttpClient): TextEmbedder {
    val key = BuildConfig.EMBEDDING_API_KEY.trim()
    if (key.isBlank()) return HashedEmbedder()
    return OpenAiCompatibleEmbedder(
        baseUrl = BuildConfig.EMBEDDING_BASE_URL,
        apiKey = key,
        model = BuildConfig.EMBEDDING_MODEL,
        httpClient = httpClient,
    )
}
