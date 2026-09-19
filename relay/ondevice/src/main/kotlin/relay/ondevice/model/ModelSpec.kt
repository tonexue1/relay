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
        downloadUrl = "https://www.modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        expectedBytes = 491_400_032L,
        contextWindow = 32_768,
        maxOutputTokens = 2_048,
    )

    /** Slot only -- it lacks a verified artifact checksum and is not selectable yet. */
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
        downloadUrl = "https://www.modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
        sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        expectedBytes = 2_104_932_768L,
        contextWindow = 32_768,
        maxOutputTokens = 2_048,
    )

    val default: ModelSpec = Qwen25_3B

    /** Models whose download artifacts are fully specified and can be safely verified. */
    val selectable: List<ModelSpec> = listOf(Qwen25_05B, Qwen25_3B)

    fun selectableById(id: String): ModelSpec? = selectable.firstOrNull { it.id == id }

}
