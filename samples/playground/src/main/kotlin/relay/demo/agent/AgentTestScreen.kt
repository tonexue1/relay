package relay.demo.agent

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentTestScreen(
    onBack: () -> Unit,
    viewModel: AgentTestViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay/agent-core", fontFamily = FontFamily.Monospace) },
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

            if (state.events.isNotEmpty()) {
                item { SectionTitle("Agent 事件") }
                items(state.events) { line -> LogLine(line) }
            }

            if (state.logs.isNotEmpty()) {
                item { SectionTitle("拦截器日志") }
                items(state.logs) { line -> LogLine(line) }
            }

            item { SectionTitle("调用代码") }
            item { CodeCard(agentCallSiteSnippet()) }
        }
    }
}

@Composable
private fun ConfigCard(state: AgentTestUiState, viewModel: AgentTestViewModel) {
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
        Text(
            text = "模型 ${state.model} · 工具含 web_search / fetch_url · maxTurns=12",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PromptCard(state: AgentTestUiState, viewModel: AgentTestViewModel) {
    SectionCard {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = viewModel::onPromptChange,
            label = { Text("Prompt") },
            enabled = !state.running,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("长任务样例", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SAMPLE_TASKS.forEach { (label, prompt) ->
                FilterChip(
                    selected = state.prompt == prompt,
                    onClick = { viewModel.onPromptChange(prompt) },
                    enabled = !state.running,
                    label = { Text(label) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = viewModel::send, enabled = state.canSend) {
                Text("发送")
            }
            OutlinedButton(onClick = viewModel::continueRun, enabled = !state.running && state.canContinue) {
                Text("继续")
            }
            OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                Text("取消")
            }
            TextButton(onClick = viewModel::resetSession, enabled = !state.running) {
                Text("清空会话")
            }
            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun OutputCard(state: AgentTestUiState) {
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
