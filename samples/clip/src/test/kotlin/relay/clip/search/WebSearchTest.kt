package relay.clip.search

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
    fun junkHitsAreCompanyHomeAndDictionaryCards() {
        assertTrue(
            WebSearch.isJunkHit(
                SearchHit("华为技术有限公司", "https://www.huawei.com/cn/", "集团网站"),
            ),
        )
        assertTrue(
            WebSearch.isJunkHit(
                SearchHit("mate （英文单词）_百度百科", "https://baike.baidu.com/item/mate", ""),
            ),
        )
        assertTrue(
            WebSearch.isJunkHit(
                SearchHit("MATE 桌面环境", "https://mate-desktop.org/zh_cn/", ""),
            ),
        )
        assertTrue(
            !WebSearch.isJunkHit(
                SearchHit("HUAWEI Mate 70_百度百科", "https://baike.baidu.com/item/HUAWEI%20Mate%2070/64325200", "2024"),
            ),
        )
    }

    @Test
    fun cookieWallIsNotTreatedAsArticleText() {
        val wall = "HUAWEI Mate 70 规格参数 我们使用cookie来确保您的高速浏览体验。继续浏览本站，即表示您同意我们使用cookie。 登录 注册 华为商城"
        assertTrue(WebSearch.isCookieOrChallengeWall(wall))
        assertTrue(WebSearch.isCookieOrChallengeWall("GSMArena Turnstile check One quick check before you continue"))
        assertTrue(!WebSearch.isCookieOrChallengeWall("HUAWEI Mate 70 于2024年11月26日发布，起售价5499元。"))
    }

    @Test
    fun parseBochaJsonReadsWebPagesValue() {
        val json = readResource("bocha_websearch_fixture.json")
        val hits = WebSearch.parseBochaJson(json)
        assertTrue(hits.size == 2)
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
    fun parseBochaJsonRejectsNon200Code() {
        val error = runCatching {
            WebSearch.parseBochaJson("""{"code":401,"msg":"invalid api key"}""")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("401"))
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
