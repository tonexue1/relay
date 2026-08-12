package relay.llm.provider.openai

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import relay.llm.RelayLlmException
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.model.ToolCall
import relay.llm.model.ToolCallDelta
import relay.llm.model.ToolDef
import relay.llm.model.Usage

internal fun ChatRequest.toRequestDto(stream: Boolean): ChatCompletionRequestDto =
    ChatCompletionRequestDto(
        model = model,
        messages = messages.map { it.toDto() },
        tools = tools.takeIf { it.isNotEmpty() }?.map { it.toDto() },
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stop = stop.takeIf { it.isNotEmpty() },
        stream = if (stream) true else null,
        streamOptions = if (stream) StreamOptionsDto(includeUsage = true) else null,
        // `stream = false` is left null so unary bodies stay byte-identical to a plain OpenAI call.
    )

/** Serialises the request and layers [ChatRequest.extra] on top, letting callers override any field. */
internal fun ChatRequest.toRequestJson(json: Json, stream: Boolean): JsonObject {
    val base = json.encodeToJsonElement(toRequestDto(stream)).jsonObject
    return if (extra.isEmpty()) base else JsonObject(base + extra)
}

internal fun Message.toDto(): MessageDto = MessageDto(
    role = role.wireName,
    content = content,
    toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
        ToolCallDto(
            id = call.id,
            type = "function",
            function = FunctionCallDto(name = call.name, arguments = call.argumentsJson),
        )
    },
    toolCallId = toolCallId,
)

internal fun ToolDef.toDto(): ToolDto = ToolDto(
    type = "function",
    function = FunctionDefDto(name = name, description = description, parameters = parameters),
)

internal fun MessageDto.toMessage(): Message = Message(
    role = role.toRole(),
    content = content,
    toolCalls = toolCalls.orEmpty().mapNotNull { it.toToolCall() },
    toolCallId = toolCallId,
)

internal fun ToolCallDto.toToolCall(): ToolCall? {
    val fn = function ?: return null
    val fnName = fn.name ?: return null
    return ToolCall(id = id.orEmpty(), name = fnName, argumentsJson = fn.arguments ?: "{}")
}

internal fun ToolCallDto.toToolCallDelta(fallbackIndex: Int): ToolCallDelta = ToolCallDelta(
    index = index ?: fallbackIndex,
    id = id,
    name = function?.name,
    argumentsDelta = function?.arguments,
)

internal fun UsageDto.toUsage(): Usage = Usage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = if (totalTokens != 0) totalTokens else promptTokens + completionTokens,
)

private val Role.wireName: String get() = name.lowercase()

internal fun String?.toRole(): Role = when (this) {
    "system" -> Role.SYSTEM
    "user" -> Role.USER
    "tool" -> Role.TOOL
    else -> Role.ASSISTANT
}

internal fun String?.toFinishReason(): FinishReason = when (this) {
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.LENGTH
    "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
    "content_filter" -> FinishReason.CONTENT_FILTER
    null -> FinishReason.UNKNOWN
    else -> FinishReason.UNKNOWN
}

/** Collapses vendor status codes onto relay-llm's failure model so callers never branch on HTTP. */
internal fun mapHttpFailure(
    providerId: String,
    statusCode: Int,
    errorBody: String?,
    retryAfterHeader: String?,
    json: Json,
): RelayLlmException {
    val detail = extractErrorMessage(errorBody, json)
    val message = "HTTP $statusCode from $providerId" + if (detail != null) ": $detail" else ""
    return when {
        statusCode == 401 || statusCode == 403 ->
            RelayLlmException.Auth(message, statusCode, providerId)

        statusCode == 429 ->
            RelayLlmException.RateLimited(message, parseRetryAfterMillis(retryAfterHeader), providerId)

        statusCode in 400..499 ->
            RelayLlmException.InvalidRequest(message, statusCode, providerId)

        statusCode in 500..599 ->
            RelayLlmException.Server(message, statusCode, providerId)

        else -> RelayLlmException.Unknown(message, providerId)
    }
}

private fun extractErrorMessage(errorBody: String?, json: Json): String? {
    val raw = errorBody?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { json.decodeFromString<ErrorEnvelopeDto>(raw).error?.message }.getOrNull()
    return parsed?.takeIf { it.isNotBlank() } ?: raw.take(MAX_ERROR_BODY_CHARS)
}

/** `Retry-After` is seconds here; the HTTP-date form is rare for LLM APIs and is ignored. */
private fun parseRetryAfterMillis(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)

internal fun Throwable.toRelayLlmException(providerId: String): RelayLlmException = when (this) {
    is RelayLlmException -> this
    is SocketTimeoutException -> RelayLlmException.Timeout(timeoutMessage(providerId), providerId, this)
    // OkHttp signals an exceeded callTimeout with a plain InterruptedIOException.
    is InterruptedIOException -> RelayLlmException.Timeout(timeoutMessage(providerId), providerId, this)
    is SerializationException ->
        RelayLlmException.InvalidRequest("Malformed response from $providerId: $message", null, providerId, this)
    is IOException -> RelayLlmException.Network("Network failure calling $providerId: $message", providerId, this)
    else -> RelayLlmException.Unknown("Unexpected failure calling $providerId: $message", providerId, this)
}

private fun Throwable.timeoutMessage(providerId: String) = "Timed out calling $providerId: $message"

private const val MAX_ERROR_BODY_CHARS = 500
