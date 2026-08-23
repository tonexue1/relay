package relay.uikit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DisplayMode { INLINE, BLOCK, CANVAS }

@Serializable
sealed interface WidgetSpec {
    val version: Int
    val sourceId: String?
    val display: DisplayMode
    fun summary(): String
}

@Serializable
@SerialName("markdown")
data class MarkdownSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.INLINE,
    val markdown: String,
) : WidgetSpec {
    override fun summary(): String = markdown.replace(Regex("[#*_`>\\[\\]]"), "")
        .replace(Regex("\\s+"), " ").trim().take(240)
}

@Serializable
data class KeyValue(val key: String, val value: String)

@Serializable
@SerialName("kv")
data class KvSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.INLINE,
    val title: String = "",
    val items: List<KeyValue>,
) : WidgetSpec {
    override fun summary(): String = listOfNotNull(title.takeIf { it.isNotBlank() }, items.joinToString { "${it.key}: ${it.value}" })
        .joinToString(" · ").take(240)
}

@Serializable
@SerialName("table")
data class TableSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val title: String = "",
    val columns: List<String>,
    val rows: List<List<String>>,
) : WidgetSpec {
    override fun summary(): String = buildString {
        if (title.isNotBlank()) append("$title · ")
        append("${rows.size} 行")
        rows.firstOrNull()?.let { append(" · "); append(columns.zip(it).joinToString { (key, value) -> "$key: $value" }) }
    }.take(240)
}

@Serializable
@SerialName("card")
data class CardSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val title: String,
    val subtitle: String = "",
    val body: String = "",
) : WidgetSpec {
    override fun summary(): String = listOf(title, subtitle, body).filter { it.isNotBlank() }.joinToString(" · ").take(240)
}

@Serializable
enum class ChoiceKind { SINGLE, MULTI }

@Serializable
data class ChoiceOption(
    val id: String,
    val label: String,
    val description: String = "",
    val recommended: Boolean = false,
)

@Serializable
data class ChoiceQuestion(
    val id: String,
    val kind: ChoiceKind = ChoiceKind.SINGLE,
    val title: String,
    val hint: String = "",
    val required: Boolean = true,
    val options: List<ChoiceOption>,
)

@Serializable
@SerialName("choice_form")
data class ChoiceFormSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val title: String,
    val taskAnchor: String = "",
    val submitLabel: String = "提交选择",
    val questions: List<ChoiceQuestion>,
    val submittedAnswers: Map<String, List<String>>? = null,
) : WidgetSpec {
    override fun summary(): String {
        val answers = submittedAnswers ?: return "$title · ${questions.size} 个问题"
        val labels = questions.mapNotNull { question ->
            answers[question.id]?.mapNotNull { answerId ->
                question.options.firstOrNull { it.id == answerId }?.label
            }?.takeIf { it.isNotEmpty() }?.joinToString("、")
        }
        return listOf(title, "已提交", labels.joinToString("；"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(240)
    }
}

@Serializable
enum class ChartKind { BAR, LINE, PIE }

@Serializable
data class ChartPoint(val label: String, val value: Double)

@Serializable
enum class ChartAxis { START, END }

@Serializable
data class ChartSeries(
    val name: String,
    val kind: ChartKind,
    val points: List<ChartPoint>,
    val axis: ChartAxis = ChartAxis.START,
)

@Serializable
data class ChartAnnotation(
    val label: String,
    val pointLabel: String,
    val value: Double? = null,
    val axis: ChartAxis = ChartAxis.START,
)

@Serializable
@SerialName("chart")
data class ChartSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.CANVAS,
    val title: String = "",
    val kind: ChartKind = ChartKind.BAR,
    val points: List<ChartPoint> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    val stacked: Boolean = false,
    val startAxisTitle: String = "",
    val endAxisTitle: String = "",
    val annotations: List<ChartAnnotation> = emptyList(),
) : WidgetSpec {
    override fun summary(): String = listOfNotNull(
        title.takeIf { it.isNotBlank() },
        if (series.isEmpty()) kind.name.lowercase() else series.joinToString { it.name },
        if (series.isEmpty()) {
            points.joinToString { "${it.label}: ${it.value}" }
        } else {
            series.joinToString { item -> "${item.name}: ${item.points.joinToString { "${it.label} ${it.value}" }}" }
        },
    )
        .joinToString(" · ").take(240)
}

@Serializable
data class GraphNode(val id: String, val label: String, val kind: String = "")

@Serializable
data class GraphEdge(
    val source: String,
    val predicate: String,
    val target: String,
    val chapter: Int? = null,
)

@Serializable
@SerialName("graph")
data class GraphSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.CANVAS,
    val title: String = "",
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val claims: List<String> = emptyList(),
    val focusId: String? = null,
    val showPredicates: Boolean = false,
    val chapter: Int? = null,
) : WidgetSpec {
    override fun summary(): String = buildString {
        if (title.isNotBlank()) append("$title · ")
        append("${nodes.size} 个节点，${edges.size} 条关系")
        if (claims.isNotEmpty()) append("，${claims.size} 条 Claim")
    }.take(240)
}

@Serializable
@SerialName("file")
data class FileSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val artifactId: String,
    val artifactVersion: Int,
    val name: String,
    val mime: String,
    val summaryText: String = "",
    val status: String = "ready",
) : WidgetSpec {
    override fun summary(): String = "$name · $mime · $status${summaryText.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}".take(240)
}

@Serializable
@SerialName("list")
data class ListSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val title: String = "",
    val items: List<String>,
) : WidgetSpec {
    override fun summary(): String = listOfNotNull(title.takeIf { it.isNotBlank() }, items.joinToString()).joinToString(" · ").take(240)
}

@Serializable
@SerialName("image")
data class ImageSpec(
    override val version: Int = CURRENT_WIDGET_VERSION,
    override val sourceId: String? = null,
    override val display: DisplayMode = DisplayMode.BLOCK,
    val uri: String,
    val alt: String,
    val caption: String = "",
) : WidgetSpec {
    override fun summary(): String = listOf(alt, caption).filter { it.isNotBlank() }.joinToString(" · ").take(240)
}

@Serializable
@SerialName("fallback")
data class FallbackSpec(
    val reason: String,
    val rawSummary: String,
    override val sourceId: String? = null,
) : WidgetSpec {
    override val version: Int = CURRENT_WIDGET_VERSION
    override val display: DisplayMode = DisplayMode.BLOCK
    override fun summary(): String = listOf(reason, rawSummary).filter { it.isNotBlank() }.joinToString(" · ").take(240)
}

const val CURRENT_WIDGET_VERSION = 1
