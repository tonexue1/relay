package relay.clip.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.FunTool
import relay.agent.Tool
import relay.clip.search.WebSearch
import relay.orchestra.ArtifactRef
import relay.orchestra.ArtifactStore

internal fun scoutTools(search: WebSearch): List<Tool> = listOf(
    FunTool(
        name = "web_search",
        description = "Search the public internet via Bocha. Start with a short broad query, then narrow. Returns titles, snippets, URLs.",
        parameters = stringSchema("query" to "Short search query"),
    ) { args ->
        val query = jsonArg(args, "query", "q", "task", "text")
        withContext(Dispatchers.IO) {
            val hits = search.searchHits(query)
            if (hits.isEmpty()) {
                "no search results for '$query'. Retry once with a shorter, broader query."
            } else {
                hits.mapIndexed { i, hit -> hit.format(i + 1) }.joinToString("\n\n")
            }
        }
    },
    FunTool(
        name = "fetch_url",
        description = "HTTP GET a public http(s) page and return extracted text (truncated). Use on 1–2 promising URLs only.",
        parameters = stringSchema("url" to "Full http or https URL"),
    ) { args ->
        val url = jsonArg(args, "url", "href", "link")
        withContext(Dispatchers.IO) {
            runCatching { search.fetchUrl(url) }.getOrElse { e ->
                "fetch_url failed for $url: ${e.message}. Try another result URL."
            }
        }
    },
)

internal fun readArtifactTool(store: ArtifactStore): Tool = FunTool(
    name = "read_artifact",
    description = "Read a worker artifact by uri from WorkerReturn.artifactRefs (artifact://...). Call this before synthesizing.",
    parameters = stringSchema("uri" to "artifact://runId/name"),
) { args ->
    val raw = jsonArg(args, "uri", "ref", "url")
    val ref = parseArtifactUri(raw)
    runCatching { store.get(ref) }.getOrElse { e ->
        "read_artifact failed for ${ref.uri}: ${e.message}"
    }
}

internal const val LEAD_SYSTEM =
    "你是主编 LeadResearcher。用户给你一个课题。你没有网页搜索。" +
        "唯一调研工人是 tool `scout`。可以在同一轮并行调用多次 scout，每次一个互不重叠的子问题。" +
        "第一次先用一两句话写出计划，然后立刻派工。" +
        "每个 scout 的 task 必须包含：目标、输出格式（发现+URL）、不要覆盖的边界。" +
        "缩放：一句话事实只用 1 个 scout；对比或多个实体同一轮 2–3 个；禁止超过 3 个；禁止两个 scout 搜同一件事。" +
        "工人回来的是摘要。长文在 artifactRefs 的 uri 里，综合前必须 read_artifact。" +
        "scout 一回来就写终稿：结论、争议、每条跟 http(s) URL。禁止把搜索计划或 SERP 当终稿。不要提 tool 名。"

internal const val SCOUT_SYSTEM =
    "你是隔离的调研工人。只做当前 brief，不管别的子问题。" +
        "必须用 web_search，禁止用训练记忆当来源。" +
        "先短宽查询，看结果再收窄。一次只调一个 web_search，不要并行连打。" +
        "最多 fetch_url 打开 2 个看起来可靠的页面。优先 ithome.com / zol.com.cn / wikipedia.org 的文章，不要打开华为官网首页或需要登录的页。" +
        "若工具返回 cookie wall / blocked / 词典或公司首页，换查询或换 URL，不要把那段当发现。" +
        "最终一条（不再调工具）必须是发现列表：每条一行，事实 + url: https://..." +
        "禁止写「我先搜」「接下来将」、禁止复述搜索步骤、禁止把 SERP 标题列表当结论。"

internal const val SCOUT_DESCRIPTION =
    "Independent research subagent with its own context. " +
        "Call once per distinct sub-question. Parallel calls must have non-overlapping briefs. " +
        "task MUST include: objective, output format (findings with URLs), preferred sources, boundaries (what NOT to cover)."

private fun stringSchema(field: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    putJsonObject("properties") {
        putJsonObject(field.first) {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(field.second))
        }
    }
    putJsonArray("required") { add(JsonPrimitive(field.first)) }
}

private val ArgsJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun jsonArg(raw: String, vararg keys: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty() && trimmed != "{}") { "empty tool arguments" }
    var text = trimmed
    repeat(2) {
        val el = ArgsJson.parseToJsonElement(text)
        if (el is JsonObject) {
            for (k in keys) {
                el[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            el.values.firstNotNullOfOrNull { v ->
                v.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }?.let { return it }
            error("missing ${keys.joinToString("/")} in $trimmed")
        }
        val inner = el.jsonPrimitive.contentOrNull?.trim().orEmpty()
        if (inner.startsWith("{")) {
            text = inner
        } else {
            require(inner.isNotEmpty()) { "missing ${keys.joinToString("/")} in $trimmed" }
            return inner
        }
    }
    error("missing ${keys.joinToString("/")} in $trimmed")
}

internal fun parseArtifactUri(raw: String): ArtifactRef {
    val start = raw.indexOf("artifact://")
    val uri = if (start >= 0) {
        raw.substring(start).trim().trimEnd(',', '"', '}', ']', ' ')
    } else {
        raw.trim()
    }
    val prefix = "artifact://"
    require(uri.startsWith(prefix)) { "not an artifact uri: $raw" }
    val rest = uri.removePrefix(prefix)
    val slash = rest.indexOf('/')
    require(slash > 0 && slash < rest.lastIndex) { "malformed artifact uri: $raw" }
    return ArtifactRef(runId = rest.substring(0, slash), name = rest.substring(slash + 1))
}
