package relay.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Usage

/** In-memory [Provider] so interceptor behaviour can be tested without a transport. */
class FakeProvider(
    override val info: ProviderInfo = ProviderInfo(
        id = "fake",
        models = listOf(ModelInfo("fake-model", contextWindow = 4_096)),
    ),
    private val onChat: suspend (ChatRequest) -> ChatResponse = { okResponse() },
    private val onStream: (ChatRequest) -> Flow<ChatChunk> = { okStream() },
) : Provider {

    val receivedRequests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        receivedRequests += request
        return onChat(request)
    }

    override fun stream(request: ChatRequest): Flow<ChatChunk> {
        receivedRequests += request
        return onStream(request)
    }

    companion object {
        fun okResponse(text: String = "ok"): ChatResponse = ChatResponse(
            message = Message.assistant(text),
            usage = Usage(promptTokens = 3, completionTokens = 1, totalTokens = 4),
            finishReason = FinishReason.STOP,
        )

        fun okStream(text: String = "ok"): Flow<ChatChunk> = flow {
            text.forEach { emit(ChatChunk.Text(it.toString())) }
            emit(ChatChunk.Done(Usage(3, text.length, 3 + text.length), FinishReason.STOP))
        }

        /** Fails [failures] times before succeeding, so retry budgets can be exercised. */
        fun failingTimes(failures: Int, error: () -> RelayLlmException): FakeProvider {
            var remaining = failures
            return FakeProvider(
                onChat = { if (remaining-- > 0) throw error() else okResponse() },
                onStream = {
                    flow {
                        if (remaining-- > 0) throw error()
                        emit(ChatChunk.Text("ok"))
                        emit(ChatChunk.Done())
                    }
                },
            )
        }
    }
}
