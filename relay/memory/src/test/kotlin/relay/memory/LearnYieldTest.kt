package relay.memory

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LearnYieldTest {
    @Test
    fun idleWeatherDoesNotRetry() {
        assertFalse(LearnYield.shouldRetryEmpty(listOf(user("今天天气怎么样"))))
    }

    @Test
    fun shortGreetingDoesNotRetry() {
        assertFalse(LearnYield.shouldRetryEmpty(listOf(user("你好"))))
    }

    @Test
    fun allergyFactRetries() {
        assertTrue(LearnYield.shouldRetryEmpty(listOf(user("我花生过敏"))))
    }

    @Test
    fun projectArchitectureRetries() {
        assertTrue(
            LearnYield.shouldRetryEmpty(
                listOf(user("我做过车管家，主界面有卡片引擎，详情页走 H5 分层。")),
            ),
        )
    }

    @Test
    fun projectFactsMintClaim() {
        val claims = HardFactClaims.from(
            GRAPH_ASSISTANT,
            listOf(user("我做过车管家，主界面有卡片引擎，云端下发卡片。")),
        )
        assertTrue(claims.single().text.contains("车管家"))
    }

    @Test
    fun idleChatDoesNotMintClaim() {
        assertTrue(HardFactClaims.from(GRAPH_ASSISTANT, listOf(user("今天天气怎么样"))).isEmpty())
    }

    private fun user(text: String) = RawEvent(
        id = "e1",
        graphId = GRAPH_ASSISTANT,
        ts = 1,
        sessionId = "s",
        role = "user",
        text = text,
        source = "chat",
        consumed = false,
    )
}
