package relay.demo.agent

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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

/**
 * Keyless public search for the agent demo. Bing HTML is the working path on
 * CN networks; Wikipedia JSON is a fallback. Parsers are pure so they can be
 * spiked without an Agent loop.
 */
class WebSearch(private val http: OkHttpClient) {

    fun search(query: String): String {
        val hits = searchHits(query)
        require(hits.isNotEmpty()) { "no search results for '$query'" }
        return hits.mapIndexed { i, hit -> hit.format(i + 1) }.joinToString("\n\n")
    }

    fun searchHits(query: String): List<SearchHit> {
        val bing = runCatching { searchBing(query) }.getOrDefault(emptyList())
        if (bing.isNotEmpty()) return bing
        return runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
    }

    fun fetchUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: error("invalid url '$url'")
        require(parsed.isHttps || parsed.scheme == "http") { "only http(s) URLs are allowed" }
        val body = httpGet(parsed.toString())
        val text = stripHtml(body)
        require(text.isNotBlank()) { "empty page at $url" }
        return text.take(4_000)
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Current cn.bing.com SERP: `<div class="b_algoheader"><a href><h2>` not `<h2><a>`. */
        private val BING_BLOCK = Regex("""<li class="b_algo"[\s\S]*?</li>""", RegexOption.IGNORE_CASE)
        private val BING_HEADER = Regex(
            """class="b_algoheader"[\s\S]*?<a[^>]+href="(https?://[^"]+)"[^>]*>\s*<h2[^>]*>([\s\S]*?)</h2>""",
            RegexOption.IGNORE_CASE,
        )
        private val BING_SNIPPET = Regex(
            """<p class="b_lineclamp\d+"[^>]*>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE,
        )

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
    }
}

private fun String.encode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
