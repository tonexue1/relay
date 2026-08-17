package relay.demo.orchestra

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.SuggestionChip
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
fun GroupChatScreen(
    onBack: () -> Unit,
    viewModel: GroupChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay/orchestra · GroupChat", fontFamily = FontFamily.Monospace) },
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
            item { TableCard(state) }
            item { PromptCard(state, viewModel) }

            state.error?.let { error ->
                item { ErrorCard(error) }
            }

            if (state.bubbles.isNotEmpty() || state.running) {
                item { SectionTitle("用户听见的话") }
                items(state.bubbles, key = { it.id }) { bubble ->
                    SpeechBubble(bubble)
                }
            }

            if (state.events.isNotEmpty()) {
                item { SectionTitle("事件带") }
                item { EventStrip(state.events) }
            }
        }
    }
}

@Composable
private fun ConfigCard(state: GroupChatUiState, viewModel: GroupChatViewModel) {
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
            text = "三人共用 Scene，规则选人（不能连说两句）。没有综合者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableCard(state: GroupChatUiState) {
    SectionCard {
        Text("圆桌", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GROUP_CHAT_SEATS.forEach { seat ->
                val active = state.highlightId == seat.id
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(seat.id, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (state.speakingId == seat.id) "正在说" else if (active) "下一个" else seat.stance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = "你 · 出题 / 随时插话（一轮结束后）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(state: GroupChatUiState, viewModel: GroupChatViewModel) {
    SectionCard {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = viewModel::onPromptChange,
            label = { Text("出题") },
            enabled = !state.running,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("题目样例", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GROUP_CHAT_TOPICS.forEach { (label, prompt) ->
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
                Text("出题")
            }
            OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                Text("取消")
            }
            TextButton(onClick = viewModel::resetTable, enabled = !state.running) {
                Text("清空圆桌")
            }
            if (state.running) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun SpeechBubble(bubble: Bubble) {
    val fromUser = bubble.speakerId == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.86f),
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (fromUser) "你" else bubble.speakerId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                    Text(
                        text = bubble.text.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventStrip(events: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        events.forEach { line ->
            SuggestionChip(onClick = {}, label = { Text(line, fontSize = 11.sp) })
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
