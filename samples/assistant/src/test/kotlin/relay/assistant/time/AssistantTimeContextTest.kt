package relay.assistant.time

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import relay.llm.model.Message

class AssistantTimeContextTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `current time anchor includes local date weekday and zone`() {
        val anchor = currentTimeAnchor(
            Instant.parse("2026-09-13T14:42:00Z"),
            shanghai,
        )

        assertEquals("当前时间：2026-09-13 星期日 22:42（Asia/Shanghai）", anchor)
    }

    @Test
    fun `tomorrow plan from two days ago is marked expired`() {
        val history = temporalUserHistory(
            text = "明天中午去南京南站接妈妈",
            sentAt = Instant.parse("2026-09-11T02:00:00Z"),
            now = Instant.parse("2026-09-13T14:42:00Z"),
            zoneId = shanghai,
        )

        assertTrue(history.contains("已过期"))
        assertTrue(history.contains("不得作为今天待办"))
    }

    @Test
    fun `context augmenter adds the current time to every model request`() = runTest {
        val augmentation = currentTimeContextAugmenter(
            now = { Instant.parse("2026-09-13T14:42:00Z") },
            zoneId = shanghai,
        ).augment(listOf(Message.user("我今天要干啥")))

        assertEquals(
            "当前时间：2026-09-13 星期日 22:42（Asia/Shanghai）",
            augmentation.messages.single().content,
        )
    }
}
