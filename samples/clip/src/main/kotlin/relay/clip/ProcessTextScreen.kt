package relay.clip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import relay.clip.research.ResearchPanel
import relay.clip.rewrite.RewriteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessTextScreen(
    selected: String,
    writable: Boolean,
    sourceLabel: String,
    onWriteBack: (String) -> Unit,
    onClose: () -> Unit,
    autoResearch: Boolean = false,
    viewModel: RewriteViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(selected) { viewModel.setSource(selected) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$sourceLabel · 课题") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("关闭") }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (writable) "来源可编辑。改完可以写回选区。" else "只读进线，改写只留在本屏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = selected.ifBlank { "（没有文字）" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuggestionChip(onClick = {}, enabled = false, label = { Text("端") })
                        Text(
                            text = viewModel.modelName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = when {
                            state.modelLoaded -> "已加载"
                            state.loadingModel -> "加载中…"
                            state.modelReady -> "已下载，待加载"
                            state.downloading -> state.downloadLabel
                            else -> "未下载。端挂了就失败，不上云。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (state.downloading) {
                        LinearProgressIndicator(
                            progress = { state.downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.loadingModel) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::download, enabled = state.canDownload) {
                            Text("下载模型")
                        }
                        Button(onClick = viewModel::load, enabled = state.canLoad) {
                            Text(if (state.loadingModel) "加载中…" else "加载")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::rewrite,
                            enabled = state.canRewrite,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.running) "改写中…" else "改正式一点")
                        }
                        OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                            Text("取消")
                        }
                    }
                    val timing = buildString {
                        state.ttftMs?.let { append("TTFT ${it}ms") }
                        state.prefillMs?.let {
                            if (isNotEmpty()) append(" · ")
                            append("prefill ${it}ms")
                        }
                        state.decodeMs?.let {
                            if (isNotEmpty()) append(" · ")
                            append("decode ${it}ms")
                        }
                        state.latencyMs?.let {
                            if (isNotEmpty()) append(" · ")
                            append("total ${it}ms")
                        }
                        state.metrics?.timeToFirstTokenMillis?.let {
                            if (isNotEmpty()) append(" · ")
                            append("metrics ttft ${it}ms")
                        }
                    }
                    if (timing.isNotEmpty()) {
                        Text(timing, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            ResearchPanel(source = selected, autoStart = autoResearch)

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            if (state.output.isNotEmpty() || state.running) {
                Card {
                    SelectionContainer {
                        Text(
                            text = state.output.ifEmpty { "…" },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            if (writable && state.output.isNotBlank() && !state.running) {
                Button(
                    onClick = { onWriteBack(state.output.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("写回改写结果")
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("丢弃")
            }
        }
    }
}
