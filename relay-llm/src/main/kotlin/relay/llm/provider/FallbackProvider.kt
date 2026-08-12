package relay.llm.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.ProviderInfo

/**
 * Falls through to [secondary] when [primary] fails.
 *
 * This is deliberately the *only* composition helper relay-llm ships. Real routing --
 * "small prompts on-device, hard ones to the cloud" -- depends on product policy and
 * belongs to the orchestration layer, not here. Fallback is different: it encodes
 * availability, not policy, and is identical everywhere.
 *
 * @param shouldFallback decides which failures are worth a second attempt. The default
 *   skips auth and malformed-request errors, which will fail on the secondary too.
 */
class FallbackProvider(
    private val primary: Provider,
    private val secondary: Provider,
    private val shouldFallback: (RelayLlmException) -> Boolean = ::isWorthFallingBack,
) : Provider {

    override val info: ProviderInfo = ProviderInfo(
        id = "fallback(${primary.info.id}->${secondary.info.id})",
        models = (primary.info.models + secondary.info.models).distinctBy { it.id },
    )

    override suspend fun chat(request: ChatRequest): ChatResponse =
        try {
            primary.chat(request)
        } catch (e: RelayLlmException) {
            if (!shouldFallback(e)) throw e
            secondary.chat(request)
        }

    override fun stream(request: ChatRequest): Flow<ChatChunk> = flow {
        var emittedAnything = false
        try {
            primary.stream(request).collect { emittedAnything = true; emit(it) }
        } catch (e: RelayLlmException) {
            // Switching backends after partial output would splice two different answers.
            if (emittedAnything || !shouldFallback(e)) throw e
            secondary.stream(request).collect { emit(it) }
        }
    }
}

private fun isWorthFallingBack(error: RelayLlmException): Boolean = when (error) {
    is RelayLlmException.Auth, is RelayLlmException.InvalidRequest -> false
    else -> true
}
