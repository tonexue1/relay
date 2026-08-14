package relay.ondevice.model

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadsAFreshFileAndMarksItReady() = runBlocking {
        val payload = payload(64)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val spec = spec(payload)
        val store = ModelStore(tmp.root)

        val file = store.ensurePresent(spec)

        assertEquals(payload.toList(), file.readBytes().toList())
        assertTrue(store.isReady(spec))
        assertEquals(0, server.takeRequest().getHeader("Range")?.length ?: 0)
    }

    @Test
    fun resumesFromAPartialFileWithARangeRequest() = runBlocking {
        val payload = payload(64)
        val split = 24
        val spec = spec(payload)
        val store = ModelStore(tmp.root)
        File(tmp.root, "${spec.fileName}.partial").writeBytes(payload.copyOfRange(0, split))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $split-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(payload.copyOfRange(split, payload.size))),
        )

        val file = store.ensurePresent(spec)

        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertEquals(payload.toList(), file.readBytes().toList())
        assertTrue(store.isReady(spec))
        assertTrue(!File(tmp.root, "${spec.fileName}.partial").exists())
    }

    @Test
    fun overwritesWhenTheServerIgnoresRangeAndReturnsTheWholeFile() = runBlocking {
        val payload = payload(32)
        val spec = spec(payload)
        val store = ModelStore(tmp.root)
        File(tmp.root, "${spec.fileName}.partial").writeBytes(ByteArray(10) { 0x7F })

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val file = store.ensurePresent(spec)

        assertEquals(payload.toList(), file.readBytes().toList())
        assertTrue(store.isReady(spec))
    }

    @Test
    fun skipsTheNetworkWhenThePartialFileIsAlreadyComplete() = runBlocking {
        val payload = payload(16)
        val spec = spec(payload)
        val store = ModelStore(tmp.root)
        File(tmp.root, "${spec.fileName}.partial").writeBytes(payload)

        val file = store.ensurePresent(spec)

        assertEquals(0, server.requestCount)
        assertEquals(payload.toList(), file.readBytes().toList())
        assertTrue(store.isReady(spec))
    }

    private fun spec(payload: ByteArray) = ModelSpec(
        id = "test-model",
        displayName = "test",
        fileName = "test.gguf",
        downloadUrl = server.url("/test.gguf").toString(),
        sha256 = sha256(payload),
        expectedBytes = payload.size.toLong(),
        contextWindow = 128,
        maxOutputTokens = 16,
    )

    private fun payload(size: Int): ByteArray = ByteArray(size) { i -> (i * 17).toByte() }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
