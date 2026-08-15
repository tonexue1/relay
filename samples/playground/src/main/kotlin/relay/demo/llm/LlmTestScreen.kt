package relay.demo.llm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import relay.llm.interceptor.CallMetrics
import relay.llm.provider.DeepSeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmTestScreen(
    onBack: () -> Unit,
    viewModel: LlmTestViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay/llm", fontFamily = FontFamily.Monospace) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConfigCard(state, viewModel) }
            item { PromptCard(state, viewModel) }

            state.error?.let { error ->
                item { ErrorCard(error) }
            }

            if (state.output.isNotEmpty() || state.running) {
                item { OutputCard(state) }
            }

            if (state.toolCalls.isNotEmpty()) {
                item { ToolCallsCard(state) }
            }

            state.metrics?.let { metrics ->
                item { MetricsCard(metrics, state.estimatedPromptTokens) }
            }

            if (state.logs.isNotEmpty()) {
                item { SectionTitle("拦截器日志") }
                items(state.logs) { line -> LogLine(line) }
            }

            item { SectionTitle("调用代码") }
            item { CodeCard(callSiteSnippet(state)) }
        }
    }
}

@Composable
private fun ConfigCard(state: LlmTestUiState, viewModel: LlmTestViewModel) {
    SectionCard {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            label = { Text("Base URL") },
            singleLine = true,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("API Key") },
            singleLine = true,
            enabled = !state.running,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("也可写进 local.properties 的 relay.deepseek.apiKey") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("模型", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeepSeek.MODELS.forEach { model ->
                FilterChip(
                    selected = state.model == model.id,
                    onClick = { viewModel.onModelChange(model.id) },
                    enabled = !state.running,
                    label = { Text(model.id) },
                )
            }
        }
        Text(
            text = DeepSeek.MODELS.firstOrNull { it.id == state.model }
                ?.let { "上下文 ${it.contextWindow / 1024}K · 最大输出 ${it.maxOutputTokens} · ${it.capabilities.joinToString()}" }
                .orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("流式输出", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.streaming,
                onCheckedChange = viewModel::onStreamingChange,
                enabled = !state.running,
            )
        }
    }
}

@Composable
private fun PromptCard(state: LlmTestUiState, viewModel: LlmTestViewModel) {
    SectionCard {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = viewModel::onPromptChange,
            label = { Text("Prompt") },
            enabled = !state.running,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = viewModel::send, enabled = state.canSend) {
                Text("发送")
            }
            OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                Text("取消")
            }
            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun OutputCard(state: LlmTestUiState) {
    SectionCard {
        Text("输出", style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(
                text = state.output.ifEmpty { "等待首个 token..." },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ToolCallsCard(state: LlmTestUiState) {
    SectionCard {
        Text("工具调用", style = MaterialTheme.typography.labelLarge)
        state.toolCalls.forEach { call ->
            Text(
                text = "${call.name}(${call.argumentsJson})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MetricsCard(metrics: CallMetrics, estimatedPromptTokens: Int) {
    SectionCard {
        Text("指标", style = MaterialTheme.typography.labelLarge)
        MetricRow("provider", metrics.providerId)
        MetricRow("总耗时", "${metrics.durationMillis} ms")
        metrics.timeToFirstTokenMillis?.let { MetricRow("首 token", "$it ms") }
        metrics.usage?.let {
            MetricRow("token", "prompt ${it.promptTokens} · completion ${it.completionTokens} · total ${it.totalTokens}")
        }
        MetricRow("prompt 预估", "$estimatedPromptTokens (启发式)")
        metrics.finishReason?.let { MetricRow("finish", it.name) }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ErrorCard(error: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun LogLine(line: String) {
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CodeCard(code: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        SelectionContainer {
            Text(
                text = code,
                modifier = Modifier
                    .padding(16.dp)
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}
