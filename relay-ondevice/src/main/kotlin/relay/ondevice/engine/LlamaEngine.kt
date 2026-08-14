package relay.ondevice.engine

import relay.ondevice.cpu.CpuPlan
import relay.ondevice.cpu.CpuTopology

/**
 * Thin native inference surface. Implementations may be JNI-backed or fakes for tests.
 *
 * [generate] is blocking and invokes [onToken] for each decoded text piece. Callers that
 * need cancellation should call [cancel] from another thread; a cancelled generate returns
 * [GenerateResult.Cancelled].
 */
interface LlamaEngine {
    val isLoaded: Boolean

    fun load(modelPath: String, nCtx: Int = 4096, cpu: CpuPlan = CpuTopology.plan())

    fun unload()

    fun cancel()

    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        onToken: (String) -> Unit,
    ): GenerateResult
}

/** Wall-clock slices measured inside the native generate loop. */
data class GenerateTimings(
    val prefillMs: Long,
    val ttftMs: Long,
    val decodeMs: Long,
)

sealed class GenerateResult {
    data class Ok(
        val promptTokens: Int,
        val completionTokens: Int,
        val timings: GenerateTimings? = null,
    ) : GenerateResult()

    data object Cancelled : GenerateResult()

    data class Failed(val code: Int, val message: String) : GenerateResult()
}
