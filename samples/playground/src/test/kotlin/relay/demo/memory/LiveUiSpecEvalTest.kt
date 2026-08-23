package relay.demo.memory

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.AgentEvent
import relay.artifacts.FileArtifactRepository
import relay.llm.provider.DeepSeek
import relay.uikit.UiToolNames
import relay.uikit.uiArtifactTools
import relay.uikit.widgetFromToolCall
import relay.uikit.FallbackSpec

class LiveUiSpecEvalTest {
    @Test
    fun uiToolSelectionAndArgumentsMeetGate() = runBlocking {
        assumeTrue(System.getProperty("relay.liveUiEval").orEmpty().isNotBlank())
        val key = System.getenv("RELAY_DEEPSEEK_API_KEY")
        assumeTrue(!key.isNullOrBlank())
        val repository = FileArtifactRepository(Files.createTempDirectory("ui-eval").toFile())
        val agent = Agent(
            provider = DeepSeek.provider(
                apiKey = key!!,
                httpClient = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build(),
            ),
            config = AgentConfig(
                model = DeepSeek.CHAT,
                systemPrompt = "按用户要求选择最合适的 UI 或产物工具。需要结构化显示时必须调用工具，不要用纯文本代替。",
                maxTurns = 4,
                timeoutMillis = 90_000,
            ),
            tools = uiArtifactTools(repository),
        )
        var truePositive = 0
        var falsePositive = 0
        var falseNegative = 0
        var schemaValid = 0
        var dataCorrect = 0
        val failures = mutableListOf<String>()
        UiEvalCorpus.samples.forEach { sample ->
            val calls = agent.prompt(sample.prompt).toList()
                .filterIsInstance<AgentEvent.ToolExecutionStart>()
                .map { it.call }
            val expected = calls.firstOrNull { it.name == sample.tool }
            if (expected == null) {
                falseNegative++
                if (calls.isNotEmpty()) falsePositive += calls.count { it.name != sample.tool }
                failures += "${sample.id}: expected ${sample.tool}, got ${calls.map { it.name }}"
            } else {
                truePositive++
                val valid = runCatching {
                    if (sample.tool in UiToolNames.renderers) {
                        widgetFromToolCall(sample.tool, expected.argumentsJson) !is FallbackSpec
                    } else {
                        expected.argumentsJson.contains("\"name\"") && expected.argumentsJson.contains("\"body\"")
                    }
                }.getOrDefault(false)
                if (valid) schemaValid++
                if (valid && expected.argumentsJson.contains(sample.mustContain, ignoreCase = true)) dataCorrect++
            }
        }
        val total = UiEvalCorpus.samples.size.toDouble()
        val precision = truePositive.toDouble() / (truePositive + falsePositive).coerceAtLeast(1)
        val recall = truePositive.toDouble() / (truePositive + falseNegative).coerceAtLeast(1)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val report = "schema=${schemaValid / total} data=${dataCorrect / total} f1=$f1\n${failures.joinToString("\n")}"
        assertTrue(report, schemaValid / total >= 0.95)
        assertTrue(report, dataCorrect / total >= 0.90)
        assertTrue(report, f1 >= 0.80)
    }
}

private data class UiEvalSample(
    val id: String,
    val prompt: String,
    val tool: String,
    val mustContain: String,
)

private object UiEvalCorpus {
    val samples = listOf(
        UiEvalSample("kv-weather", "用键值卡显示城市北京、温度26度。", "render_kv", "北京"),
        UiEvalSample("kv-status", "把环境端侧、状态正常显示成 KV。", "render_kv", "正常"),
        UiEvalSample("table-tasks", "用表格列出合同完成、预览进行中。", "render_table", "合同"),
        UiEvalSample("table-prices", "做表格：苹果5元，梨4元。", "render_table", "苹果"),
        UiEvalSample("chart-bar", "用柱状图显示周一12、周二18。", "render_chart", "12"),
        UiEvalSample("chart-line", "用折线图显示一月3、二月7。", "render_chart", "二月"),
        UiEvalSample("chart-pie", "用饼图显示安卓60、iOS40。", "render_chart", "60"),
        UiEvalSample("card", "做卡片，标题发布，正文今天上线。", "render_card", "发布"),
        UiEvalSample("list", "用列表展示验证、发布、复盘。", "render_list", "复盘"),
        UiEvalSample("markdown", "在聊天中用短 Markdown 写二级标题今日。", "render_markdown", "今日"),
        UiEvalSample("graph", "画小图：Relay 运行于 Android。", "render_graph", "Relay"),
        UiEvalSample("graph-two", "用关系图显示林晚认识顾舟。", "render_graph", "林晚"),
        UiEvalSample("html", "生成名为 dashboard.html 的 HTML 文件，内容有仪表盘标题。", "write_html_artifact", "dashboard.html"),
        UiEvalSample("html-tabs", "生成 tabs.html，做三个可切换标签页。", "write_html_artifact", "tabs.html"),
        UiEvalSample("html-form", "生成 survey.html，包含端侧问卷表单。", "write_html_artifact", "survey.html"),
        UiEvalSample("md", "生成 notes.md，一级标题会议纪要。", "write_markdown_artifact", "notes.md"),
        UiEvalSample("md-long", "生成 report.md，写项目周报。", "write_markdown_artifact", "report.md"),
        UiEvalSample("image", "用图片组件显示这个 data:image/png;base64,AA，替代文字图标。", "render_image", "图标"),
        UiEvalSample("kv-user", "用 KV 显示姓名林晚、身份编辑。", "render_kv", "林晚"),
        UiEvalSample("table-scores", "用表格显示小明90分、小红95分。", "render_table", "小红"),
    )
}
