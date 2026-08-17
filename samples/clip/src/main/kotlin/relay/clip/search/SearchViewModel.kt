package relay.clip.search

import androidx.lifecycle.ViewModel
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
import relay.clip.BuildConfig

data class SearchUiState(
    val query: String = "华为 Mate 70 发布年份",
    val bochaKey: String = BuildConfig.BOCHA_API_KEY,
    val running: Boolean = false,
    val source: String = "",
    val hits: List<SearchHit> = emptyList(),
    val fetchUrl: String? = null,
    val fetchText: String = "",
    val error: String? = null,
    val elapsedMs: Long? = null,
) {
    val canSearch: Boolean get() = !running && query.isNotBlank()
}

class SearchViewModel : ViewModel() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onBochaKeyChange(value: String) = _uiState.update { it.copy(bochaKey = value) }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank() || _uiState.value.running) return
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            _uiState.update {
                it.copy(running = true, error = null, hits = emptyList(), fetchText = "", fetchUrl = null, source = "", elapsedMs = null)
            }
            try {
                var result = SearchHits("none", emptyList())
                val ms = measureTimeMillis {
                    result = withContext(Dispatchers.IO) {
                        WebSearch(http, _uiState.value.bochaKey.trim()).searchWithSource(query)
                    }
                }
                _uiState.update {
                    it.copy(
                        running = false,
                        source = result.source,
                        hits = result.hits,
                        elapsedMs = ms,
                        error = if (result.hits.isEmpty()) "没有结果" else null,
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun fetch(url: String) {
        if (_uiState.value.running) return
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            _uiState.update { it.copy(running = true, error = null, fetchUrl = url, fetchText = "") }
            try {
                val text = withContext(Dispatchers.IO) {
                    WebSearch(http, _uiState.value.bochaKey.trim()).fetchUrl(url)
                }
                _uiState.update { it.copy(running = false, fetchText = text) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancel() {
        inFlight?.cancel()
        inFlight = null
        _uiState.update { it.copy(running = false) }
    }
}
