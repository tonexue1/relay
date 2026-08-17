package relay.demo.agent

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    @Test
    fun parseBingHtmlReadsAlgoHeaderLayout() {
        val html = readResource("bing_serp_fixture.html")
        val hits = WebSearch.parseBingHtml(html)
        assertTrue(hits.size >= 2)
        assertTrue(hits[0].title.contains("Mate 70"))
        assertTrue(hits[0].url.contains("baike.baidu.com"))
        assertTrue(hits[0].snippet.contains("2024"))
    }

    @Test
    fun parseWikipediaOpensearchReadsJsonArray() {
        val json = readResource("wikipedia_opensearch.json")
        val hits = WebSearch.parseWikipediaOpensearch(json)
        assertTrue(hits.single().title.contains("Mate 70"))
        assertTrue(hits.single().url.startsWith("https://"))
    }

    @Test
    fun liveBingSearchReturnsUrls() {
        val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        val hits = WebSearch(http).searchHits("Huawei Mate 70")
        assertTrue("expected Bing hits, got $hits", hits.isNotEmpty())
        assertTrue(hits.any { it.url.startsWith("http") })
        assertTrue(hits.any { it.title.isNotBlank() })
    }

    private fun readResource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing $name" }
            .readText()
}
