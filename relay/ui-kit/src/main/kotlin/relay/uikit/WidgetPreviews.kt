package relay.uikit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Widgets narrow", widthDp = 320, showBackground = true)
@Preview(name = "Widgets dark", widthDp = 420, showBackground = true, uiMode = 0x20)
@Composable
private fun WidgetCatalogPreview() {
    MaterialTheme {
        Column(Modifier.padding(12.dp)) {
            WidgetHost(KvSpec(title = "状态", items = listOf(KeyValue("环境", "端侧"), KeyValue("结果", "正常"))))
            WidgetHost(TableSpec(title = "任务", columns = listOf("名称", "状态"), rows = listOf(listOf("合同", "完成"))))
            WidgetHost(ChartSpec(title = "调用", kind = ChartKind.BAR, points = listOf(ChartPoint("一", 12.0), ChartPoint("二", 18.0))))
        }
    }
}

@Preview(name = "Graph", widthDp = 420, heightDp = 560, showBackground = true)
@Composable
private fun GraphPreview() {
    MaterialTheme {
        WidgetHost(
            GraphSpec(
                title = "记忆",
                nodes = listOf(GraphNode("relay", "Relay"), GraphNode("android", "Android")),
                edges = listOf(GraphEdge("relay", "运行于", "android")),
                claims = listOf("未来可能支持桌面端"),
                focusId = "relay",
            ),
            Modifier.padding(12.dp),
        )
    }
}
