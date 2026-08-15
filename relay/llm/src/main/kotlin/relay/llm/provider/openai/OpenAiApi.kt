package relay.llm.provider.openai

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The body is a raw [JsonObject] rather than a typed DTO so that
 * [relay.llm.model.ChatRequest.extra] can be merged in verbatim.
 *
 * Returning [Response] instead of the bare DTO keeps the error body and response headers
 * (notably `Retry-After`) reachable for error mapping.
 */
internal interface OpenAiApi {

    @POST("chat/completions")
    suspend fun chatCompletions(@Body body: JsonObject): Response<ChatCompletionResponseDto>
}
