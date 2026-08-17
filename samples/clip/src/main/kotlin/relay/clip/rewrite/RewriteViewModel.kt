package relay.clip.rewrite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import relay.llm.RelayLlmException
import relay.llm.interceptor.CallMetrics
import relay.llm.interceptor.MetricsInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.ondevice.OnDeviceProvider
import relay.ondevice.cpu.CpuTopology
import relay.ondevice.engine.JniLlamaEngine
import relay.ondevice.model.ModelStore
import relay.ondevice.model.OnDeviceModels

data class RewriteUiState(
    val source: String = "",
    val modelReady: Boolean = false,
    val modelLoaded: Boolean = false,
    val loadingModel: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadLabel: String = "",
    val running: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val metrics: CallMetrics? = null,
    val ttftMs: Long? = null,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val latencyMs: Long? = null,
) {
    val canDownload: Boolean get() = !downloading && !modelReady
    val canLoad: Boolean get() = modelReady && !modelLoaded && !loadingModel && !running && !downloading
    val canRewrite: Boolean get() = modelLoaded && !running && !loadingModel && source.isNotBlank()
}

/** S1: on-device rewrite only. Same Provider stack as playground; 0.5B, no cloud. */
class RewriteViewModel(application: Application) : AndroidViewModel(application) {

    private val modelSpec = OnDeviceModels.Qwen25_05B
    val modelName: String = modelSpec.displayName

    private val store = ModelStore(
        rootDir = application.filesDir.resolve("models"),
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build(),
    )
    private val engine = JniLlamaEngine()
    private val provider = OnDeviceProvider(engine, modelSpec)

    private val _uiState = MutableStateFlow(RewriteUiState())
    val uiState: StateFlow<RewriteUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = store.isReady(modelSpec)
            _uiState.update { it.copy(modelReady = ready) }
        }
    }

    fun setSource(text: String) {
        if (_uiState.value.source == text) return
        _uiState.update { it.copy(source = text) }
    }

    fun download() {
        if (!_uiState.value.canDownload) return
        _uiState.update {
            it.copy(downloading = true, error = null, downloadProgress = 0f, downloadLabel = "开始下载…")
        }
        viewModelScope.launch {
            try {
                store.ensurePresent(modelSpec) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    _uiState.update {
                        it.copy(
                            downloadProgress = progress.coerceIn(0f, 1f),
                            downloadLabel = formatBytes(downloaded) + " / " + formatBytes(total),
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        downloading = false,
                        modelReady = true,
                        downloadProgress = 1f,
                        downloadLabel = "已就绪",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(downloading = false, error = e.message ?: "download failed") }
            }
        }
    }

    fun load() {
        if (!_uiState.value.canLoad) return
        _uiState.update { it.copy(loadingModel = true, error = null) }
        viewModelScope.launch {
            try {
                val path = store.localFile(modelSpec).absolutePath
                val cpu = CpuTopology.plan()
                withContext(Dispatchers.IO) {
                    provider.load(path, nCtx = 2048, cpu = cpu)
                }
                _uiState.update { it.copy(modelLoaded = true, loadingModel = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loadingModel = false, error = e.message ?: "load failed") }
            }
        }
    }

    fun rewrite() {
        val state = _uiState.value
        if (!state.canRewrite) return
        val request = ChatRequest(
            model = modelSpec.id,
            messages = listOf(
                Message.system("把用户给的句子改得更正式、更书面。只输出改写后的正文，不要解释，不要加引号。"),
                Message.user(state.source),
            ),
            temperature = 0.4,
            maxTokens = 128,
        )
        _uiState.update {
            it.copy(
                running = true,
                output = "",
                error = null,
                metrics = null,
                ttftMs = null,
                prefillMs = null,
                decodeMs = null,
                latencyMs = null,
            )
        }
        val instrumented = provider.intercept(
            MetricsInterceptor { metrics -> _uiState.update { it.copy(metrics = metrics) } },
        )
        inFlight = viewModelScope.launch {
            val started = System.nanoTime()
            var firstTokenAt: Long? = null
            try {
                instrumented.stream(request).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Text -> {
                            if (firstTokenAt == null) firstTokenAt = System.nanoTime()
                            _uiState.update { it.copy(output = it.output + chunk.delta) }
                        }
                        is ChatChunk.ToolCalls -> Unit
                        is ChatChunk.Done -> {
                            val prefill = chunk.extra[OnDeviceProvider.EXTRA_PREFILL_MS]?.toLongOrNull()
                            val nativeTtft = chunk.extra[OnDeviceProvider.EXTRA_TTFT_MS]?.toLongOrNull()
                            val decode = chunk.extra[OnDeviceProvider.EXTRA_DECODE_MS]?.toLongOrNull()
                            _uiState.update {
                                it.copy(
                                    prefillMs = prefill,
                                    ttftMs = nativeTtft ?: it.ttftMs,
                                    decodeMs = decode,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(error = "已取消") }
                throw e
            } catch (e: RelayLlmException) {
                _uiState.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "inference failed") }
            } finally {
                val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val wallTtft = firstTokenAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) }
                _uiState.update {
                    it.copy(
                        running = false,
                        latencyMs = it.latencyMs ?: totalMs,
                        ttftMs = it.ttftMs ?: wallTtft,
                    )
                }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
        engine.cancel()
    }

    override fun onCleared() {
        inFlight?.cancel()
        engine.cancel()
        Thread({ engine.unload() }, "relay-clip-unload").apply {
            isDaemon = true
            start()
        }
        super.onCleared()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }
}
