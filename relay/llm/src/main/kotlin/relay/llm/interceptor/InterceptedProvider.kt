package relay.llm.interceptor

import kotlinx.coroutines.flow.Flow
import relay.llm.Provider
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.ProviderInfo

/**
 * Wraps a [Provider] with an interceptor chain and is itself a [Provider], so callers
 * upstream cannot tell whether they hold a bare backend or a whole pipeline.
 *
 * Interceptors run in list order; the delegate is the terminal node.
 */
class InterceptedProvider(
    private val delegate: Provider,
    private val interceptors: List<Interceptor>,
) : Provider {

    override val info: ProviderInfo get() = delegate.info

    override suspend fun chat(request: ChatRequest): ChatResponse =
        RealChain(delegate, interceptors, 0, request).proceed(request)

    override fun stream(request: ChatRequest): Flow<ChatChunk> =
        RealStreamChain(delegate, interceptors, 0, request).proceed(request)
}

/** Adds [interceptors] on top of this provider, outermost first. */
fun Provider.intercept(vararg interceptors: Interceptor): Provider =
    intercept(interceptors.toList())

fun Provider.intercept(interceptors: List<Interceptor>): Provider =
    if (interceptors.isEmpty()) this else InterceptedProvider(this, interceptors)

private class RealChain(
    private val delegate: Provider,
    private val interceptors: List<Interceptor>,
    private val index: Int,
    override val request: ChatRequest,
) : Chain {

    override val providerInfo: ProviderInfo get() = delegate.info

    override suspend fun proceed(request: ChatRequest): ChatResponse {
        if (index >= interceptors.size) return delegate.chat(request)
        val next = RealChain(delegate, interceptors, index + 1, request)
        return interceptors[index].intercept(next)
    }
}

private class RealStreamChain(
    private val delegate: Provider,
    private val interceptors: List<Interceptor>,
    private val index: Int,
    override val request: ChatRequest,
) : StreamChain {

    override val providerInfo: ProviderInfo get() = delegate.info

    override fun proceed(request: ChatRequest): Flow<ChatChunk> {
        if (index >= interceptors.size) return delegate.stream(request)
        val next = RealStreamChain(delegate, interceptors, index + 1, request)
        return interceptors[index].interceptStream(next)
    }
}
