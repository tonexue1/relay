package relay.memory.extract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Usage

internal class RecordingProvider(
    private val reply: String,
) : Provider {
    override val info: ProviderInfo = ProviderInfo(
        id = "recording",
        models = listOf(ModelInfo("fake-model", contextWindow = 4_096)),
    )

    val chats = mutableListOf<ChatRequest>()
    val streams = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        chats += request
        return ChatResponse(
            message = Message.assistant(reply),
            usage = Usage(3, reply.length, 3 + reply.length),
            finishReason = FinishReason.STOP,
        )
    }

    override fun stream(request: ChatRequest): Flow<ChatChunk> {
        streams += request
        return flow {
            if (reply.isNotEmpty()) emit(ChatChunk.Text(reply))
            emit(ChatChunk.Done(Usage(3, reply.length, 3 + reply.length), FinishReason.STOP))
        }
    }
}
