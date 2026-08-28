package relay.demo.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import relay.uikit.ChatTurn
import relay.uikit.ProcessStatus
import relay.uikit.TurnItem
import relay.uikit.GraphWidget
import relay.uikit.HtmlArtifactPreview
import relay.uikit.MarkdownRenderer
import relay.uikit.WidgetHost

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showMemory by remember { mutableStateOf(false) }
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
    val preview = state.preview
    if (preview != null) {
        ArtifactPreviewScreen(preview, viewModel)
        return
    }
    if (showMemory) {
        MemoryOverviewScreen(state, onBack = { showMemory = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人助手", fontFamily = FontFamily.Monospace) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = { showMemory = true }) { Text("记忆") } },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingsCard(state, viewModel) }
            if (state.stageTrace.isNotBlank() || state.toolTrace.isNotBlank()) {
                item { TraceCard(state) }
            }
            items(state.turns, key = { it.id }) { turn ->
                TurnCard(turn, viewModel::openArtifact)
            }
            state.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            item { Composer(state, viewModel) }
        }
    }
}

@Composable
private fun SettingsCard(state: AssistantUiState, viewModel: AssistantViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "原文先落盘。对话轮次写成 Episode，状态由宿主 commit。",
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
                supportingText = { Text("未消费原文 ${state.pendingRaw} 条") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TraceCard(state: AssistantUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(state.stageTrace.ifBlank { "本轮" }, style = MaterialTheme.typography.labelLarge)
            if (state.toolTrace.isNotBlank()) {
                SelectionContainer {
                    Text(state.toolTrace, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryOverviewScreen(state: AssistantUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆总览") },
                navigationIcon = { TextButton(onClick = onBack) { Text("对话") } },
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${state.factCount} 条状态 · ${state.claimCount} 条经历 · ${state.pendingRaw} 条待消费原文",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                GraphWidget(state.graph, Modifier.fillMaxWidth())
            }
            item {
                Text(
                    "图中的边只来自已入库事实；Claim 单独列出，不提升为关系。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TurnCard(turn: ChatTurn, openArtifact: (String, Int) -> Unit) {
    val isUser = turn.role == "user"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isUser) "你" else "助手", style = MaterialTheme.typography.labelSmall)
            turn.items.forEach { item ->
                when (item) {
                    is TurnItem.Text -> MarkdownRenderer(item.text)
                    is TurnItem.Process -> ProcessChip(item)
                    is TurnItem.Widget -> WidgetHost(
                        item.spec,
                        onOpenArtifact = { openArtifact(it.artifactId, it.artifactVersion) },
                    )
                    is TurnItem.Artifact -> WidgetHost(
                        item.file,
                        onOpenArtifact = { openArtifact(it.artifactId, it.artifactVersion) },
                    )
                }
            }
            if (!turn.complete && turn.items.isEmpty()) Text("…")
        }
    }
}

@Composable
private fun ProcessChip(item: TurnItem.Process) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val prefix = when (item.status) {
        ProcessStatus.RUNNING -> "运行中"
        ProcessStatus.SUCCEEDED -> "完成"
        ProcessStatus.FAILED -> "失败"
        ProcessStatus.CANCELLED -> "取消"
    }
    Column(Modifier.clickable { expanded = !expanded }) {
        Text(
            "$prefix · ${item.label}",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.status == ProcessStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Text(item.argumentsSummary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (item.resultSummary.isNotBlank()) {
                Text(item.resultSummary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: AssistantUiState, viewModel: AssistantViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Text("UI 演示", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantCorpus.uiTalks.forEach { talk ->
                    FilterChip(
                        selected = state.prompt == talk.prompt,
                        onClick = { viewModel.onPromptChange(talk.prompt) },
                        enabled = !state.busy,
                        label = { Text(talk.label) },
                    )
                }
            }
            (AssistantCorpus.talks + AssistantCorpus.uiTalks)
                .firstOrNull { it.prompt == state.prompt }
                ?.let { sample ->
                    Text(
                        sample.expect,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = viewModel::send, enabled = state.canSend) { Text("发送") }
                Button(
                    onClick = viewModel::toggleLatestReplay,
                    enabled = state.replaying || (!state.busy && state.apiKey.isNotBlank()),
                ) {
                    Text(if (state.replaying) "停止 ${state.replayProgress}" else "回放 12 轮")
                }
                TextButton(onClick = viewModel::resetChat, enabled = !state.busy) { Text("清空") }
                if (state.running || state.learning) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactPreviewScreen(preview: ArtifactPreviewUi, viewModel: AssistantViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(preview.content.metadata.name)
                        Text("v${preview.content.metadata.version}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = viewModel::closeArtifact) { Text("关闭") } },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("preview" to "预览", "source" to "源码", "versions" to "版本").forEach { (id, label) ->
                    FilterChip(
                        selected = preview.tab == id,
                        onClick = { viewModel.setArtifactTab(id) },
                        label = { Text(label) },
                    )
                }
                if (preview.content.metadata.mime == "text/html") {
                    FilterChip(
                        selected = preview.annotationMode,
                        onClick = viewModel::toggleAnnotationMode,
                        label = { Text("标注") },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (preview.tab) {
                    "source" -> SelectionContainer {
                        Text(preview.content.body, fontFamily = FontFamily.Monospace)
                    }
                    "versions" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(preview.versions, key = { it.version }) { version ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text("v${version.version}${version.baseVersion?.let { " · 基于 v$it" }.orEmpty()}")
                                        Text(version.summary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { viewModel.selectArtifactVersion(version.version) }) { Text("查看") }
                                }
                            }
                        }
                    }
                    else -> if (preview.content.metadata.mime == "text/html") {
                        HtmlArtifactPreview(
                            preview.content.body,
                            annotationMode = preview.annotationMode,
                            onDiagnostic = viewModel::onHtmlDiagnostic,
                        )
                    } else {
                        MarkdownRenderer(preview.content.body)
                    }
                }
            }
            OutlinedTextField(
                value = preview.feedback,
                onValueChange = viewModel::onArtifactFeedbackChange,
                label = { Text("反馈") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveArtifactFeedback(true) }) { Text("让 Relay 修复") }
                TextButton(onClick = { viewModel.saveArtifactFeedback(false) }) { Text("只保存反馈") }
                TextButton(onClick = viewModel::activatePreviewVersion) { Text("设为当前版本") }
            }
            if (preview.diagnostics.isNotEmpty()) {
                Text(
                    "诊断 ${preview.diagnostics.size} 条 · ${preview.diagnostics.last().kind}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
