package relay.llm.interceptor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Usage

/**
 * One observation per call.
 *
 * [timeToFirstTokenMillis] is only meaningful for streams and is what users actually
 * perceive as latency; [durationMillis] covers the whole call.
 */
data class CallMetrics(
    val providerId: String,
    val model: String,
    val streaming: Boolean,
    val durationMillis: Long,
    val timeToFirstTokenMillis: Long? = null,
    val usage: Usage? = null,
    val finishReason: FinishReason? = null,
    val error: Throwable? = null,
) {
    val succeeded: Boolean get() = error == null
}

fun interface MetricsSink {
    fun record(metrics: CallMetrics)
}

/**
 * Reports latency, token usage and outcome to a [MetricsSink].
 *
 * Kept separate from [LoggingInterceptor] because metrics feed dashboards and routing
 * decisions, while logs feed humans.
 */
class MetricsInterceptor(private val sink: MetricsSink) : Interceptor {

    override suspend fun intercept(chain: Chain): ChatResponse {
        val startedAt = System.nanoTime()
        try {
            val response = chain.proceed(chain.request)
            sink.record(
                CallMetrics(
                    providerId = chain.providerInfo.id,
                    model = chain.request.model,
                    streaming = false,
                    durationMillis = elapsedMillis(startedAt),
                    usage = response.usage,
                    finishReason = response.finishReason,
                ),
            )
            return response
        } catch (e: Throwable) {
            sink.record(
                CallMetrics(
                    providerId = chain.providerInfo.id,
                    model = chain.request.model,
                    streaming = false,
                    durationMillis = elapsedMillis(startedAt),
                    error = e,
                ),
            )
            throw e
        }
    }

    override fun interceptStream(chain: StreamChain): Flow<ChatChunk> = flow {
        val startedAt = System.nanoTime()
        var firstChunkMillis: Long? = null
        var usage: Usage? = null
        var finishReason: FinishReason? = null

        try {
            chain.proceed(chain.request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> if (firstChunkMillis == null) firstChunkMillis = elapsedMillis(startedAt)
                    is ChatChunk.Done -> {
                        usage = chunk.usage
                        finishReason = chunk.finishReason
                    }
                    is ChatChunk.ToolCalls -> Unit
                }
                emit(chunk)
            }
            sink.record(
                CallMetrics(
                    providerId = chain.providerInfo.id,
                    model = chain.request.model,
                    streaming = true,
                    durationMillis = elapsedMillis(startedAt),
                    timeToFirstTokenMillis = firstChunkMillis,
                    usage = usage,
                    finishReason = finishReason,
                ),
            )
        } catch (e: Throwable) {
            sink.record(
                CallMetrics(
                    providerId = chain.providerInfo.id,
                    model = chain.request.model,
                    streaming = true,
                    durationMillis = elapsedMillis(startedAt),
                    timeToFirstTokenMillis = firstChunkMillis,
                    error = e,
                ),
            )
            throw e
        }
    }
}
