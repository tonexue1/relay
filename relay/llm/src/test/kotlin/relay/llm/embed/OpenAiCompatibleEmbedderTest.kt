package relay.llm.embed

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import relay.llm.RelayLlmException

class OpenAiCompatibleEmbedderTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun embedder() = OpenAiCompatibleEmbedder(
        baseUrl = server.url("/").toString(),
        apiKey = "test-key",
        model = "text-embedding-3-small",
        httpClient = OkHttpClient(),
        providerId = "test-embed",
    )

    @Test
    fun mapsEmbeddingVector() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"embedding":[0.25,-0.5,1.0],"index":0}]}"""),
        )
        val vector = embedder().embed("西红柿")
        assertEquals(listOf(0.25f, -0.5f, 1.0f), vector.toList())
        val recorded = server.takeRequest()
        assertEquals("/embeddings", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("西红柿"))
    }

    @Test
    fun mapsAuthFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        assertFailsWith<RelayLlmException.Auth> {
            embedder().embed("x")
        }
    }
}
