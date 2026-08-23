package relay.demo.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import relay.uikit.CardSpec
import relay.uikit.ChartKind
import relay.uikit.ChartPoint
import relay.uikit.ChartSpec
import relay.uikit.GraphEdge
import relay.uikit.GraphNode
import relay.uikit.GraphSpec
import relay.uikit.HtmlArtifactPreview
import relay.uikit.KeyValue
import relay.uikit.KvSpec
import relay.uikit.ListSpec
import relay.uikit.MarkdownSpec
import relay.uikit.TableSpec
import relay.uikit.WidgetHost
import relay.uikit.WidgetParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiKitScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay UI-kit") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("静态样例不调用模型；助手页的“UI 演示”样例用于验证 function calling。")
            }
            item { WidgetHost(MarkdownSpec(markdown = "## 原生组件\n合同、降级和展示模式已接通。")) }
            item {
                WidgetHost(
                    KvSpec(
                        title = "运行状态",
                        items = listOf(KeyValue("渲染", "Compose"), KeyValue("沙箱", "WebView")),
                    ),
                )
            }
            item {
                WidgetHost(
                    TableSpec(
                        title = "交付",
                        columns = listOf("模块", "状态"),
                        rows = listOf(listOf("ui-kit", "完成"), listOf("artifacts", "完成")),
                    ),
                )
            }
            item {
                WidgetHost(
                    ChartSpec(
                        title = "调用趋势",
                        kind = ChartKind.LINE,
                        points = listOf(ChartPoint("一", 8.0), ChartPoint("二", 14.0), ChartPoint("三", 11.0)),
                    ),
                )
            }
            item {
                WidgetHost(
                    ChartSpec(
                        title = "模块耗时",
                        kind = ChartKind.BAR,
                        points = listOf(
                            ChartPoint("解析", 32.0),
                            ChartPoint("布局", 61.0),
                            ChartPoint("首帧", 118.0),
                        ),
                    ),
                )
            }
            item {
                WidgetHost(
                    ChartSpec(
                        title = "内容占比",
                        kind = ChartKind.PIE,
                        points = listOf(
                            ChartPoint("Markdown", 35.0),
                            ChartPoint("原生", 40.0),
                            ChartPoint("HTML", 25.0),
                        ),
                    ),
                )
            }
            item {
                WidgetHost(
                    ListSpec(
                        title = "本地交互",
                        items = listOf("表格行详情", "组件全屏展开", "图谱焦点切换", "HTML 元素标注"),
                    ),
                )
            }
            item {
                WidgetHost(
                    GraphSpec(
                        title = "查询结果",
                        nodes = listOf(GraphNode("relay", "Relay"), GraphNode("android", "Android")),
                        edges = listOf(GraphEdge("relay", "运行于", "android")),
                        focusId = "relay",
                    ),
                )
            }
            item {
                WidgetHost(CardSpec(title = "HTML 强沙箱", body = "下方 fixture 允许内联交互，但网络和原生能力被禁用。"))
                HtmlArtifactPreview(
                    HTML_FIXTURE,
                    Modifier.fillMaxWidth().height(280.dp),
                )
            }
            item {
                WidgetHost(
                    WidgetParser.parse(
                        """{"type":"table","version":1,"columns":["名称","状态"],"rows":[["缺列"]]}""",
                    ),
                )
                Text("上面是故意损坏的 spec，用于验证聊天不会崩溃而是显示 fallback。")
            }
        }
    }
}

private const val HTML_FIXTURE = """
<!doctype html><html><head><style>
body{font:16px system-ui;margin:0;padding:24px;background:linear-gradient(135deg,#f7e8df,#e4efe9)}
.card{padding:20px;border-radius:18px;background:#fffc;box-shadow:0 12px 36px #4322}
button{padding:10px 14px;border:0;border-radius:10px;background:#2d5a3d;color:white}
</style></head><body><div class="card"><h2>端侧 HTML</h2><p id="count">点击次数 0</p>
<button onclick="const n=+(this.dataset.n||0)+1;this.dataset.n=n;document.getElementById('count').textContent='点击次数 '+n">交互</button>
</div></body></html>
"""
