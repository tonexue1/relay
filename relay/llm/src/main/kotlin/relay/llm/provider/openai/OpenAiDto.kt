package relay.llm.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire format of the OpenAI `/chat/completions` API, shared by every OpenAI-compatible
 * vendor (DeepSeek, Moonshot, Qwen, vLLM, Ollama, ...).
 *
 * These types stay internal: they are an adapter detail, not part of relay-llm's API.
 */
@Serializable
internal data class ChatCompletionRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val tools: List<ToolDto>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

/**
 * Asks the server to append a final usage-only chunk to a stream.
 *
 * No default value: the encoder omits defaults, which would ship an empty
 * `stream_options: {}` and silently lose usage reporting.
 */
@Serializable
internal data class StreamOptionsDto(
    @SerialName("include_usage") val includeUsage: Boolean,
)

@Serializable
internal data class MessageDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/**
 * [index] is only present on streamed deltas, where it groups fragments belonging to the
 * same tool call; [function] fields arrive piecewise in that mode.
 */
@Serializable
internal data class ToolCallDto(
    val id: String? = null,
    val index: Int? = null,
    val type: String? = null,
    val function: FunctionCallDto? = null,
)

@Serializable
internal data class FunctionCallDto(
    val name: String? = null,
    val arguments: String? = null,
)

/** [type] carries no default for the same reason as [StreamOptionsDto]; the API rejects a tool without it. */
@Serializable
internal data class ToolDto(
    val type: String,
    val function: FunctionDefDto,
)

@Serializable
internal data class FunctionDefDto(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject,
)

@Serializable
internal data class ChatCompletionResponseDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
internal data class ChoiceDto(
    val index: Int = 0,
    val message: MessageDto? = null,
    val delta: MessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
internal data class ErrorEnvelopeDto(
    val error: ErrorDto? = null,
)

@Serializable
internal data class ErrorDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
