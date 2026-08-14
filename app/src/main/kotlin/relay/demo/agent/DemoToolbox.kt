package relay.demo.agent

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import relay.agent.FunTool
import relay.agent.Tool

/**
 * Local tools for the agent demo. Notes live in-process so a multi-turn prompt can
 * write, list, and read them without a backend. [http] is a dedicated client so
 * search/fetch timeouts do not affect the LLM stream.
 */
class DemoToolbox(
    private val http: OkHttpClient,
) {

    private val notes = LinkedHashMap<String, String>()

    fun clear() {
        notes.clear()
    }

    fun tools(): List<Tool> = listOf(
        FunTool(
            name = "get_current_time",
            description = "Current time in UTC and Asia/Shanghai, plus unix epoch milliseconds.",
        ) {
            val shanghai = ZonedDateTime.now(SHANGHAI)
            val utc = ZonedDateTime.now(ZoneId.of("UTC"))
            buildString {
                append("utc=").append(utc.format(ISO))
                append('\n')
                append("shanghai=").append(shanghai.format(ISO))
                append('\n')
                append("epoch_ms=").append(System.currentTimeMillis())
                append('\n')
                append("weekday=").append(shanghai.dayOfWeek)
            }
        },
        FunTool(
            name = "echo",
            description = "Echo the given text back unchanged.",
            parameters = stringSchema("text" to "Text to echo"),
        ) { args -> args.str("text") },
        FunTool(
            name = "calculator",
            description = "Evaluate a numeric expression. Supports + - * / and parentheses. Do not compute arithmetic yourself.",
            parameters = stringSchema("expression" to "For example (3+5)*12 or 144/12"),
        ) { args ->
            val expression = args.str("expression")
            val value = evalArithmetic(expression)
            "$expression = ${prettyNumber(value)}"
        },
        FunTool(
            name = "convert_units",
            description = "Convert a numeric value between units: km/mi, m/ft, kg/lb, c/f (celsius/fahrenheit).",
            parameters = objectSchema(
                "value" to ("number" to "Numeric amount"),
                "from" to ("string" to "Source unit: km, mi, m, ft, kg, lb, c, f"),
                "to" to ("string" to "Target unit"),
                required = listOf("value", "from", "to"),
            ),
        ) { args ->
            val value = args.num("value")
            val from = args.str("from")
            val to = args.str("to")
            val converted = convertUnits(value, from, to)
            "$value $from = $converted $to"
        },
        FunTool(
            name = "save_note",
            description = "Save a text note under a short key in this session's scratchpad.",
            parameters = objectSchema(
                "key" to ("string" to "Note id, for example midnight or result"),
                "text" to ("string" to "Note body"),
                required = listOf("key", "text"),
            ),
        ) { args ->
            val key = args.str("key")
            notes[key] = args.str("text")
            "saved:$key (${notes.size} notes)"
        },
        FunTool(
            name = "read_note",
            description = "Read a previously saved note by key.",
            parameters = stringSchema("key" to "Note id"),
        ) { args ->
            val key = args.str("key")
            notes[key] ?: error("no note named '$key'")
        },
        FunTool(
            name = "list_notes",
            description = "List keys and a short preview of every saved note.",
        ) {
            if (notes.isEmpty()) {
                "(empty)"
            } else {
                notes.entries.joinToString("\n") { (key, text) ->
                    "$key: ${text.take(80)}"
                }
            }
        },
        FunTool(
            name = "web_search",
            description = "Search the public internet. Returns titles, snippets and URLs. Use fetch_url to read a promising result.",
            parameters = stringSchema("query" to "Search query"),
        ) { args ->
            withContext(Dispatchers.IO) { webSearch(args.str("query")) }
        },
        FunTool(
            name = "fetch_url",
            description = "HTTP GET a public http(s) page and return extracted text (truncated).",
            parameters = stringSchema("url" to "Full http or https URL"),
        ) { args ->
            withContext(Dispatchers.IO) { fetchUrl(args.str("url")) }
        },
    )

    companion object {
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun webSearch(query: String): String {
        val bing = runCatching { searchBing(query) }.getOrDefault(emptyList())
        if (bing.isNotEmpty()) return bing.joinToString("\n\n")
        val wiki = runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
        if (wiki.isNotEmpty()) return wiki.joinToString("\n\n")
        error("no search results for '$query'")
    }

    private fun searchBing(query: String): List<String> {
        val url = "https://www.bing.com/search?q=${query.encode()}&setlang=zh-Hans"
        val html = httpGet(url)
        val blocks = BING_BLOCK.findAll(html).take(5).toList()
        return blocks.mapIndexedNotNull { index, match ->
            val block = match.value
            val link = BING_LINK.find(block) ?: return@mapIndexedNotNull null
            val href = link.groupValues[1]
            val title = stripHtml(link.groupValues[2]).ifBlank { return@mapIndexedNotNull null }
            val snippet = BING_SNIPPET.find(block)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
            "${index + 1}. $title\nurl: $href\n$snippet".trim()
        }
    }

    private fun searchWikipedia(query: String): List<String> {
        val url =
            "https://zh.wikipedia.org/w/api.php?action=opensearch&search=${query.encode()}&limit=5&namespace=0&format=json"
        val root = Json.parseToJsonElement(httpGet(url)).jsonArray
        val titles = root.getOrNull(1)?.jsonArray ?: return emptyList()
        val descs = root.getOrNull(2)?.jsonArray
        val urls = root.getOrNull(3)?.jsonArray
        return titles.mapIndexed { index, titleEl ->
            val title = titleEl.jsonPrimitive.content
            val desc = descs?.getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty()
            val href = urls?.getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty()
            "${index + 1}. $title\nurl: $href\n$desc".trim()
        }
    }

    private fun fetchUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: error("invalid url '$url'")
        require(parsed.isHttps || parsed.scheme == "http") { "only http(s) URLs are allowed" }
        val body = httpGet(parsed.toString())
        val text = stripHtml(body)
        require(text.isNotBlank()) { "empty page at $url" }
        return text.take(4_000)
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }
}

internal val SAMPLE_TASKS: List<Pair<String, String>> = listOf(
    "联网检索" to
        "用 web_search 搜「华为 Mate 70 发布年份」，再 fetch_url 打开一条看起来可靠的结果核对，把结论存成笔记 mate70。不要用训练记忆代替检索。",
    "午夜倒计时" to
        "先看现在上海时间，再用 calculator 算出距离今天 24:00 还剩多少分钟，把结果存成笔记 midnight，再 read_note 读出来，最后 list_notes。不要口算。",
    "单位换算" to
        "把 26 摄氏度转成华氏，把 10 公里转成英里。两个结果分别存笔记 temp 和 distance，然后 list_notes 汇总。必须用 convert_units。",
    "多步计算" to
        "用 calculator 分别算 (3+5)*12 和 144/12，比较哪个更大，把较大的那个用 echo 打出来，再存笔记 winner。",
)

private val BING_BLOCK = Regex("""<li class="b_algo"[\s\S]*?</li>""", RegexOption.IGNORE_CASE)
private val BING_LINK = Regex(
    """<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
    RegexOption.IGNORE_CASE,
)
private val BING_SNIPPET = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)

private fun String.encode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun stripHtml(html: String): String =
    html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace(Regex("&nbsp;|&#160;"), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun stringSchema(field: Pair<String, String>): JsonObject =
    objectSchema(field.first to ("string" to field.second), required = listOf(field.first))

private fun objectSchema(
    vararg fields: Pair<String, Pair<String, String>>,
    required: List<String>,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    putJsonObject("properties") {
        for ((name, spec) in fields) {
            val (type, description) = spec
            putJsonObject(name) {
                put("type", JsonPrimitive(type))
                put("description", JsonPrimitive(description))
            }
        }
    }
    putJsonArray("required") {
        required.forEach { add(JsonPrimitive(it)) }
    }
}

private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun String.str(key: String): String =
    asObject()[key]?.jsonPrimitive?.contentOrNull ?: error("missing string '$key'")

private fun String.num(key: String): Double {
    val el = asObject()[key]?.jsonPrimitive ?: error("missing number '$key'")
    return el.doubleOrNull ?: el.content.toDoubleOrNull() ?: error("not a number '$key'")
}

internal fun evalArithmetic(expression: String): Double {
    val src = expression.replace(" ", "")
    require(src.isNotEmpty()) { "empty expression" }
    require(src.length <= 200) { "expression too long" }
    require(src.all { it.isDigit() || it in "+-*/().eE" }) { "unsupported character in '$expression'" }
    val parser = ArithmeticParser(src)
    val value = parser.parse()
    parser.expectEnd()
    require(value.isFinite()) { "non-finite result" }
    return value
}

private class ArithmeticParser(private val src: String) {
    private var i = 0

    fun parse(): Double = parseAdd()

    fun expectEnd() {
        if (i < src.length) error("unexpected '${src[i]}' in '$src'")
    }

    private fun parseAdd(): Double {
        var left = parseMul()
        while (i < src.length && (src[i] == '+' || src[i] == '-')) {
            val op = src[i++]
            val right = parseMul()
            left = if (op == '+') left + right else left - right
        }
        return left
    }

    private fun parseMul(): Double {
        var left = parseUnary()
        while (i < src.length && (src[i] == '*' || src[i] == '/')) {
            val op = src[i++]
            val right = parseUnary()
            left = if (op == '*') left * right else left / right
        }
        return left
    }

    private fun parseUnary(): Double {
        if (i < src.length && src[i] == '+') {
            i++
            return parseUnary()
        }
        if (i < src.length && src[i] == '-') {
            i++
            return -parseUnary()
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Double {
        if (i < src.length && src[i] == '(') {
            i++
            val inner = parseAdd()
            require(i < src.length && src[i] == ')') { "missing ')'" }
            i++
            return inner
        }
        val start = i
        while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
        require(i > start) { "expected number at '$src' index $start" }
        return src.substring(start, i).toDouble()
    }
}

private fun convertUnits(value: Double, fromRaw: String, toRaw: String): String {
    val from = fromRaw.trim().lowercase()
    val to = toRaw.trim().lowercase()
    require(compatible(from, to)) { "cannot convert $fromRaw to $toRaw" }
    val si = when (from) {
        "km" -> value * 1_000
        "mi" -> value * 1_609.344
        "m" -> value
        "ft" -> value * 0.3048
        "kg" -> value
        "lb" -> value * 0.45359237
        "c" -> value
        "f" -> (value - 32) * 5 / 9
        else -> error("unsupported from unit '$fromRaw'")
    }
    val out = when (to) {
        "km" -> si / 1_000
        "mi" -> si / 1_609.344
        "m" -> si
        "ft" -> si / 0.3048
        "kg" -> si
        "lb" -> si / 0.45359237
        "c" -> si
        "f" -> si * 9 / 5 + 32
        else -> error("unsupported to unit '$toRaw'")
    }
    require(out.isFinite()) { "non-finite conversion" }
    return prettyNumber(out)
}

private fun prettyNumber(value: Double): String {
    val asLong = value.toLong()
    if (value == asLong.toDouble()) return asLong.toString()
    return String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}

private fun compatible(from: String, to: String): Boolean {
    val length = setOf("km", "mi", "m", "ft")
    val mass = setOf("kg", "lb")
    val temp = setOf("c", "f")
    return (from in length && to in length) || (from in mass && to in mass) || (from in temp && to in temp)
}
