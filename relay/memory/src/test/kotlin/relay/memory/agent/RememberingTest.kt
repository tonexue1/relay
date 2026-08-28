package relay.memory.agent

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.llm.model.Message
import relay.memory.OWNER_USER
import relay.memory.SPACE_ASSISTANT
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryWriterKind
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.captureTurn
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.ensureAssistantSpace
import relay.memory.testContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RememberingTest {

    @Test
    fun recallingPadsAllergyAndEpisode() = runTest {
        val runtime = SqliteLedgerRuntime(testContext())
        runtime.ensureAssistantSpace()
        val rawId = runtime.captureTurn(
            spaceId = SPACE_ASSISTANT,
            ownerId = OWNER_USER,
            domain = ClockDomain.WALL_CLOCK,
            role = "user",
            text = "我花生过敏，火锅别放花生。",
            sessionId = "s1",
        )
        val result = runtime.commit(
            MemoryBatch(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                writerKind = MemoryWriterKind.HOST,
                writerId = "test",
                writerRunId = "seed",
                commands = listOf(
                    StateCommand(
                        fieldId = "allergies",
                        payload = JsonObject(mapOf("value" to JsonPrimitive("花生"))),
                        rendered = RenderedText("花生"),
                        sources = listOf(SourceRef(SourceType.RAW_EVENT, rawId)),
                        validFrom = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()),
                    ),
                ),
            ),
        )
        assertTrue(result.ok)

        val pad = runtime.recallPad(
            spaceId = SPACE_ASSISTANT,
            ownerId = OWNER_USER,
            query = "火锅",
            at = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()),
            sessionId = "s1",
        )
        assertTrue("花生" in pad)

        val augmenter = runtime.recalling(SPACE_ASSISTANT, OWNER_USER, sessionId = { "s1" })
        val out = augmenter.augment(listOf(Message.user("今晚想吃火锅")))
        assertTrue(out.messages.any { "花生" in (it.content.orEmpty()) })
    }
}
