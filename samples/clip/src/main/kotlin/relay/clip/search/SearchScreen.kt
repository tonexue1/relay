package relay.clip.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel

private val SAMPLE_QUERIES = listOf(
    "华为 Mate 70 发布年份",
    "llama.cpp Android JNI",
    "DeepSeek API SSE",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检索原子") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "有博查 key 走 Web Search API；没有才 Bing / Wikipedia。点一条再 fetch_url。S2 深挖同一套。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = state.bochaKey,
                            onValueChange = viewModel::onBochaKeyChange,
                            label = { Text("博查 API Key") },
                            singleLine = true,
                            enabled = !state.running,
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = { Text("local.properties 的 relay.bocha.apiKey") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = { Text("Query") },
                            enabled = !state.running,
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SAMPLE_QUERIES.forEach { q ->
                                FilterChip(
                                    selected = state.query == q,
                                    onClick = { viewModel.onQueryChange(q) },
                                    enabled = !state.running,
                                    label = { Text(q) },
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = viewModel::search, enabled = state.canSearch) {
                                Text("搜索")
                            }
                            OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                                Text("取消")
                            }
                            if (state.running) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (state.source.isNotBlank() || state.elapsedMs != null) {
                item {
                    Text(
                        text = buildString {
                            if (state.source.isNotBlank()) append("来源 ${state.source}")
                            state.elapsedMs?.let {
                                if (isNotEmpty()) append(" · ")
                                append("${it}ms")
                            }
                            if (state.hits.isNotEmpty()) append(" · ${state.hits.size} 条")
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            items(state.hits, key = { it.url + it.title }) { hit ->
                Card(
                    onClick = { viewModel.fetch(hit.url) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hit.url == state.fetchUrl) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = hit.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (hit.snippet.isNotBlank()) {
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.fetchText.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("fetch_url · ${state.fetchUrl}", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(state.fetchText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
