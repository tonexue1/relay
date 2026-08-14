package relay.ondevice.engine

import android.util.Log
import relay.llm.model.Message
import relay.llm.model.Role
import relay.ondevice.cpu.CpuPlan

/**
 * JNI-backed [LlamaEngine] wrapping `librelay_llama.so` (llama.cpp, arm64 CPU).
 *
 * Load the shared library once per process. Generation is serialized inside the native
 * layer; [cancel] is safe to call from another thread.
 *
 * Tokens are delivered as raw UTF-8 bytes from native code (avoids JNI NewStringUTF
 * rejecting incomplete multi-byte sequences mid-piece).
 */
class JniLlamaEngine : LlamaEngine {

    @Volatile
    private var loaded = false

    override val isLoaded: Boolean get() = loaded

    override fun load(modelPath: String, nCtx: Int, cpu: CpuPlan) {
        Log.i(TAG, "load: threads=${cpu.threadCount} cores=${cpu.coreIndices}")
        check(nativeLoad(modelPath, nCtx, cpu.threadCount, cpu.coreIndices.toIntArray())) {
            "Failed to load GGUF at $modelPath"
        }
        loaded = true
    }

    override fun unload() {
        nativeUnload()
        loaded = false
    }

    override fun cancel() {
        nativeCancel()
    }

    override fun formatChat(messages: List<Message>): String {
        check(loaded) { "Model is not loaded" }
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val roles = Array(messages.size) { i ->
            when (messages[i].role) {
                Role.SYSTEM -> "system"
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
                Role.TOOL -> error("on-device chat template does not support TOOL turns")
            }
        }
        val contents = Array(messages.size) { messages[it].content.orEmpty() }
        val bytes = nativeApplyChatTemplate(roles, contents)
            ?: error("Failed to apply the GGUF chat template")
        return String(bytes, Charsets.UTF_8)
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): GenerateResult {
        check(loaded) { "Model is not loaded" }
        val decoder = Utf8PieceDecoder()
        val timingsNs = LongArray(3)
        val packed = nativeGenerate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            callback = object : TokenCallback {
                override fun onToken(bytes: ByteArray) {
                    decoder.push(bytes)?.let(onToken)
                }
            },
            timingsNs = timingsNs,
        )
        decoder.finish()?.let(onToken)

        val result = NativeGenerateCodec.unpack(packed, timingsNs)
        when (result) {
            is GenerateResult.Failed -> Log.e(TAG, result.message)
            is GenerateResult.Ok -> result.timings?.let { t ->
                Log.i(
                    TAG,
                    "timings prefill=${t.prefillMs}ms ttft=${t.ttftMs}ms decode=${t.decodeMs}ms " +
                        "prompt=${result.promptTokens} completion=${result.completionTokens}",
                )
            }
            GenerateResult.Cancelled -> Unit
        }
        return result
    }

    /**
     * Must stay public for JNI method lookup (`onToken([B)V`).
     */
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray)
    }

    /**
     * [cpuIndices] pins one worker per listed CPU; an empty array leaves placement to
     * the scheduler.
     */
    private external fun nativeLoad(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        cpuIndices: IntArray,
    ): Boolean
    private external fun nativeUnload()
    private external fun nativeCancel()
    private external fun nativeApplyChatTemplate(roles: Array<String>, contents: Array<String>): ByteArray?
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: TokenCallback,
        timingsNs: LongArray,
    ): Int

    companion object {
        private const val TAG = "JniLlamaEngine"

        init {
            System.loadLibrary("relay_llama")
        }
    }
}

/**
 * Buffers incomplete UTF-8 sequences across token pieces so a multi-byte character
 * split across pieces is not decoded twice (once as replacement, once as garbage).
 */
internal class Utf8PieceDecoder {
    private val pending = ArrayList<Byte>(8)

    fun push(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        for (b in bytes) pending.add(b)
        return drain(flush = false)
    }

    fun finish(): String? = drain(flush = true)

    private fun drain(flush: Boolean): String? {
        if (pending.isEmpty()) return null
        val keep = if (flush) 0 else incompleteSuffixLength(pending)
        val emitCount = pending.size - keep
        if (emitCount <= 0) return null
        val out = ByteArray(emitCount) { pending[it] }
        repeat(emitCount) { pending.removeAt(0) }
        return String(out, Charsets.UTF_8)
    }

    /** How many trailing bytes form an incomplete UTF-8 sequence. */
    private fun incompleteSuffixLength(bytes: List<Byte>): Int {
        if (bytes.isEmpty()) return 0
        var i = bytes.lastIndex
        var cont = 0
        while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) {
            cont++
            i--
            if (cont > 3) return 0
        }
        if (i < 0) return bytes.size
        val lead = bytes[i].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return 0
        }
        val have = cont + 1
        return if (have < need) have else 0
    }
}
