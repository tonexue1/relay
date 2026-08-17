package relay.clip.search

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String = "",
) {
    fun format(index: Int): String = buildString {
        append(index).append(". ").append(title)
        append("\nurl: ").append(url)
        if (snippet.isNotBlank()) append('\n').append(snippet)
    }
}

data class SearchHits(
    val source: String,
    val hits: List<SearchHit>,
)

/**
 * Clip search atomic. Bocha Web Search API when [bochaApiKey] is set;
 * otherwise Bing HTML + Wikipedia JSON.
 */
class WebSearch(
    private val http: OkHttpClient,
    private val bochaApiKey: String = "",
) {

    fun search(query: String): String {
        val hits = searchHits(query)
        require(hits.isNotEmpty()) { "no search results for '$query'" }
        return hits.mapIndexed { i, hit -> hit.format(i + 1) }.joinToString("\n\n")
    }

    fun searchHits(query: String): List<SearchHit> = searchWithSource(query).hits

    fun searchWithSource(query: String): SearchHits {
        val key = bochaApiKey.trim()
        if (key.isNotEmpty()) {
            val bocha = searchBocha(query)
            if (bocha.isNotEmpty()) return SearchHits("Bocha", bocha)
            val wiki = runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
            return SearchHits(if (wiki.isEmpty()) "Bocha" else "Bocha+Wikipedia", wiki)
        }
        val bing = runCatching { searchBing(query) }.getOrDefault(emptyList()).filterNot(::isJunkHit)
        val wiki = runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
        val hits = (bing + wiki).distinctBy { it.url.lowercase() }.take(8)
        val source = when {
            bing.isNotEmpty() && wiki.isNotEmpty() -> "Bing+Wikipedia"
            bing.isNotEmpty() -> "Bing"
            wiki.isNotEmpty() -> "Wikipedia"
            else -> "none"
        }
        return SearchHits(source, hits)
    }

    fun fetchUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: error("invalid url '$url'")
        require(parsed.isHttps || parsed.scheme == "http") { "only http(s) URLs are allowed" }
        val text = stripHtml(httpGet(parsed.toString()))
        require(text.isNotBlank()) { "empty page at $url" }
        if (isCookieOrChallengeWall(text)) {
            return "blocked or cookie/challenge wall at $url. This is not article text. " +
                "Pick a different URL (news article, zol.com.cn, ithome.com, wikipedia.org)."
        }
        return text.take(4_000)
    }

    private fun searchBocha(query: String): List<SearchHit> {
        val payload = buildJsonObject {
            put("query", query)
            put("freshness", "noLimit")
            put("summary", true)
            put("count", 8)
        }.toString()
        val request = Request.Builder()
            .url(BOCHA_URL)
            .header("Authorization", "Bearer ${bochaApiKey.trim()}")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) error("Bocha HTTP ${response.code}: ${body.take(240)}")
            return parseBochaJson(body)
        }
    }

    private fun searchBing(query: String): List<SearchHit> {
        val url = "https://cn.bing.com/search?q=${query.encode()}&setlang=zh-Hans"
        return parseBingHtml(httpGet(url))
    }

    private fun searchWikipedia(query: String): List<SearchHit> {
        val url =
            "https://zh.wikipedia.org/w/api.php?action=opensearch&search=${query.encode()}&limit=5&namespace=0&format=json"
        return parseWikipediaOpensearch(httpGet(url))
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

    companion object {
        private const val BOCHA_URL = "https://api.bochaai.com/v1/web-search"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val BodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val BING_BLOCK = Regex("""<li class="b_algo"[\s\S]*?</li>""", RegexOption.IGNORE_CASE)
        private val BING_HEADER = Regex(
            """class="b_algoheader"[\s\S]*?<a[^>]+href="(https?://[^"]+)"[^>]*>\s*<h2[^>]*>([\s\S]*?)</h2>""",
            RegexOption.IGNORE_CASE,
        )
        private val BING_SNIPPET = Regex(
            """<p class="b_lineclamp\d+"[^>]*>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE,
        )

        fun parseBochaJson(raw: String): List<SearchHit> {
            val root = BodyJson.parseToJsonElement(raw).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) {
                val msg = root["msg"]?.jsonPrimitive?.contentOrNull
                    ?: root["message"]?.jsonPrimitive?.contentOrNull
                    ?: raw.take(200)
                error("Bocha $code: $msg")
            }
            val payload = root["data"]?.jsonObject ?: root
            val values = payload["webPages"]?.jsonObject?.get("value")?.jsonArray ?: return emptyList()
            return values.mapNotNull { el ->
                val obj = el.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty() || name.isEmpty()) return@mapNotNull null
                val snippet = obj["summary"]?.jsonPrimitive?.contentOrNull
                    ?: obj["snippet"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                SearchHit(name, url, snippet.take(500))
            }
        }

        fun parseBingHtml(html: String): List<SearchHit> =
            BING_BLOCK.findAll(html).mapNotNull { match ->
                val block = match.value
                val header = BING_HEADER.find(block) ?: return@mapNotNull null
                val url = header.groupValues[1]
                val title = stripHtml(header.groupValues[2]).ifBlank { return@mapNotNull null }
                val snippet = BING_SNIPPET.find(block)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
                SearchHit(title, url, snippet)
            }.take(5).toList()

        fun parseWikipediaOpensearch(json: String): List<SearchHit> {
            val root = Json.parseToJsonElement(json).jsonArray
            val titles = root.getOrNull(1)?.jsonArray ?: return emptyList()
            val descs = root.getOrNull(2)?.jsonArray
            val urls = root.getOrNull(3)?.jsonArray
            return titles.mapIndexed { index, titleEl ->
                SearchHit(
                    title = titleEl.jsonPrimitive.content,
                    url = urls?.getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty(),
                    snippet = descs?.getOrNull(index)?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

        fun stripHtml(html: String): String =
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

        internal fun isJunkHit(hit: SearchHit): Boolean {
            val title = hit.title.lowercase()
            val url = hit.url.lowercase()
            if (title.contains("英文单词") || title.contains("汉语文字") || title.contains("的翻译")) return true
            if (title.contains("桌面环境") || url.contains("mate-desktop.org") || url.contains("iciba.com")) return true
            if (title == "华为技术有限公司" || title.contains("构建万物互联")) return true
            return false
        }

        internal fun isCookieOrChallengeWall(text: String): Boolean {
            val head = text.take(800).lowercase()
            return head.contains("turnstile") ||
                head.contains("one quick check") ||
                head.contains("just a moment") ||
                head.contains("继续浏览本站，即表示您同意我们使用cookie") ||
                (head.contains("cookie") && head.contains("登录") && head.contains("华为商城"))
        }
    }
}

private fun String.encode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
