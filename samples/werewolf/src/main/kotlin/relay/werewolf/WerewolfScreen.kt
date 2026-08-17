package relay.werewolf

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WerewolfScreen(viewModel: WerewolfViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay/werewolf · 引擎导演", fontFamily = FontFamily.Monospace) },
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
            item { Controls(state, viewModel) }

            state.error?.let { error ->
                item { ErrorCard(error) }
            }

            if (state.bubbles.isNotEmpty() || state.running) {
                item { Text("场上", style = MaterialTheme.typography.titleSmall) }
                items(state.visibleBubbles, key = { it.id }) { bubble ->
                    SpeechBubble(bubble)
                }
            }

            if (state.events.isNotEmpty()) {
                item { Text("事件带", style = MaterialTheme.typography.titleSmall) }
                item { EventStrip(state.events) }
            }
        }
    }
}

@Composable
private fun ConfigCard(state: WerewolfUiState, viewModel: WerewolfViewModel) {
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
            text = "座位号上场，身份是暗牌。默认场上视角：夜里谁在行动、谁是狼都不显示。结束才公布身份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableCard(state: WerewolfUiState) {
    SectionCard {
        Text("第${state.day}天 · ${state.phase}", style = MaterialTheme.typography.titleSmall)
        Text(
            text = state.roster,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.speakingId?.let {
            Text("正在说：$it", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Controls(state: WerewolfUiState, viewModel: WerewolfViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = viewModel::start, enabled = state.canStart) {
            Text("开一局")
        }
        OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
            Text("停")
        }
        FilterChip(
            selected = state.godView,
            onClick = viewModel::toggleGodView,
            label = { Text(if (state.godView) "上帝视角" else "场上视角") },
        )
        if (state.running) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SpeechBubble(bubble: Bubble) {
    val system = bubble.speakerId == "system"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (system) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (system) "引擎" else bubble.speakerId,
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
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
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
