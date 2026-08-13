package relay.demo.ondevice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
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
import relay.llm.interceptor.LoggingInterceptor
import relay.llm.interceptor.MetricsInterceptor
import relay.llm.interceptor.intercept
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.ondevice.OnDeviceProvider
import relay.ondevice.engine.JniLlamaEngine
import relay.ondevice.model.ModelStore
import relay.ondevice.model.OnDeviceModels

data class OnDeviceUiState(
    val modelReady: Boolean = false,
    val modelLoaded: Boolean = false,
    val loadingModel: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadLabel: String = "",
    val prompt: String = "用一句话解释什么是端云协同。",
    val streaming: Boolean = true,
    val running: Boolean = false,
    val output: String = "",
    val error: String? = null,
    val metrics: CallMetrics? = null,
    val usageLabel: String = "",
    val latencyMs: Long? = null,
    val ttftMs: Long? = null,
    val logs: List<String> = emptyList(),
) {
    val canDownload: Boolean get() = !downloading && !modelReady
    val canLoad: Boolean get() = modelReady && !modelLoaded && !loadingModel && !running && !downloading
    val canSend: Boolean get() = modelLoaded && !running && !loadingModel && prompt.isNotBlank()
}

class OnDeviceTestViewModel(application: Application) : AndroidViewModel(application) {

    private val modelSpec = OnDeviceModels.default
    private val store = ModelStore(
        rootDir = application.filesDir.resolve("models"),
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build(),
    )
    private val engine = JniLlamaEngine()
    private val provider = OnDeviceProvider(engine, modelSpec)

    private val _uiState = MutableStateFlow(OnDeviceUiState())
    val uiState: StateFlow<OnDeviceUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null

    init {
        // SHA-256 of a ~400MB GGUF must not run on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val ready = store.isReady(modelSpec)
            _uiState.update { it.copy(modelReady = ready) }
        }
    }

    fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }

    fun onStreamingChange(value: Boolean) = _uiState.update { it.copy(streaming = value) }

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
                appendLog("model ready: ${modelSpec.fileName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloading = false, error = e.message ?: "download failed")
                }
            }
        }
    }

    fun load() {
        if (!_uiState.value.canLoad) return
        _uiState.update { it.copy(loadingModel = true, error = null) }
        viewModelScope.launch {
            try {
                val path = store.localFile(modelSpec).absolutePath
                // Use all cores for decode; UI work is light and already off the inference threads.
                val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                withContext(Dispatchers.IO) {
                    // Cap context for phone RAM; full 32k is unnecessary for the demo.
                    provider.load(path, nCtx = 2048, nThreads = threads)
                }
                _uiState.update { it.copy(modelLoaded = true, loadingModel = false, error = null) }
                appendLog("loaded $path (threads=$threads)")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loadingModel = false, error = e.message ?: "load failed")
                }
            }
        }
    }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return

        val request = ChatRequest(
            model = modelSpec.id,
            messages = listOf(
                Message.system("你是一个简洁的中文助手,回答控制在三句话内。"),
                Message.user(state.prompt),
            ),
            temperature = 0.7,
            maxTokens = 128,
        )

        _uiState.update {
            it.copy(
                running = true,
                output = "",
                error = null,
                metrics = null,
                usageLabel = "",
                latencyMs = null,
                ttftMs = null,
            )
        }

        val instrumented = provider.intercept(
            LoggingInterceptor(logger = ::appendLog),
            MetricsInterceptor { metrics -> _uiState.update { it.copy(metrics = metrics) } },
        )

        inFlight = viewModelScope.launch {
            val started = System.nanoTime()
            var firstTokenAt: Long? = null
            try {
                if (state.streaming) {
                    instrumented.stream(request).collect { chunk ->
                        when (chunk) {
                            is ChatChunk.Text -> {
                                if (firstTokenAt == null) firstTokenAt = System.nanoTime()
                                _uiState.update { it.copy(output = it.output + chunk.delta) }
                            }
                            is ChatChunk.ToolCalls -> Unit
                            is ChatChunk.Done -> {
                                val usage = chunk.usage
                                _uiState.update {
                                    it.copy(
                                        usageLabel = usage?.let { u ->
                                            "prompt=${u.promptTokens} completion=${u.completionTokens}"
                                        }.orEmpty(),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val elapsed = measureTimeMillis {
                        val response = instrumented.chat(request)
                        _uiState.update {
                            it.copy(
                                output = response.message.content.orEmpty(),
                                usageLabel = response.usage?.let { u ->
                                    "prompt=${u.promptTokens} completion=${u.completionTokens}"
                                }.orEmpty(),
                            )
                        }
                    }
                    _uiState.update { it.copy(latencyMs = elapsed) }
                }
            } catch (e: CancellationException) {
                appendLog("call cancelled")
                _uiState.update { it.copy(error = "已取消") }
                throw e
            } catch (e: RelayLlmException) {
                appendLog("error: ${e.message}")
                _uiState.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                appendLog("error: ${e::class.simpleName}: ${e.message}")
                _uiState.update { it.copy(error = e.message ?: "inference failed") }
            } finally {
                val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val ttft = firstTokenAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) }
                _uiState.update {
                    it.copy(
                        running = false,
                        latencyMs = it.latencyMs ?: totalMs,
                        ttftMs = ttft,
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
        // unload is blocking JNI; never do it on the main thread.
        Thread({ engine.unload() }, "relay-ondevice-unload").apply {
            isDaemon = true
            start()
        }
        super.onCleared()
    }

    private fun appendLog(line: String) {
        _uiState.update { it.copy(logs = (it.logs + line).takeLast(80)) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }
}
