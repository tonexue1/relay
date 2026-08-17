package relay.clip.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ResearchPanel(
    source: String,
    autoStart: Boolean = false,
    viewModel: ResearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(source, autoStart) {
        viewModel.setSource(source)
        if (autoStart && source.isNotBlank()) viewModel.research()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SuggestionChip(onClick = {}, enabled = false, label = { Text("云") })
                Text(
                    text = "Supervisor · 主编派 scout",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                enabled = !state.running,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("也可写 local.properties 的 relay.deepseek.apiKey") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.bochaKey,
                onValueChange = viewModel::onBochaKeyChange,
                label = { Text("博查 API Key") },
                singleLine = true,
                enabled = !state.running,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("open.bochaai.com · local.properties 的 relay.bocha.apiKey") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::research,
                    enabled = state.canRun,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.running) "深挖中…" else "深挖")
                }
                OutlinedButton(onClick = viewModel::cancel, enabled = state.running) {
                    Text("取消")
                }
            }
            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.scouts.forEach { card ->
                Text(
                    text = "${card.workerId} · ${card.status}\n${card.task.take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (card.findings.isNotEmpty()) {
                    Text(
                        text = card.findings.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.toolLog.isNotEmpty()) {
                state.toolLog.takeLast(8).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.leadText.isNotEmpty() || state.running) {
                Card {
                    SelectionContainer {
                        Text(
                            text = state.leadText.ifEmpty { "主编先想…" },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
