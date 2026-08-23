package relay.assistant.artifact

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import relay.assistant.theme.Paper
import relay.uikit.MarkdownRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownArtifactScreen(
    state: ArtifactPreviewState,
    onClose: () -> Unit,
    onTab: (ArtifactTab) -> Unit,
    onSelectVersion: (Int) -> Unit,
    onFeedback: (String) -> Unit,
    onSaveFeedback: () -> Unit,
    onFix: () -> Unit,
    onActivate: () -> Unit,
) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.content.metadata.name)
                        Text(
                            "v${state.content.metadata.version} · ${state.content.metadata.sizeBytes} 字节",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArtifactTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { onTab(tab) },
                        label = {
                            Text(
                                when (tab) {
                                    ArtifactTab.PREVIEW -> "预览"
                                    ArtifactTab.SOURCE -> "源码"
                                    ArtifactTab.VERSIONS -> "版本"
                                },
                            )
                        },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (state.tab) {
                    ArtifactTab.PREVIEW -> LazyColumn(Modifier.fillMaxSize()) {
                        item { MarkdownRenderer(state.content.body, Modifier.fillMaxWidth()) }
                    }
                    ArtifactTab.SOURCE -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            SelectionContainer {
                                Text(state.content.body, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    ArtifactTab.VERSIONS -> LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.versions, key = { it.version }) { version ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("v${version.version}")
                                        Text(version.summary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { onSelectVersion(version.version) }) {
                                        Text(if (version.version == state.content.metadata.version) "当前" else "查看")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.feedback,
                onValueChange = onFeedback,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("对这个版本的反馈") },
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onFix, enabled = state.feedback.isNotBlank()) { Text("让 Relay 修复") }
                TextButton(onClick = onSaveFeedback, enabled = state.feedback.isNotBlank()) { Text("保存反馈") }
                TextButton(onClick = onActivate) { Text("设为当前") }
            }
        }
    }
}
