package relay.llm.interceptor

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.RelayLlmException
import relay.llm.model.ChatChunk
import relay.llm.model.ChatResponse

/**
 * Retries transient failures with exponential backoff and jitter.
 *
 * Only [RelayLlmException.retryable] failures are retried, so auth and malformed-request
 * errors fail fast instead of burning the budget. A `Retry-After` hint from the provider
 * wins over the computed backoff.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val initialBackoffMillis: Long = 500,
    private val maxBackoffMillis: Long = 8_000,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) : Interceptor {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    override suspend fun intercept(chain: Chain): ChatResponse {
        var attempt = 1
        while (true) {
            try {
                return chain.proceed(chain.request)
            } catch (e: RelayLlmException) {
                if (attempt >= maxAttempts || !e.retryable) throw e
                delay(backoffMillis(attempt, e))
                attempt++
            }
        }
    }

    override fun interceptStream(chain: StreamChain): Flow<ChatChunk> = flow {
        var attempt = 1
        while (true) {
            var emittedAnything = false
            try {
                chain.proceed(chain.request).collect { chunk ->
                    emittedAnything = true
                    emit(chunk)
                }
                return@flow
            } catch (e: RelayLlmException) {
                // Restarting mid-stream would replay tokens the collector already saw,
                // so a partially delivered stream is not retryable.
                if (emittedAnything || attempt >= maxAttempts || !e.retryable) throw e
                delay(backoffMillis(attempt, e))
                attempt++
            }
        }
    }

    internal fun backoffMillis(attempt: Int, error: RelayLlmException): Long {
        (error as? RelayLlmException.RateLimited)?.retryAfterMillis?.let {
            return min(it, maxBackoffMillis)
        }
        val exponential = min(initialBackoffMillis shl (attempt - 1), maxBackoffMillis)
        val jitter = (exponential * jitterRatio * random.nextDouble()).toLong()
        return exponential + jitter
    }
}
