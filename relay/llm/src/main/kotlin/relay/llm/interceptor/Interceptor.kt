package relay.llm.interceptor

import kotlinx.coroutines.flow.Flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.ProviderInfo

/**
 * Cross-cutting behaviour layered over a [relay.llm.Provider].
 *
 * Modelled after OkHttp's interceptor chain so that `Provider` keeps meaning "a model
 * backend" and nothing more. Both call shapes have pass-through defaults, so an
 * interceptor only overrides the one it cares about.
 */
interface Interceptor {

    suspend fun intercept(chain: Chain): ChatResponse = chain.proceed(chain.request)

    fun interceptStream(chain: StreamChain): Flow<ChatChunk> = chain.proceed(chain.request)
}

/** Unary leg of the chain. Call [proceed] exactly once to invoke the rest of the chain. */
interface Chain {
    val request: ChatRequest

    /** Describes the backend at the end of the chain, so interceptors can tag metrics or inspect limits. */
    val providerInfo: ProviderInfo

    suspend fun proceed(request: ChatRequest): ChatResponse
}

/** Streaming leg of the chain. [proceed] returns a cold flow; collect it to drive the call. */
interface StreamChain {
    val request: ChatRequest

    val providerInfo: ProviderInfo

    fun proceed(request: ChatRequest): Flow<ChatChunk>
}
