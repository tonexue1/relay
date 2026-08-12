package relay.llm

import kotlinx.coroutines.flow.Flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.ProviderInfo

/**
 * A model backend.
 *
 * A `Provider` represents one place inference can happen -- a cloud endpoint or an
 * on-device engine -- and nothing else. Cross-cutting behaviour (caching, retry, rate
 * limiting, metrics) belongs in `relay.llm.interceptor.Interceptor`, and choosing
 * between providers is the caller's policy, not this layer's.
 *
 * Cancellation is coroutine-native: cancelling the calling coroutine aborts [chat], and
 * stopping collection of [stream] aborts the underlying request.
 */
interface Provider {
    val info: ProviderInfo

    suspend fun chat(request: ChatRequest): ChatResponse

    fun stream(request: ChatRequest): Flow<ChatChunk>
}
