package relay.llm.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import relay.llm.Provider
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Role
import relay.llm.model.Usage
import relay.llm.provider.openai.ChatCompletionResponseDto
import relay.llm.provider.openai.OpenAiApi
import relay.llm.provider.openai.mapHttpFailure
import relay.llm.provider.openai.toFinishReason
import relay.llm.provider.openai.toMessage
import relay.llm.provider.openai.toRelayLlmException
import relay.llm.provider.openai.toRequestJson
import relay.llm.provider.openai.toToolCallDelta
import relay.llm.provider.openai.toUsage
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Talks to any endpoint that speaks the OpenAI `/chat/completions` dialect: DeepSeek,
 * Moonshot, Qwen, vLLM, Ollama and friends. Vendor differences are expressed as
 * constructor arguments, not subclasses.
 *
 * The caller owns [httpClient]; this provider only derives from it (`newBuilder()`), so
 * the caller's connection pool, proxy and OkHttp interceptors are preserved and never
 * mutated.
 *
 * @param models what this endpoint serves. Nothing is probed at runtime -- the caller
 *   declares it, which keeps construction free of network I/O.
 */
class OpenAiCompatibleProvider(
    baseUrl: String,
    apiKey: String,
    models: List<ModelInfo>,
    providerId: String = "openai-compatible",
    httpClient: OkHttpClient = OkHttpClient(),
    defaultHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val info: ProviderInfo = ProviderInfo(providerId, models)

    private val providerId: String get() = info.id

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val authedClient: OkHttpClient = httpClient.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            defaultHeaders.forEach { (name, value) -> builder.header(name, value) }
            chain.proceed(builder.build())
        }
        .build()

    private val api: OpenAiApi = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(authedClient)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
        .build()
        .create(OpenAiApi::class.java)

    /**
     * A stream is idle between tokens, so the shared read timeout would abort it. Idle
     * bounds for streaming come from [ChatRequest.timeoutMillis] instead, applied per call.
     */
    private val sseClient: OkHttpClient = authedClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val streamUrl = normalizedBaseUrl + CHAT_COMPLETIONS_PATH

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val body = request.toRequestJson(json, stream = false)

        val response = try {
            withOptionalTimeout(request.timeoutMillis) { api.chatCompletions(body) }
        } catch (e: TimeoutCancellationException) {
            throw RelayLlmException.Timeout(
                "Timed out after ${request.timeoutMillis}ms calling $providerId",
                providerId,
                e,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.toRelayLlmException(providerId)
        }

        if (!response.isSuccessful) {
            throw mapHttpFailure(
                providerId = providerId,
                statusCode = response.code(),
                errorBody = runCatching { response.errorBody()?.string() }.getOrNull(),
                retryAfterHeader = response.headers()[RETRY_AFTER_HEADER],
                json = json,
            )
        }

        val dto = response.body()
            ?: throw RelayLlmException.InvalidRequest("Empty response body from $providerId", null, providerId)
        val choice = dto.choices.firstOrNull()
            ?: throw RelayLlmException.InvalidRequest("No choices in response from $providerId", null, providerId)

        return ChatResponse(
            message = choice.message?.toMessage() ?: Message(Role.ASSISTANT, content = ""),
            usage = dto.usage?.toUsage(),
            finishReason = choice.finishReason.toFinishReason(),
            model = dto.model,
            providerId = providerId,
        )
    }

    override fun stream(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val payload = request.toRequestJson(json, stream = true).toString()
        val httpRequest = Request.Builder()
            .url(streamUrl)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .build()

        // The usage-only chunk and finish_reason arrive before [DONE], so both are held
        // until the terminal Done chunk can be emitted.
        var usage: Usage? = null
        var finishReason = FinishReason.STOP
        var toolCallSeq = 0

        val listener = object : EventSourceListener() {

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == SSE_DONE) {
                    trySendBlocking(ChatChunk.Done(usage, finishReason))
                    close()
                    return
                }

                val chunk = runCatching {
                    json.decodeFromString<ChatCompletionResponseDto>(data)
                }.getOrElse { return }

                chunk.usage?.let { usage = it.toUsage() }

                val choice = chunk.choices.firstOrNull() ?: return
                choice.finishReason?.let { finishReason = it.toFinishReason() }

                val delta = choice.delta ?: return
                delta.content?.takeIf { it.isNotEmpty() }?.let {
                    trySendBlocking(ChatChunk.Text(it))
                }
                delta.toolCalls?.forEach { toolCall ->
                    trySendBlocking(ChatChunk.ToolCalls(toolCall.toToolCallDelta(toolCallSeq++)))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(streamFailure(t, response))
            }

            /** Reached when the server ends the stream without sending `[DONE]`. */
            override fun onClosed(eventSource: EventSource) {
                trySendBlocking(ChatChunk.Done(usage, finishReason))
                close()
            }
        }

        val client = request.timeoutMillis
            ?.takeIf { it > 0 }
            ?.let { sseClient.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() }
            ?: sseClient

        val eventSource = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun streamFailure(t: Throwable?, response: Response?): RelayLlmException {
        if (response != null && !response.isSuccessful) {
            return mapHttpFailure(
                providerId = providerId,
                statusCode = response.code,
                errorBody = runCatching { response.body.string() }.getOrNull(),
                retryAfterHeader = response.header(RETRY_AFTER_HEADER),
                json = json,
            )
        }
        return t?.toRelayLlmException(providerId)
            ?: RelayLlmException.Network("Stream from $providerId closed unexpectedly", providerId)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CHAT_COMPLETIONS_PATH = "chat/completions"
        const val SSE_DONE = "[DONE]"
        const val RETRY_AFTER_HEADER = "Retry-After"
    }
}

private suspend fun <T> withOptionalTimeout(timeoutMillis: Long?, block: suspend () -> T): T =
    if (timeoutMillis == null || timeoutMillis <= 0) block() else withTimeout(timeoutMillis) { block() }
