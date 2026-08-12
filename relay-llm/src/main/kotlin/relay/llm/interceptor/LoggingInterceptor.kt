package relay.llm.interceptor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse

/** Where [LoggingInterceptor] writes. Lets a UI tail the same lines that go to stdout. */
fun interface LlmLogger {
    fun log(line: String)

    companion object {
        val Stdout: LlmLogger = LlmLogger(::println)
    }
}

/**
 * Traces calls with timings.
 *
 * Prompts and completions are omitted unless [logContent] is set: log sinks outlive
 * requests and prompts routinely carry user data.
 */
class LoggingInterceptor(
    private val logger: LlmLogger = LlmLogger.Stdout,
    private val logContent: Boolean = false,
) : Interceptor {

    override suspend fun intercept(chain: Chain): ChatResponse {
        val request = chain.request
        val providerId = chain.providerInfo.id
        val startedAt = System.nanoTime()

        logger.log("--> [$providerId] chat model=${request.model} messages=${request.messages.size}${contentOf(request)}")
        try {
            val response = chain.proceed(request)
            val tokens = response.usage?.let { "${it.promptTokens}+${it.completionTokens}" } ?: "n/a"
            logger.log(
                "<-- [$providerId] chat ${elapsedMillis(startedAt)}ms " +
                    "finish=${response.finishReason} tokens=$tokens" +
                    if (logContent) " content=${response.message.content.orEmpty().ellipsize()}" else "",
            )
            return response
        } catch (e: Throwable) {
            logger.log("<-- [$providerId] chat ${elapsedMillis(startedAt)}ms FAILED ${e.describe()}")
            throw e
        }
    }

    override fun interceptStream(chain: StreamChain): Flow<ChatChunk> = flow {
        val request = chain.request
        val providerId = chain.providerInfo.id
        val startedAt = System.nanoTime()
        var firstChunkMillis: Long? = null
        var characters = 0

        logger.log("--> [$providerId] stream model=${request.model} messages=${request.messages.size}${contentOf(request)}")
        try {
            chain.proceed(request).collect { chunk ->
                if (chunk is ChatChunk.Text) {
                    if (firstChunkMillis == null) firstChunkMillis = elapsedMillis(startedAt)
                    characters += chunk.delta.length
                }
                emit(chunk)
            }
            logger.log(
                "<-- [$providerId] stream ${elapsedMillis(startedAt)}ms " +
                    "ttft=${firstChunkMillis ?: "n/a"}ms chars=$characters",
            )
        } catch (e: Throwable) {
            logger.log("<-- [$providerId] stream ${elapsedMillis(startedAt)}ms FAILED ${e.describe()}")
            throw e
        }
    }

    private fun contentOf(request: ChatRequest): String =
        if (!logContent) "" else " prompt=" + request.messages.lastOrNull()?.content.orEmpty().ellipsize()

    private fun String.ellipsize(): String =
        if (length <= MAX_CONTENT_CHARS) this else take(MAX_CONTENT_CHARS) + "..."

    private fun Throwable.describe(): String = "${this::class.simpleName}: $message"

    private companion object {
        const val MAX_CONTENT_CHARS = 200
    }
}

internal fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000
