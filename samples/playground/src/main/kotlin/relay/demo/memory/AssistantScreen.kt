package relay.demo.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人助手", fontFamily = FontFamily.Monospace) },
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "原文先落盘；4 回合或空闲后批量学习。复杂经历进 Claim，稳定关系进图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = viewModel::onApiKeyChange,
                            label = { Text("API Key") },
                            singleLine = true,
                            enabled = !state.busy,
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = {
                                Text("4 回合或空闲 60 秒学习。未消费原文 ${state.pendingRaw} 条")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "活图 ${state.factCount} 条 · Claim ${state.claimCount} 条",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SelectionContainer {
                            Text(
                                text = state.facts.ifBlank { "还没有事实。说两句就会进图。" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            state.stageTrace.ifBlank { "本轮" },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SelectionContainer {
                            Text(
                                text = state.toolTrace.ifBlank { "对话看 tool call；学习/整理状态在上方。" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            items(state.lines) { line ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (line.role) {
                            "user" -> MaterialTheme.colorScheme.surfaceVariant
                            "dream" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (line.role) {
                                "user" -> "你"
                                "dream" -> "夜"
                                else -> "助手"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (state.output.isNotEmpty() || state.busy) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.output.ifEmpty { "…" },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = state.prompt,
                            onValueChange = viewModel::onPromptChange,
                            label = { Text("说话") },
                            enabled = !state.busy,
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistantCorpus.talks.forEach { talk ->
                                FilterChip(
                                    selected = state.prompt == talk.prompt,
                                    onClick = { viewModel.onPromptChange(talk.prompt) },
                                    enabled = !state.busy,
                                    label = { Text(talk.label) },
                                )
                            }
                        }
                        val expect = AssistantCorpus.talks.firstOrNull { it.prompt == state.prompt }?.expect
                        if (expect != null) {
                            Text(
                                expect,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = viewModel::send, enabled = state.canSend) {
                                Text("发送")
                            }
                            Button(
                                onClick = viewModel::toggleLatestReplay,
                                enabled = state.replaying || (!state.busy && state.apiKey.isNotBlank()),
                            ) {
                                Text(
                                    if (state.replaying) {
                                        "停止回放 ${state.replayProgress}"
                                    } else {
                                        "回放刚才 12 轮"
                                    },
                                )
                            }
                            TextButton(onClick = viewModel::resetChat, enabled = !state.busy) {
                                Text("清空对话")
                            }
                            if (state.running || state.learning || state.consolidating) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
