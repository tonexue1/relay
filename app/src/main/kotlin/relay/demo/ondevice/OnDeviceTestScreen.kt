package relay.demo.ondevice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import relay.ondevice.model.OnDeviceModels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceTestScreen(
    onBack: () -> Unit,
    viewModel: OnDeviceTestViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val spec = OnDeviceModels.default

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay-ondevice", fontFamily = FontFamily.Monospace) },
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "运行时下载到 filesDir/models，SHA-256 校验后经 JNI 加载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                state.modelLoaded -> "状态: 已加载"
                                state.loadingModel -> "状态: 加载中…"
                                state.modelReady -> "状态: 已下载,待加载"
                                else -> "状态: 未下载"
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.downloading) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(state.downloadLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.loadingModel) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::download,
                                enabled = state.canDownload,
                            ) { Text("下载模型") }
                            Button(
                                onClick = viewModel::load,
                                enabled = state.canLoad,
                            ) { Text(if (state.loadingModel) "加载中…" else "加载") }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.prompt,
                            onValueChange = viewModel::onPromptChange,
                            label = { Text("Prompt") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("流式", modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.streaming,
                                onCheckedChange = viewModel::onStreamingChange,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::send,
                                enabled = state.canSend,
                            ) { Text(if (state.running) "推理中…" else "发送") }
                            OutlinedButton(
                                onClick = viewModel::cancel,
                                enabled = state.running,
                            ) { Text("取消") }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }

            if (state.output.isNotEmpty() || state.running) {
                item {
                    Card {
                        SelectionContainer {
                            Text(
                                text = state.output.ifEmpty { "…" },
                                modifier = Modifier.padding(16.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }

            if (state.latencyMs != null || state.usageLabel.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.ttftMs?.let { Text("TTFT: ${it} ms", fontFamily = FontFamily.Monospace) }
                        state.latencyMs?.let { Text("Latency: ${it} ms", fontFamily = FontFamily.Monospace) }
                        if (state.usageLabel.isNotEmpty()) {
                            Text("Usage: ${state.usageLabel}", fontFamily = FontFamily.Monospace)
                        }
                        state.metrics?.let { m ->
                            Text(
                                "Metrics: ok=${m.succeeded} total=${m.durationMillis}ms" +
                                    (m.timeToFirstTokenMillis?.let { " ttft=${it}ms" } ?: ""),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            if (state.logs.isNotEmpty()) {
                item { Text("日志", style = MaterialTheme.typography.titleSmall) }
                items(state.logs) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}
