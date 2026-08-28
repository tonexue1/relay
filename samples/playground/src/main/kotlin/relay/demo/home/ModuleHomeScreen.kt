package relay.demo.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class RelayModule(
    val artifact: String,
    val summary: String,
    val available: Boolean,
) {
    Llm(
        artifact = "relay/llm",
        summary = "统一模型调用:Provider 抽象、拦截器链、流式与 tool calling、token 预估",
        available = true,
    ),
    OnDevice(
        artifact = "relay/ondevice",
        summary = "端侧推理 Provider:llama.cpp via JNI,与云端 Provider 同一接口",
        available = true,
    ),
    AgentCore(
        artifact = "relay/agent-core",
        summary = "Agent 运行时:agent loop、工具注册与调度、会话与上下文管理",
        available = true,
    ),
    Orchestra(
        artifact = "relay/orchestra",
        summary = "多 Agent 编排:先体验 GroupChat 圆桌（Yield，没有综合者）",
        available = true,
    ),
    Memory(
        artifact = "relay/memory",
        summary = "抽完存，再按点时召回。没有知识图。",
        available = true,
    ),
    UiKit(
        artifact = "relay/ui-kit",
        summary = "Compose 原生组件、版本化 Markdown/HTML 产物与 WebView 强沙箱",
        available = true,
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleHomeScreen(onOpenModule: (RelayModule) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay 调试台") },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "端云协同 Agent 运行时,按模块分屏自测。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(RelayModule.entries) { module ->
                ModuleCard(module = module, onClick = { onOpenModule(module) })
            }
        }
    }
}

@Composable
private fun ModuleCard(module: RelayModule, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = module.available,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = module.artifact,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = module.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SuggestionChip(
                onClick = onClick,
                enabled = module.available,
                label = { Text(if (module.available) "打开调试屏" else "待实现") },
                colors = SuggestionChipDefaults.suggestionChipColors(),
            )
        }
    }
}
