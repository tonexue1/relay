package relay.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.foldToResponse
import relay.llm.model.Capability
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.ToolCall
import relay.llm.model.ToolCallDelta
import relay.llm.model.Usage

/** In-memory provider that plays a scripted sequence of streams, one per LLM turn. */
class ScriptedProvider(
    private val scripts: List<(ChatRequest) -> Flow<ChatChunk>>,
    override val info: ProviderInfo = toolsInfo(),
) : Provider {

    val receivedRequests = mutableListOf<ChatRequest>()

    @Volatile
    private var index = 0

    override suspend fun chat(request: ChatRequest): ChatResponse =
        stream(request).foldToResponse()

    override fun stream(request: ChatRequest): Flow<ChatChunk> {
        receivedRequests += request
        val i = index++
        check(i < scripts.size) { "ScriptedProvider has no script for turn $i" }
        return scripts[i](request)
    }

    companion object {
        fun toolsInfo(
            model: String = "fake-model",
            contextWindow: Int = 4_096,
            capabilities: Set<Capability> = setOf(Capability.STREAMING, Capability.TOOLS),
        ): ProviderInfo = ProviderInfo(
            id = "scripted",
            models = listOf(ModelInfo(model, contextWindow, capabilities = capabilities)),
        )

        fun text(text: String, finish: FinishReason = FinishReason.STOP): Flow<ChatChunk> = flow {
            if (text.isNotEmpty()) emit(ChatChunk.Text(text))
            emit(ChatChunk.Done(Usage(3, text.length, 3 + text.length), finish))
        }

        fun tools(vararg calls: ToolCall): Flow<ChatChunk> = flow {
            calls.forEachIndexed { i, call ->
                emit(
                    ChatChunk.ToolCalls(
                        ToolCallDelta(
                            index = i,
                            id = call.id,
                            name = call.name,
                            argumentsDelta = call.argumentsJson,
                        ),
                    ),
                )
            }
            emit(ChatChunk.Done(Usage(8, 4, 12), FinishReason.TOOL_CALLS))
        }
    }
}
