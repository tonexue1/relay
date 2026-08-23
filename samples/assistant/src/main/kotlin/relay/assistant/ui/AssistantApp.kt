package relay.assistant.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import relay.assistant.artifact.ArtifactViewModel
import relay.assistant.artifact.MarkdownArtifactScreen
import relay.assistant.session.AssistantSession
import relay.assistant.state.AssistantUiState
import relay.assistant.state.AssistantViewModel
import relay.assistant.theme.Ink
import relay.assistant.theme.InkMuted
import relay.assistant.theme.Line
import relay.assistant.theme.Paper
import relay.assistant.theme.PaperRaised
import relay.assistant.theme.Rust
import relay.assistant.theme.RustSoft
import relay.uiagent.ChatTurn
import relay.uiagent.TurnItem
import relay.uikit.ChoiceFormSpec
import relay.uikit.FileSpec
import relay.uikit.GraphWidget
import relay.uikit.MarkdownRenderer
import relay.uikit.WidgetHost

private enum class Destination { CHAT, MEMORY, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantApp(
    viewModel: AssistantViewModel = viewModel(),
    artifactViewModel: ArtifactViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val artifactPreview by artifactViewModel.preview.collectAsState()
    var destination by remember { mutableStateOf(Destination.CHAT) }
    var toolLog by remember { mutableStateOf<List<TurnItem.Process>?>(null) }
    var products by remember { mutableStateOf<List<TurnItem>?>(null) }
    var researchCandidate by remember { mutableStateOf<String?>(null) }
    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()
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
    artifactPreview?.let { preview ->
        MarkdownArtifactScreen(
            state = preview,
            onClose = artifactViewModel::close,
            onTab = artifactViewModel::setTab,
            onSelectVersion = artifactViewModel::selectVersion,
            onFeedback = artifactViewModel::setFeedback,
            onSaveFeedback = { artifactViewModel.saveFeedback() },
            onFix = {
                artifactViewModel.buildFixPrompt()?.let { prompt ->
                    artifactViewModel.close()
                    viewModel.send(prompt)
                }
            },
            onActivate = artifactViewModel::activate,
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = state.sessions,
                activeSessionId = state.activeSessionId,
                onNew = {
                    viewModel.newSession()
                    destination = Destination.CHAT
                    scope.launch { drawerState.close() }
                },
                onSessionSelected = {
                    viewModel.selectSession(it)
                    destination = Destination.CHAT
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = viewModel::deleteSession,
                onRenameSession = viewModel::renameSession,
                onSettings = {
                    destination = Destination.SETTINGS
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                AssistantTopBar(
                    title = when (destination) {
                        Destination.CHAT -> state.activeSession?.title ?: "新对话"
                        Destination.MEMORY -> "记忆"
                        Destination.SETTINGS -> "设置"
                    },
                    onMenu = { scope.launch { drawerState.open() } },
                    onNew = {
                        viewModel.newSession()
                        destination = Destination.CHAT
                    },
                    onMemory = { destination = Destination.MEMORY },
                    onSettings = { destination = Destination.SETTINGS },
                    onBack = { destination = Destination.CHAT },
                    showBack = destination == Destination.MEMORY,
                    showNew = destination == Destination.CHAT,
                )
            },
        ) { insets ->
            AnimatedContent(
                targetState = destination,
                label = "destination",
                modifier = Modifier.fillMaxSize().padding(insets),
            ) { current ->
                when (current) {
                    Destination.CHAT -> ChatScreen(
                        state = state,
                        onSend = viewModel::send,
                        onOpenToolLog = { toolLog = it },
                        onOpenProducts = { products = it },
                        onOpenArtifact = artifactViewModel::open,
                        onSubmitChoiceForm = viewModel::submitChoiceForm,
                    )
                    Destination.MEMORY -> MemoryScreen(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onResearch = { researchCandidate = it },
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        state = state,
                        onApiKeyChange = viewModel::onApiKeyChange,
                        onMemoryEnabledChange = viewModel::onMemoryEnabledChange,
                        onBack = { destination = Destination.CHAT },
                    )
                }
            }
        }
    }

    toolLog?.let { processes ->
        ModalBottomSheet(
            onDismissRequest = { toolLog = null },
            containerColor = PaperRaised,
        ) {
            ToolLogSheet(processes)
        }
    }
    products?.let { items ->
        ModalBottomSheet(
            onDismissRequest = { products = null },
            containerColor = PaperRaised,
        ) {
            ArtifactGallerySheet(
                items = items,
                onOpenArtifact = {
                    products = null
                    artifactViewModel.open(it)
                },
            )
        }
    }
    researchCandidate?.let { entity ->
        ModalBottomSheet(
            onDismissRequest = { researchCandidate = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ResearchLaunchSheet(
                entity = entity,
                onCancel = { researchCandidate = null },
                onStart = {
                    researchCandidate = null
                    viewModel.startResearchSession(entity)
                    destination = Destination.CHAT
                },
            )
        }
    }
}

@Composable
private fun AssistantTopBar(
    title: String,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    onMemory: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    showNew: Boolean,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(color = PaperRaised, shadowElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIcon("‹", "返回对话", onBack)
            } else {
                RoundIcon("☰", "会话", onMenu)
            }
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showNew) RoundIcon("＋", "新对话", onNew)
            Box {
                RoundIcon("···", "更多") { moreExpanded = true }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    if (!showBack) {
                        DropdownMenuItem(
                            text = { Text("记忆总览") },
                            onClick = {
                                moreExpanded = false
                                onMemory()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("设置与模型") },
                        onClick = {
                            moreExpanded = false
                            onSettings()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun RoundIcon(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).semantics { contentDescription = description }
            .clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SessionDrawer(
    sessions: List<AssistantSession>,
    activeSessionId: String,
    onNew: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onSettings: () -> Unit,
) {
    var sessionQuery by remember { mutableStateOf("") }
    var renamingSession by remember { mutableStateOf<AssistantSession?>(null) }
    var renameValue by remember { mutableStateOf("") }
    val context = LocalContext.current
    ModalDrawerSheet(
        drawerContainerColor = PaperRaised,
        modifier = Modifier.fillMaxHeight().width(320.dp),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Ink,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "R",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Relay", style = MaterialTheme.typography.titleLarge)
                    Text("理解你，也记得你", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("＋  新对话")
            }
            Text(
                "最近对话",
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = InkMuted,
            )
            OutlinedTextField(
                value = sessionQuery,
                onValueChange = { sessionQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("搜索会话") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            sessions.filter {
                sessionQuery.isBlank() ||
                    it.title.contains(sessionQuery, true) ||
                    it.summary.contains(sessionQuery, true)
            }.forEach { session ->
                NavigationDrawerItem(
                    selected = session.id == activeSessionId,
                    onClick = { onSessionSelected(session.id) },
                    label = {
                        Column {
                            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                session.summary,
                                color = InkMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    badge = {
                        Row {
                            Text(
                                "✎",
                                modifier = Modifier.clip(CircleShape).clickable {
                                    renamingSession = session
                                    renameValue = session.title
                                }.padding(6.dp),
                                color = InkMuted,
                            )
                            Text(
                                "×",
                                modifier = Modifier.clip(CircleShape).clickable { onDeleteSession(session.id) }.padding(6.dp),
                                color = InkMuted,
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = Line)
            NavigationDrawerItem(
                selected = false,
                onClick = {
                    val exportText = sessions.joinToString("\n\n---\n\n") { session ->
                        buildString {
                            append("# ${session.title}\n\n")
                            session.turns.forEach { turn ->
                                append(if (turn.role == "user") "我：" else "Relay：")
                                append(
                                    turn.items.mapNotNull { item ->
                                        when (item) {
                                            is TurnItem.Text -> item.text
                                            is TurnItem.Widget -> item.spec.summary()
                                            is TurnItem.Artifact -> item.file.summary()
                                            is TurnItem.Process -> null
                                        }
                                    }.joinToString("\n"),
                                )
                                append("\n\n")
                            }
                        }
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Relay 会话导出")
                                putExtra(Intent.EXTRA_TEXT, exportText)
                            },
                            "导出会话",
                        ),
                    )
                },
                icon = { Text("⇧") },
                label = { Text("导出全部会话") },
            )
            NavigationDrawerItem(
                selected = false,
                onClick = onSettings,
                icon = { Text("⚙") },
                label = { Text("设置与模型") },
            )
        }
    }
    renamingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("会话标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, renameValue)
                        renamingSession = null
                    },
                    enabled = renameValue.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ChatScreen(
    state: AssistantUiState,
    onSend: (String) -> Unit,
    onOpenToolLog: (List<TurnItem.Process>) -> Unit,
    onOpenProducts: (List<TurnItem>) -> Unit,
    onOpenArtifact: (FileSpec) -> Unit,
    onSubmitChoiceForm: (String, String, Map<String, List<String>>) -> Unit,
) {
    val listState = rememberLazyListState()
    val lastTurnSize = state.turns.lastOrNull()?.items?.sumOf {
        when (it) {
            is TurnItem.Text -> it.text.length
            else -> 1
        }
    } ?: 0
    LaunchedEffect(state.turns.size, lastTurnSize) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.lastIndex)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.turns.isEmpty()) {
                item { WelcomeCard(onSend) }
            }
            items(state.turns, key = { it.id }) { turn ->
                if (turn.role == "user") {
                    UserBubble(
                        turn.items.filterIsInstance<TurnItem.Text>().joinToString("\n") { it.text },
                    )
                } else {
                    AssistantTurn(
                        turn = turn,
                        onOpenToolLog = {
                            onOpenToolLog(turn.items.filterIsInstance<TurnItem.Process>())
                        },
                        onOpenProducts = {
                            onOpenProducts(turn.items.filter(::isProductItem))
                        },
                        onOpenArtifact = onOpenArtifact,
                        choiceFormEnabled = !state.running,
                        onSubmitChoiceForm = { itemId, answers ->
                            onSubmitChoiceForm(turn.id, itemId, answers)
                        },
                    )
                }
            }
            val researchEntity = state.activeSession?.researchEntity
            if (researchEntity != null && state.turns.none { it.role == "user" }) {
                item {
                    ResearchAspectPicker(
                        entity = researchEntity,
                        onSelect = { aspect ->
                            onSend("围绕「$researchEntity」开展扩展研究，重点研究：$aspect。先给出研究范围和行动路径。")
                        },
                    )
                }
            }
        }
        ComposerBar(
            onSend = onSend,
            enabled = state.canSend,
            busy = state.running,
            error = state.error,
        )
    }
}

@Composable
private fun ResearchAspectPicker(
    entity: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择研究方向", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("核心原理", "最新进展", "落地实践", "风险与争议", "与我的关系").forEach { aspect ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(aspect) },
                    label = { Text(aspect) },
                )
            }
        }
        Text(
            "也可以直接输入你对「$entity」最关心的问题。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WelcomeCard(onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Ink, shape = RoundedCornerShape(18.dp), modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "R",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        Text("今天想一起处理什么？", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp))
        Text(
            "我会结合你的记忆给出具体答案。",
            color = InkMuted,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
        )
        listOf(
            "根据我的经历，整理今天最重要的三件事",
            "回顾最近提到的项目，帮我找出下一步",
            "把这周的想法整理成一份清单",
        ).forEach { suggestion ->
            Surface(
                color = PaperRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onSend(suggestion) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(suggestion, Modifier.weight(1f))
                    Text("›", color = Rust)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = Ink,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 13.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantTurn(
    turn: ChatTurn,
    onOpenToolLog: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenArtifact: (FileSpec) -> Unit,
    choiceFormEnabled: Boolean,
    onSubmitChoiceForm: (String, Map<String, List<String>>) -> Unit,
) {
    val processes = turn.items.filterIsInstance<TurnItem.Process>()
    val hasFailure = processes.any { it.status == relay.uiagent.ProcessStatus.FAILED }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .clickable(enabled = processes.isNotEmpty(), onClick = onOpenToolLog)
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (hasFailure) MaterialTheme.colorScheme.error else Rust,
                shape = CircleShape,
                modifier = Modifier.size(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (hasFailure) "!" else "✓",
                        color = if (hasFailure) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                if (turn.complete) "已完成" else "思考中",
                modifier = Modifier.padding(start = 8.dp),
                color = InkMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            if (!turn.complete) {
                CircularProgressIndicator(Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 2.dp)
            }
            if (processes.isNotEmpty()) {
                Text(
                    " · 查看过程 ›",
                    color = Rust,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        var aggregateShown = false
        turn.items.forEach { item ->
            when (item) {
                is TurnItem.Text -> MarkdownRenderer(item.text, Modifier.fillMaxWidth())
                is TurnItem.Process -> when (processDisplay(item)) {
                    ProcessDisplay.HIDDEN -> Unit
                    ProcessDisplay.ERROR -> ProcessRow(
                        text = "${item.label} 执行失败",
                        onClick = onOpenToolLog,
                    )
                    ProcessDisplay.AGGREGATED -> if (!aggregateShown) {
                        val summary = processSummary(processes)
                        if (summary.isNotBlank()) ProcessRow(summary, onOpenToolLog)
                        aggregateShown = true
                    }
                }
                is TurnItem.Widget -> WidgetHost(
                    spec = item.spec,
                    modifier = Modifier.fillMaxWidth(),
                    choiceFormEnabled = choiceFormEnabled,
                    onChoiceFormSubmit = { _: ChoiceFormSpec, answers ->
                        onSubmitChoiceForm(item.id, answers)
                    },
                )
                is TurnItem.Artifact -> WidgetHost(
                    spec = item.file,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenArtifact = onOpenArtifact,
                )
            }
        }
        val products = turn.items.count(::isProductItem)
        if (products > 0) {
            TextButton(onClick = onOpenProducts, modifier = Modifier.align(Alignment.End)) {
                Text("本轮产物 ($products)  ›")
            }
        }
    }
}

private fun isProductItem(item: TurnItem): Boolean =
    item is TurnItem.Artifact

@Composable
private fun ProcessRow(text: String, onClick: () -> Unit) {
    Surface(
        color = RustSoft,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = Rust, style = MaterialTheme.typography.labelLarge)
            Text(text, Modifier.padding(start = 7.dp), color = Rust, style = MaterialTheme.typography.bodySmall)
            Text("  ›", color = Rust)
        }
    }
}

@Composable
private fun ComposerBar(
    onSend: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    error: String?,
) {
    var value by remember { mutableStateOf("") }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    ?: uri.lastPathSegment
                    ?: "文本附件"
                val body = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText().take(MAX_TEXT_ATTACHMENT_CHARS) }
                    ?: error("无法读取附件")
                attachmentName = name
                value = listOf(
                    value.trim(),
                    "附件《$name》的内容：\n$body",
                ).filter { it.isNotBlank() }.joinToString("\n\n")
                attachmentError = ""
            }.onFailure {
                attachmentError = "附件读取失败：${it.message.orEmpty()}"
            }
        }
    }
    Surface(color = PaperRaised, shadowElevation = 8.dp, modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            attachmentName?.let { name ->
                Surface(color = RustSoft, shape = RoundedCornerShape(999.dp), modifier = Modifier.padding(bottom = 7.dp)) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("附件 · $name", color = Rust, style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = {
                                attachmentName = null
                                value = ""
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("移除") }
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).clickable {
                            attachmentLauncher.launch("text/*")
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "＋",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        decorationBox = { inner ->
                            if (value.isBlank()) {
                                Text("发消息", color = InkMuted)
                            }
                            inner()
                        },
                    )
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (value.isBlank() || !enabled) Line else Rust)
                            .clickable(enabled = value.isNotBlank() && enabled) {
                                onSend(value.trim())
                                value = ""
                                attachmentName = null
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = InkMuted)
                        } else {
                            Text(
                                "↑",
                                color = if (value.isBlank() || !enabled) {
                                    InkMuted
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Text(
                attachmentError.ifBlank {
                    error ?: if (!enabled && !busy) "请先在设置中填写 API Key" else "Relay 可能会出错，请核对重要信息"
                },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                color = if (attachmentError.isNotBlank() || error != null || (!enabled && !busy)) {
                    MaterialTheme.colorScheme.error
                } else {
                    InkMuted
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToolLogSheet(processes: List<TurnItem.Process>) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
        Text("本轮过程", style = MaterialTheme.typography.headlineSmall)
        Text(
            "模型只决定内容，过程与组件由宿主稳定呈现。",
            color = InkMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        processes.forEach { process ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    if (process.status == relay.uiagent.ProcessStatus.FAILED) "×" else "✓",
                    color = Rust,
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(process.label)
                    if (process.argumentsSummary.isNotBlank()) {
                        Text(process.argumentsSummary, color = InkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (process.resultSummary.isNotBlank()) {
                        Text(process.resultSummary, color = InkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun ArtifactGallerySheet(
    items: List<TurnItem>,
    onOpenArtifact: (FileSpec) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
        Text("本轮产物", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${items.size} 项 · 原生组件和 Markdown 文件",
            color = InkMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        items.forEachIndexed { index, item ->
            val file = (item as? TurnItem.Artifact)?.file
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clickable(enabled = file?.status == "ready") {
                        file?.let(onOpenArtifact)
                    },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (file != null) Rust else Ink,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (file != null) "MD" else "UI",
                                color = if (file != null) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.inverseOnSurface
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(file?.name ?: "组件 ${index + 1}", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (item) {
                                is TurnItem.Widget -> item.spec.summary()
                                is TurnItem.Artifact -> item.file.summary()
                                else -> ""
                            },
                            color = InkMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (file?.status == "ready") Text("›", color = Rust)
                }
            }
        }
    }
}

@Composable
private fun ResearchLaunchSheet(
    entity: String,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("扩展研究", color = Rust, style = MaterialTheme.typography.labelLarge)
        Text(entity, style = MaterialTheme.typography.headlineLarge)
        Text(
            "把这个实体带到一个干净的新对话里。Relay 会先问你想研究什么，再结合现有记忆逐步展开。",
            color = InkMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            color = RustSoft,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ResearchStep("1", "创建独立研究对话", "不打断当前会话")
                ResearchStep("2", "选择研究方向", "原理、进展、实践、风险或与你的关系")
                ResearchStep("3", "形成研究路径", "先定范围，再查询、分析和沉淀")
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("在新对话中开始")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("暂不研究")
        }
    }
}

@Composable
private fun ResearchStep(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Rust, shape = CircleShape, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = InkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryScreen(
    state: AssistantUiState,
    onRefresh: () -> Unit,
    onResearch: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var focusId by remember(state.graph.nodes) { mutableStateOf(state.graph.focusId) }
    var selectedNode by remember { mutableStateOf<String?>(null) }
    val filteredRelations = state.relations.filter { relation ->
        val matchesQuery = query.isBlank() ||
            listOf(relation.subject, relation.predicate, relation.objectValue, relation.scopeLabel)
                .any { it.contains(query, true) }
        val matchesFilter = when (filter) {
            "当前可用" -> relation.recallable
            "资料" -> relation.scopeLabel.startsWith("资料")
            "未纳入自动回忆" -> !relation.recallable
            "经历" -> false
            else -> true
        }
        matchesQuery && matchesFilter
    }
    val filteredClaims = state.claims.filter { claim ->
        val matchesQuery = query.isBlank() || claim.text.contains(query, true) || claim.scopeLabel.contains(query, true)
        val matchesFilter = when (filter) {
            "当前可用" -> claim.recallable
            "资料" -> claim.scopeLabel.startsWith("资料")
            "未纳入自动回忆" -> !claim.recallable
            "经历", "全部" -> true
            else -> false
        }
        matchesQuery && matchesFilter
    }
    val availableCount = state.relations.count { it.recallable }
    val isolatedCount = state.relations.count { it.isolated || !it.recallable }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(availableCount.toString(), "当前可用", Modifier.weight(1f))
                StatCard(isolatedCount.toString(), "未纳入", Modifier.weight(1f))
                StatCard(state.claims.size.toString(), "经历", Modifier.weight(1f))
                StatCard(state.pendingRaw.toString(), "未消费", Modifier.weight(1f))
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜节点、谓语、经历…") },
                leadingIcon = { Text("⌕") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("全部", "当前可用", "资料", "未纳入自动回忆", "经历").forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option) },
                    )
                }
            }
        }
        if (state.graph.nodes.isNotEmpty()) item {
            Surface(
                color = PaperRaised,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("焦点一跳", style = MaterialTheme.typography.titleMedium)
                            Text("点击聚焦 · 长按扩展研究 · 双指缩放", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            onClick = {
                                focusId = state.graph.nodes.firstOrNull {
                                    it.label == "我" || it.label == "用户"
                                }?.id ?: state.graph.nodes.firstOrNull()?.id
                            },
                        ) { Text("复位") }
                    }
                    GraphWidget(
                        spec = state.graph.copy(focusId = focusId),
                        modifier = Modifier.fillMaxWidth(),
                        onFocus = {
                            focusId = it
                            selectedNode = it
                        },
                        onResearch = { nodeId ->
                            state.graph.nodes.firstOrNull { it.id == nodeId }?.label?.let(onResearch)
                        },
                    )
                }
            }
        }
        if (state.graph.nodes.isEmpty()) item {
            Surface(color = PaperRaised, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前对话还没有可自动回忆的记忆", style = MaterialTheme.typography.titleMedium)
                    Text("库存里的历史隔离边不会画进这张图，也不会进入新对话。", color = InkMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item { SectionTitle("活跃关系", "${filteredRelations.size} 条") }
        items(filteredRelations) { relation ->
            RelationRow(
                edge = Triple(relation.subject, relation.predicate, relation.objectValue),
                caption = if (relation.recallable) relation.scopeLabel else "${relation.scopeLabel} · 未纳入自动回忆",
                onClick = {
                    val target = state.graph.nodes.firstOrNull { it.label == relation.objectValue }?.id
                        ?: state.graph.nodes.firstOrNull { it.label == relation.subject }?.id
                    focusId = target
                    selectedNode = target
                },
            )
        }
        item { SectionTitle("经历与判断", "${filteredClaims.size} 条 Claim") }
        items(filteredClaims) { claim ->
            ClaimCard(
                claim.text,
                buildString {
                    append(claim.scopeLabel)
                    append(" · 置信度 ${(claim.confidence * 100).toInt()}%")
                    if (!claim.recallable) append(" · 未纳入自动回忆")
                },
            )
        }
    }
    selectedNode?.let { nodeId ->
        val node = state.graph.nodes.firstOrNull { it.id == nodeId }
        if (node != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedNode = null },
                containerColor = PaperRaised,
            ) {
                NodeDetailSheet(
                    label = node.label,
                    relations = state.relations.filter {
                        it.subject == node.label || it.objectValue == node.label
                    },
                    claims = state.claims.filter { it.text.contains(node.label, true) },
                    onFocus = {
                        focusId = node.id
                        selectedNode = null
                    },
                )
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = PaperRaised, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = Rust)
            Text(label, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(trailing, color = InkMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RelationRow(
    edge: Triple<String, String, String>,
    caption: String,
    onClick: () -> Unit,
) {
    Surface(
        color = PaperRaised,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(edge.first, fontWeight = FontWeight.SemiBold)
                    Text("  ${edge.second}  ", color = Rust, style = MaterialTheme.typography.bodySmall)
                    Text(edge.third)
                }
                Text(caption, color = InkMuted, style = MaterialTheme.typography.labelSmall)
            }
            Text("›", color = InkMuted)
        }
    }
}

@Composable
private fun NodeDetailSheet(
    label: String,
    relations: List<relay.assistant.state.MemoryRelationUi>,
    claims: List<relay.assistant.state.MemoryClaimUi>,
    onFocus: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${relations.size} 条关系 · ${claims.size} 条相关经历",
            color = InkMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        relations.take(8).forEach { relation ->
            Text(
                "${relation.subject}  ${relation.predicate}  ${relation.objectValue}",
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            )
            HorizontalDivider(color = Line)
        }
        claims.take(4).forEach { claim ->
            Text(claim.text, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        Button(onClick = onFocus, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("以此为中心")
        }
    }
}

@Composable
private fun ClaimCard(text: String, source: String) {
    Surface(
        color = RustSoft,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("经历", color = Rust, style = MaterialTheme.typography.labelLarge)
            Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            Text(source, Modifier.padding(top = 8.dp), color = InkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AssistantUiState,
    onApiKeyChange: (String) -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← 返回对话") }
            Text("让 Relay 适合你", style = MaterialTheme.typography.headlineLarge)
            Text(
                "模型负责思考，记忆留在你的设备上。",
                color = InkMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            SettingsCard("模型") {
                Text("DeepSeek Chat", style = MaterialTheme.typography.titleMedium)
                Text("支持流式回复与工具调用", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
        item {
            SettingsCard("记忆") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.memoryEnabled) "长期记忆已开启" else "长期记忆已暂停",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("原文先落盘，4 回合或空闲后学习", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.memoryEnabled, onCheckedChange = onMemoryEnabledChange)
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                Text(
                    "${state.relations.size} 条关系 · ${state.claims.size} 条经历 · ${state.pendingRaw} 条待学习",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            SettingsCard("关于") {
                Text("Relay Assistant 0.1", style = MaterialTheme.typography.titleMedium)
                Text("端侧记忆 · 原生组件 · 可追溯过程", color = InkMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PaperRaised),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Rust, style = MaterialTheme.typography.labelLarge)
            Column(Modifier.padding(top = 12.dp), content = content)
        }
    }
}

private const val MAX_TEXT_ATTACHMENT_CHARS = 20_000
