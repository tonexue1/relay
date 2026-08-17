package relay.werewolf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.foldToResponse
import relay.llm.model.Capability
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Usage

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
        fun toolsInfo(): ProviderInfo = ProviderInfo(
            id = "scripted",
            models = listOf(
                ModelInfo("fake-model", 4_096, capabilities = setOf(Capability.STREAMING, Capability.TOOLS)),
            ),
        )

        fun text(text: String): Flow<ChatChunk> = flow {
            if (text.isNotEmpty()) emit(ChatChunk.Text(text))
            emit(ChatChunk.Done(Usage(3, text.length, 3 + text.length), FinishReason.STOP))
        }
    }
}
