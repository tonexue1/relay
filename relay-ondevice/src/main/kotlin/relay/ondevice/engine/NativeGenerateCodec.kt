package relay.ondevice.engine

/**
 * Maps the packed JNI return (token counts or a negative error code) plus an optional
 * `[prefillNs, ttftNs, decodeNs]` out-array into a [GenerateResult].
 */
internal object NativeGenerateCodec {

    fun unpack(packed: Int, timingsNs: LongArray?): GenerateResult = when {
        packed == -100 -> GenerateResult.Cancelled
        packed < 0 -> GenerateResult.Failed(packed, "nativeGenerate failed with code $packed")
        else -> GenerateResult.Ok(
            promptTokens = (packed ushr 16) and 0xFFFF,
            completionTokens = packed and 0xFFFF,
            timings = timingsOf(timingsNs),
        )
    }

    private fun timingsOf(timingsNs: LongArray?): GenerateTimings? {
        if (timingsNs == null || timingsNs.size < 3) return null
        return GenerateTimings(
            prefillMs = nanosToMillis(timingsNs[0]),
            ttftMs = nanosToMillis(timingsNs[1]),
            decodeMs = nanosToMillis(timingsNs[2]),
        )
    }

    private fun nanosToMillis(ns: Long): Long = (ns / 1_000_000L).coerceAtLeast(0)
}
