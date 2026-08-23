package relay.uikit

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.FunTool
import relay.agent.Tool
import relay.artifacts.ArtifactRef
import relay.artifacts.ArtifactRepository
import relay.artifacts.ArtifactValidator

object UiToolNames {
    const val MARKDOWN = "render_markdown"
    const val KV = "render_kv"
    const val TABLE = "render_table"
    const val CARD = "render_card"
    const val CHOICE_FORM = "render_choice_form"
    const val CHART = "render_chart"
    const val GRAPH = "render_graph"
    const val LIST = "render_list"
    const val IMAGE = "render_image"
    const val WRITE_MARKDOWN = "write_markdown_artifact"
    const val WRITE_HTML = "write_html_artifact"
    const val READ_ARTIFACT = "read_artifact"
    const val REVISE_ARTIFACT = "revise_artifact"

    val renderers = setOf(MARKDOWN, KV, TABLE, CARD, CHOICE_FORM, CHART, GRAPH, LIST, IMAGE)
    val writers = setOf(WRITE_MARKDOWN, WRITE_HTML)
}

fun uiArtifactTools(
    repository: ArtifactRepository,
    includeHtml: Boolean = true,
): List<Tool> = buildList {
    add(rendererTool(UiToolNames.MARKDOWN, "在聊天中渲染短 Markdown。", markdownSchema))
    add(rendererTool(UiToolNames.KV, "渲染少量键值信息。", kvSchema))
    add(rendererTool(UiToolNames.TABLE, "渲染必须破泡的结构化表格。", tableSchema))
    add(rendererTool(UiToolNames.CARD, "渲染标题、摘要和正文卡片。", cardSchema))
    add(
        rendererTool(
            UiToolNames.CHOICE_FORM,
            "向用户提出一个或多个需要显式提交的单选或多选问题；不要替用户预选。",
            choiceFormSchema,
        ),
    )
    add(rendererTool(UiToolNames.CHART, "渲染柱状图、折线图或饼图。", chartSchema))
    add(rendererTool(UiToolNames.GRAPH, "渲染小型查询结果图谱；不要传全数据库。", graphSchema))
    add(rendererTool(UiToolNames.LIST, "渲染列表。", listSchema))
    add(rendererTool(UiToolNames.IMAGE, "渲染 data URI 图片。", imageSchema))
    add(writeArtifactTool(UiToolNames.WRITE_MARKDOWN, "text/markdown", repository))
    if (includeHtml) add(writeArtifactTool(UiToolNames.WRITE_HTML, "text/html", repository))
    add(
        FunTool(
            name = UiToolNames.READ_ARTIFACT,
            description = "读取产物某个版本的源码，用于修订。",
            parameters = artifactRefSchema,
        ) { raw ->
            val args = Json.parseToJsonElement(raw) as JsonObject
            val ref = ArtifactRef(
                args.getValue("artifactId").jsonPrimitive.content,
                args.getValue("version").jsonPrimitive.content.toInt(),
            )
            repository.read(ref)?.body ?: error("Artifact not found: $ref")
        },
    )
    add(
        FunTool(
            name = UiToolNames.REVISE_ARTIFACT,
            description = "基于已有版本写入一个不可变的新版本。",
            parameters = reviseSchema,
        ) { raw ->
            val args = Json.decodeFromString<ReviseArgs>(raw)
            Json.encodeToString(
                repository.revise(
                    artifactId = args.artifactId,
                    baseVersion = args.baseVersion,
                    body = args.body,
                    summary = args.summary,
                    validation = ArtifactValidator.validate(
                        repository.read(ArtifactRef(args.artifactId, args.baseVersion))?.metadata?.mime
                            ?: error("Artifact not found"),
                        args.body,
                    ).also { require(it.valid) { it.errors.joinToString() } },
                ),
            )
        },
    )
}

private fun rendererTool(name: String, description: String, schema: JsonObject): Tool =
    FunTool(name, description, schema) { raw ->
        val spec = widgetFromToolCall(name, raw)
        val fallback = WidgetParser.validate(spec)
        require(fallback == null) { fallback?.reason.orEmpty() }
        """{"ok":true,"summary":${Json.encodeToString(spec.summary())}}"""
    }

private fun writeArtifactTool(name: String, mime: String, repository: ArtifactRepository): Tool =
    FunTool(name, "生成 UTF-8 单文件 ${if (mime == "text/html") "HTML" else "Markdown"} 产物。", writeSchema) { raw ->
        val args = Json.decodeFromString<WriteArgs>(raw)
        val validation = ArtifactValidator.validate(mime, args.body)
        require(validation.valid) { validation.errors.joinToString() }
        Json.encodeToString(repository.create(args.name, mime, args.body, args.summary, validation))
    }

fun widgetFromToolCall(name: String, argumentsJson: String): WidgetSpec {
    val objectValue = Json.parseToJsonElement(argumentsJson) as JsonObject
    val type = when (name) {
        UiToolNames.MARKDOWN -> "markdown"
        UiToolNames.KV -> "kv"
        UiToolNames.TABLE -> "table"
        UiToolNames.CARD -> "card"
        UiToolNames.CHOICE_FORM -> "choice_form"
        UiToolNames.CHART -> "chart"
        UiToolNames.GRAPH -> "graph"
        UiToolNames.LIST -> "list"
        UiToolNames.IMAGE -> "image"
        else -> error("Not a UI tool: $name")
    }
    val enriched = JsonObject(
        objectValue +
            ("type" to Json.parseToJsonElement("\"$type\"")) +
            ("version" to Json.parseToJsonElement("1")),
    )
    return WidgetParser.parse(enriched.toString())
}

@Serializable
private data class WriteArgs(val name: String, val body: String, val summary: String = "")

@Serializable
private data class ReviseArgs(
    val artifactId: String,
    val baseVersion: Int,
    val body: String,
    val summary: String = "",
)

private fun objectSchema(required: List<String>, properties: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { required.forEach { add(Json.parseToJsonElement("\"$it\"")) } }
    put("properties", properties)
}

private fun stringProp(description: String = "") = buildJsonObject {
    put("type", "string")
    if (description.isNotBlank()) put("description", description)
}

private val markdownSchema = objectSchema(listOf("markdown"), buildJsonObject {
    put("markdown", stringProp("短 Markdown 内容"))
})
private val kvSchema = objectSchema(listOf("items"), buildJsonObject {
    put("title", stringProp())
    putJsonObject("items") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonArray("required") {
                add(Json.parseToJsonElement("\"key\""))
                add(Json.parseToJsonElement("\"value\""))
            }
            putJsonObject("properties") {
                put("key", stringProp())
                put("value", stringProp())
            }
        }
    }
})
private val tableSchema = objectSchema(listOf("columns", "rows"), buildJsonObject {
    putJsonObject("columns") {
        put("type", "array")
        put("items", stringProp())
    }
    putJsonObject("rows") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "array")
            put("items", stringProp())
        }
    }
    put("title", stringProp())
})
private val cardSchema = objectSchema(listOf("title"), buildJsonObject {
    put("title", stringProp())
    put("subtitle", stringProp())
    put("body", stringProp())
})
private val choiceOptionSchema = objectSchema(listOf("id", "label"), buildJsonObject {
    put("id", stringProp("选项内稳定且唯一的短 ID"))
    put("label", stringProp("用户看到的选项标题"))
    put("description", stringProp("可选的一行解释"))
    putJsonObject("recommended") { put("type", "boolean") }
})
private val choiceQuestionSchema = objectSchema(listOf("id", "kind", "title", "options"), buildJsonObject {
    put("id", stringProp("表单内稳定且唯一的问题 ID"))
    putJsonObject("kind") {
        put("type", "string")
        putJsonArray("enum") {
            listOf("SINGLE", "MULTI").forEach { add(Json.parseToJsonElement("\"$it\"")) }
        }
    }
    put("title", stringProp("清晰且可直接回答的问题"))
    put("hint", stringProp("可选的选择要求"))
    putJsonObject("required") { put("type", "boolean") }
    putJsonObject("options") {
        put("type", "array")
        put("minItems", 1)
        put("items", choiceOptionSchema)
    }
})
private val choiceFormSchema = objectSchema(listOf("title", "taskAnchor", "questions"), buildJsonObject {
    put("title", stringProp("整组问题的标题"))
    putJsonObject("taskAnchor") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 400)
        put("description", "用户当前要完成的原始任务摘要；必须来自本轮请求，提交后将据此继续，禁止写入无关记忆")
    }
    put("submitLabel", stringProp("最后一步按钮文字，例如“提交选择”"))
    putJsonObject("questions") {
        put("type", "array")
        put("minItems", 1)
        put("items", choiceQuestionSchema)
    }
})
private val chartPointSchema = objectSchema(listOf("label", "value"), buildJsonObject {
    put("label", stringProp())
    putJsonObject("value") { put("type", "number") }
})
private val chartSeriesSchema = objectSchema(listOf("name", "kind", "points"), buildJsonObject {
    put("name", stringProp("图例中的系列名称"))
    putJsonObject("kind") {
        put("type", "string")
        putJsonArray("enum") {
            listOf("BAR", "LINE").forEach { add(Json.parseToJsonElement("\"$it\"")) }
        }
    }
    putJsonObject("points") {
        put("type", "array")
        put("items", chartPointSchema)
    }
    putJsonObject("axis") {
        put("type", "string")
        putJsonArray("enum") {
            listOf("START", "END").forEach { add(Json.parseToJsonElement("\"$it\"")) }
        }
    }
})
private val chartAnnotationSchema = objectSchema(listOf("label", "pointLabel"), buildJsonObject {
    put("label", stringProp("标注文字"))
    put("pointLabel", stringProp("标注对应的类别"))
    putJsonObject("value") { put("type", "number") }
    putJsonObject("axis") {
        put("type", "string")
        putJsonArray("enum") {
            listOf("START", "END").forEach { add(Json.parseToJsonElement("\"$it\"")) }
        }
    }
})
private val chartSchema = buildJsonObject {
    objectSchema(emptyList(), buildJsonObject {
        putJsonObject("kind") {
            put("type", "string")
            putJsonArray("enum") {
                listOf("BAR", "LINE", "PIE").forEach { add(Json.parseToJsonElement("\"$it\"")) }
            }
        }
        putJsonObject("points") {
            put("type", "array")
            put("items", chartPointSchema)
        }
        putJsonObject("series") {
            put("type", "array")
            put("items", chartSeriesSchema)
        }
        putJsonObject("stacked") { put("type", "boolean") }
        put("startAxisTitle", stringProp())
        put("endAxisTitle", stringProp())
        putJsonObject("annotations") {
            put("type", "array")
            put("items", chartAnnotationSchema)
        }
        put("title", stringProp())
    }).forEach { (key, value) -> put(key, value) }
    putJsonArray("anyOf") {
        add(buildJsonObject {
            putJsonArray("required") {
                add(Json.parseToJsonElement("\"kind\""))
                add(Json.parseToJsonElement("\"points\""))
            }
        })
        add(buildJsonObject {
            putJsonArray("required") { add(Json.parseToJsonElement("\"series\"")) }
        })
    }
}
private val graphSchema = objectSchema(listOf("nodes", "edges"), buildJsonObject {
    putJsonObject("nodes") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonArray("required") {
                add(Json.parseToJsonElement("\"id\""))
                add(Json.parseToJsonElement("\"label\""))
            }
            putJsonObject("properties") {
                put("id", stringProp())
                put("label", stringProp())
                put("kind", stringProp())
            }
        }
    }
    putJsonObject("edges") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonArray("required") {
                add(Json.parseToJsonElement("\"source\""))
                add(Json.parseToJsonElement("\"predicate\""))
                add(Json.parseToJsonElement("\"target\""))
            }
            putJsonObject("properties") {
                put("source", stringProp())
                put("predicate", stringProp())
                put("target", stringProp())
                putJsonObject("chapter") {
                    put("type", "integer")
                    put("minimum", 1)
                }
            }
        }
    }
    putJsonObject("claims") {
        put("type", "array")
        put("items", stringProp())
    }
    put("title", stringProp())
    put("focusId", stringProp())
})
private val listSchema = objectSchema(listOf("items"), buildJsonObject {
    put("title", stringProp())
    putJsonObject("items") {
        put("type", "array")
        put("items", stringProp())
    }
})
private val imageSchema = objectSchema(listOf("uri", "alt"), buildJsonObject {
    put("uri", stringProp("仅允许 data:image URI"))
    put("alt", stringProp())
    put("caption", stringProp())
})
private val writeSchema = objectSchema(listOf("name", "body"), buildJsonObject {
    put("name", stringProp())
    put("body", stringProp())
    put("summary", stringProp())
})
private val artifactRefSchema = objectSchema(listOf("artifactId", "version"), buildJsonObject {
    put("artifactId", stringProp())
    putJsonObject("version") {
        put("type", "integer")
        put("minimum", 1)
    }
})
private val reviseSchema = objectSchema(listOf("artifactId", "baseVersion", "body"), buildJsonObject {
    put("artifactId", stringProp())
    putJsonObject("baseVersion") {
        put("type", "integer")
        put("minimum", 1)
    }
    put("body", stringProp())
    put("summary", stringProp())
})
