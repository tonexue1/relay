package relay.ondevice.model

/**
 * Describes a downloadable on-device GGUF.
 *
 * [Qwen25_3B] is the default. Smaller checkpoints stay listed so a device that
 * already has them can still load them by id.
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

    val Qwen25_3B = ModelSpec(
        id = "qwen2.5-3b-instruct",
        displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
        fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        sha256 = "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
        expectedBytes = 1_929_903_264L,
        contextWindow = 32_768,
        maxOutputTokens = 2_048,
    )

    val default: ModelSpec = Qwen25_3B
}
