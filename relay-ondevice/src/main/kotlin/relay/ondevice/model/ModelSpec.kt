package relay.ondevice.model

/**
 * Describes a downloadable on-device GGUF.
 *
 * [Qwen25_05B] is the first-slice model. [Qwen25_15B] is reserved as a slot only --
 * download / load for 1.5B is intentionally not wired yet.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val expectedBytes: Long,
    val contextWindow: Int,
    val maxOutputTokens: Int,
)

object OnDeviceModels {
    val Qwen25_05B = ModelSpec(
        id = "qwen2.5-0.5b-instruct",
        displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
        fileName = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        sha256 = "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653",
        expectedBytes = 397_808_192L,
        contextWindow = 32_768,
        maxOutputTokens = 2_048,
    )

    /** Slot only -- not downloaded or loaded in this slice. */
    val Qwen25_15B = ModelSpec(
        id = "qwen2.5-1.5b-instruct",
        displayName = "Qwen2.5 1.5B Instruct (Q4_K_M)",
        fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        sha256 = "",
        expectedBytes = 0L,
        contextWindow = 32_768,
        maxOutputTokens = 2_048,
    )

    val default: ModelSpec = Qwen25_05B
}
