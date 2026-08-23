package relay.uikit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object WidgetParser {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun parse(raw: String): WidgetSpec {
        val objectValue = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrElse {
            return FallbackSpec("组件 JSON 无法解析", raw.compact())
        }
        val version = objectValue["version"]?.jsonPrimitive?.intOrNull
        if (version != CURRENT_WIDGET_VERSION) {
            return FallbackSpec("不支持的组件版本 ${version ?: "missing"}", raw.compact())
        }
        val type = objectValue["type"]?.jsonPrimitive?.content
            ?: return FallbackSpec("组件缺少 type", raw.compact())
        val decoded = runCatching {
            when (type) {
                "markdown" -> json.decodeFromJsonElement(MarkdownSpec.serializer(), objectValue)
                "kv" -> json.decodeFromJsonElement(KvSpec.serializer(), objectValue)
                "table" -> json.decodeFromJsonElement(TableSpec.serializer(), objectValue)
                "card" -> json.decodeFromJsonElement(CardSpec.serializer(), objectValue)
                "choice_form" -> json.decodeFromJsonElement(ChoiceFormSpec.serializer(), objectValue)
                "chart" -> json.decodeFromJsonElement(ChartSpec.serializer(), objectValue)
                "graph" -> json.decodeFromJsonElement(GraphSpec.serializer(), objectValue)
                "file" -> json.decodeFromJsonElement(FileSpec.serializer(), objectValue)
                "list" -> json.decodeFromJsonElement(ListSpec.serializer(), objectValue)
                "image" -> json.decodeFromJsonElement(ImageSpec.serializer(), objectValue)
                else -> return FallbackSpec("未知组件类型 $type", raw.compact())
            }
        }.getOrElse { error ->
            return FallbackSpec("组件参数非法: ${error.message.orEmpty()}", raw.compact())
        }
        return validate(decoded) ?: decoded
    }

    fun validate(spec: WidgetSpec): FallbackSpec? {
        val reason = when (spec) {
            is MarkdownSpec -> "Markdown 为空".takeIf { spec.markdown.isBlank() }
            is KvSpec -> "KV 没有条目".takeIf { spec.items.isEmpty() || spec.items.any { it.key.isBlank() } }
            is TableSpec -> "表格列或行不合法".takeIf {
                spec.columns.isEmpty() || spec.columns.any { it.isBlank() } ||
                    spec.rows.any { it.size != spec.columns.size }
            }
            is CardSpec -> "卡片标题为空".takeIf { spec.title.isBlank() }
            is ChoiceFormSpec -> validateChoiceForm(spec)
            is ChartSpec -> validateChart(spec)
            is GraphSpec -> "图谱节点或边不合法".takeIf {
                spec.nodes.map { it.id }.toSet().size != spec.nodes.size ||
                    spec.nodes.any { it.id.isBlank() || it.label.isBlank() } ||
                    spec.edges.any { edge ->
                        spec.nodes.none { it.id == edge.source } ||
                            spec.nodes.none { it.id == edge.target } ||
                            edge.predicate.isBlank()
                    }
            }
            is FileSpec -> "产物引用不合法".takeIf {
                spec.artifactId.isBlank() || spec.artifactVersion < 1 || spec.name.isBlank()
            }
            is ListSpec -> "列表为空".takeIf { spec.items.isEmpty() }
            is ImageSpec -> "图片 URI 或替代文字为空".takeIf { spec.uri.isBlank() || spec.alt.isBlank() }
            is FallbackSpec -> null
        }
        return reason?.let { FallbackSpec(it, spec.summary(), spec.sourceId) }
    }

    private fun validateChoiceForm(spec: ChoiceFormSpec): String? {
        val questionIds = spec.questions.map(ChoiceQuestion::id)
        return when {
            spec.title.isBlank() || spec.questions.isEmpty() -> "选择表单标题或问题为空"
            questionIds.any(String::isBlank) || questionIds.toSet().size != questionIds.size ->
                "选择表单问题 ID 不合法"
            spec.questions.any { question ->
                val optionIds = question.options.map(ChoiceOption::id)
                question.title.isBlank() || question.options.isEmpty() ||
                    optionIds.any(String::isBlank) || optionIds.toSet().size != optionIds.size ||
                    question.options.any { it.label.isBlank() }
            } -> "选择表单选项不合法"
            spec.submittedAnswers != null && spec.questions.any {
                it.required && spec.submittedAnswers[it.id].isNullOrEmpty()
            } -> "选择表单必答题未完成"
            spec.submittedAnswers?.any { (questionId, optionIds) ->
                val question = spec.questions.firstOrNull { it.id == questionId } ?: return@any true
                optionIds.isEmpty() && question.required ||
                    question.kind == ChoiceKind.SINGLE && optionIds.size > 1 ||
                    optionIds.any { answerId -> question.options.none { it.id == answerId } }
            } == true -> "选择表单答案不合法"
            else -> null
        }
    }

    private fun validateChart(spec: ChartSpec): String? {
        val series = spec.series.ifEmpty {
            return "图表没有有效数据".takeIf {
                spec.points.isEmpty() || spec.points.any { !it.value.isFinite() || it.label.isBlank() }
            }
        }
        val categories = series.firstOrNull()?.points?.map(ChartPoint::label).orEmpty()
        return when {
            series.any { it.name.isBlank() || it.kind == ChartKind.PIE || it.points.isEmpty() } ->
                "高级图表系列不合法"
            series.map { it.name }.toSet().size != series.size ->
                "图表系列名称重复"
            series.any { item ->
                item.points.map(ChartPoint::label) != categories ||
                    item.points.any { it.label.isBlank() || !it.value.isFinite() }
            } -> "图表系列类别不一致"
            series.filter { it.kind == ChartKind.BAR }.map { it.axis }.distinct().size > 1 ->
                "柱状系列不能跨越两个轴"
            series.filter { it.kind == ChartKind.LINE }.map { it.axis }.distinct().size > 1 ->
                "折线系列不能跨越两个轴"
            spec.stacked && series.count { it.kind == ChartKind.BAR } < 2 ->
                "堆叠图至少需要两个柱状系列"
            spec.annotations.any {
                it.label.isBlank() || it.pointLabel !in categories || it.value?.isFinite() == false
            } -> "图表标注不合法"
            else -> null
        }
    }

    private fun String.compact(): String = replace(Regex("\\s+"), " ").take(220)
}
