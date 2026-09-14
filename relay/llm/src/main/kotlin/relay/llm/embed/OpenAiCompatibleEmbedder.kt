package relay.llm.embed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import relay.llm.RelayLlmException
import relay.llm.provider.openai.mapHttpFailure
import relay.llm.provider.openai.toRelayLlmException

/**
 * POST `/embeddings` against any OpenAI-shaped host (OpenAI, SiliconFlow, Ollama, …).
 */
class OpenAiCompatibleEmbedder(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient = OkHttpClient(),
    private val providerId: String = "openai-embeddings",
) : TextEmbedder {
    override val modelId: String = model

    private val json = Json { ignoreUnknownKeys = true }
    private val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val url = root + "embeddings"
    private val client: OkHttpClient = httpClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            chain.proceed(builder.build())
        }
        .build()

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            EmbedRequest.serializer(),
            EmbedRequest(model = model, input = text),
        )
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.toRelayLlmException(providerId)
        }
        response.use { http ->
            val body = http.body?.string()
            if (!http.isSuccessful) {
                throw mapHttpFailure(
                    providerId = providerId,
                    statusCode = http.code,
                    errorBody = body,
                    retryAfterHeader = http.header("Retry-After"),
                    json = json,
                )
            }
            val parsed = try {
                json.decodeFromString(EmbedResponse.serializer(), body.orEmpty())
            } catch (e: Throwable) {
                throw RelayLlmException.InvalidRequest(
                    "Malformed embeddings response from $providerId",
                    http.code,
                    providerId,
                    e,
                )
            }
            val vector = parsed.data.firstOrNull()?.embedding
                ?: throw RelayLlmException.InvalidRequest(
                    "No embedding in response from $providerId",
                    http.code,
                    providerId,
                )
            if (vector.isEmpty()) {
                throw RelayLlmException.InvalidRequest(
                    "Empty embedding from $providerId",
                    http.code,
                    providerId,
                )
            }
            return@withContext FloatArray(vector.size) { vector[it].toFloat() }
        }
    }
}

@Serializable
private data class EmbedRequest(
    val model: String,
    val input: String,
)

@Serializable
private data class EmbedResponse(
    val data: List<EmbedData> = emptyList(),
)

@Serializable
private data class EmbedData(
    val embedding: List<Double> = emptyList(),
)

private val JSON = "application/json; charset=utf-8".toMediaType()
