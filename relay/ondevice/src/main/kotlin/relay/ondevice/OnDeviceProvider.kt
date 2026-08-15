package relay.ondevice

import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import relay.llm.Provider
import relay.llm.RelayLlmException
import relay.llm.foldToResponse
import relay.llm.model.Capability
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Usage
import relay.ondevice.cpu.CpuPlan
import relay.ondevice.cpu.CpuTopology
import relay.ondevice.engine.GenerateResult
import relay.ondevice.engine.GenerateTimings
import relay.ondevice.engine.LlamaEngine
import relay.ondevice.model.ModelSpec
import relay.ondevice.model.OnDeviceModels

/**
 * End-side [Provider] backed by a local llama.cpp engine.
 *
 * Call [load] with a verified GGUF path before chat/stream. Tools are rejected --
 * the current Instruct checkpoints do not advertise TOOLS.
 *
 * Streaming: native generation runs on a dedicated thread and pushes text pieces into a
 * queue drained on [Dispatchers.IO]. Cancelling collection calls [LlamaEngine.cancel].
 *
 * [load] / [unload] are blocking JNI calls -- never invoke them on the main thread.
 */
class OnDeviceProvider(
    private val engine: LlamaEngine,
    private val modelSpec: ModelSpec = OnDeviceModels.default,
) : Provider {

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        models = listOf(
            ModelInfo(
                id = modelSpec.id,
                contextWindow = modelSpec.contextWindow,
                maxOutputTokens = modelSpec.maxOutputTokens,
                capabilities = setOf(Capability.STREAMING),
            ),
        ),
    )

    fun load(modelPath: String, nCtx: Int = 4096, cpu: CpuPlan = CpuTopology.plan()) {
        engine.load(modelPath, nCtx = nCtx, cpu = cpu)
    }

    fun unload() {
        engine.unload()
    }

    val isLoaded: Boolean get() = engine.isLoaded

    override suspend fun chat(request: ChatRequest): ChatResponse =
        stream(request).foldToResponse().copy(model = request.model, providerId = PROVIDER_ID)

    override fun stream(request: ChatRequest): Flow<ChatChunk> = flow {
        validate(request)
        if (!engine.isLoaded) {
            throw RelayLlmException.InvalidRequest(
                message = "On-device model is not loaded",
                providerId = PROVIDER_ID,
            )
        }

        val prompt = try {
            engine.formatChat(request.messages)
        } catch (e: RuntimeException) {
            throw RelayLlmException.InvalidRequest(
                message = e.message ?: "Invalid messages",
                providerId = PROVIDER_ID,
                cause = e,
            )
        }

        val maxTokens = request.maxTokens ?: modelSpec.maxOutputTokens
        val temperature = (request.temperature ?: 0.7).toFloat()
        val topP = (request.topP ?: 0.9).toFloat()

        val queue = LinkedBlockingQueue<Any>()
        val doneSentinel = Any()

        val producer = Thread({
            try {
                val result = engine.generate(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    topP = topP,
                    onToken = { piece -> queue.put(TokenPiece(piece)) },
                )
                queue.put(result)
            } catch (t: Throwable) {
                queue.put(t)
            } finally {
                queue.put(doneSentinel)
            }
        }, "relay-ondevice-generate").apply {
            isDaemon = true
            start()
        }

        try {
            var finished: GenerateResult? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val item = queue.take()
                when {
                    item === doneSentinel -> break
                    item is TokenPiece -> emit(ChatChunk.Text(item.text))
                    item is GenerateResult -> finished = item
                    item is Throwable -> throw item
                }
            }
            producer.join()

            when (val result = finished) {
                null -> throw RelayLlmException.Unknown(
                    message = "Native generate returned no result",
                    providerId = PROVIDER_ID,
                )
                is GenerateResult.Cancelled -> throw CancellationException("on-device generate cancelled")
                is GenerateResult.Failed -> throw RelayLlmException.Unknown(
                    message = result.message,
                    providerId = PROVIDER_ID,
                )
                is GenerateResult.Ok -> {
                    val usage = Usage(
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        totalTokens = result.promptTokens + result.completionTokens,
                    )
                    val finish = if (result.completionTokens >= maxTokens) {
                        FinishReason.LENGTH
                    } else {
                        FinishReason.STOP
                    }
                    emit(
                        ChatChunk.Done(
                            usage = usage,
                            finishReason = finish,
                            extra = result.timings.toExtra(),
                        ),
                    )
                }
            }
        } finally {
            // Only abort native work when the collector was cancelled; a normal
            // completion must not flip the cancel flag under a still-running join.
            if (!currentCoroutineContext().isActive) {
                engine.cancel()
            }
            producer.join(2_000)
        }
    }.flowOn(Dispatchers.IO)

    private fun validate(request: ChatRequest) {
        if (request.tools.isNotEmpty()) {
            throw RelayLlmException.InvalidRequest(
                message = "On-device provider does not support tools",
                providerId = PROVIDER_ID,
            )
        }
        if (request.model.isNotBlank() && request.model != modelSpec.id) {
            throw RelayLlmException.InvalidRequest(
                message = "Unknown on-device model '${request.model}' (expected ${modelSpec.id})",
                providerId = PROVIDER_ID,
            )
        }
    }

    private data class TokenPiece(val text: String)

    private fun GenerateTimings?.toExtra(): Map<String, String> {
        if (this == null) return emptyMap()
        return mapOf(
            EXTRA_PREFILL_MS to prefillMs.toString(),
            EXTRA_TTFT_MS to ttftMs.toString(),
            EXTRA_DECODE_MS to decodeMs.toString(),
        )
    }

    companion object {
        const val PROVIDER_ID = "ondevice-qwen"
        const val EXTRA_PREFILL_MS = "prefillMs"
        const val EXTRA_TTFT_MS = "ttftMs"
        const val EXTRA_DECODE_MS = "decodeMs"
    }
}
