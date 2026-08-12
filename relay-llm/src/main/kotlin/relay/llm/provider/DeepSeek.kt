package relay.llm.provider

import okhttp3.OkHttpClient
import relay.llm.Provider
import relay.llm.model.Capability
import relay.llm.model.ModelInfo

/**
 * DeepSeek preset for [OpenAiCompatibleProvider].
 *
 * Model metadata is declared rather than probed, so the numbers below track DeepSeek's
 * published limits and must be revised when they change.
 */
object DeepSeek {

    const val ID: String = "deepseek"
    const val BASE_URL: String = "https://api.deepseek.com/"

    const val CHAT: String = "deepseek-chat"
    const val REASONER: String = "deepseek-reasoner"

    val MODELS: List<ModelInfo> = listOf(
        ModelInfo(
            id = CHAT,
            contextWindow = 65_536,
            maxOutputTokens = 8_192,
            capabilities = setOf(Capability.STREAMING, Capability.TOOLS, Capability.JSON_SCHEMA),
        ),
        ModelInfo(
            id = REASONER,
            contextWindow = 65_536,
            maxOutputTokens = 8_192,
            capabilities = setOf(Capability.STREAMING),
        ),
    )

    @JvmStatic
    @JvmOverloads
    fun provider(
        apiKey: String,
        httpClient: OkHttpClient = OkHttpClient(),
        baseUrl: String = BASE_URL,
    ): Provider = OpenAiCompatibleProvider(
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = MODELS,
        providerId = ID,
        httpClient = httpClient,
    )
}
