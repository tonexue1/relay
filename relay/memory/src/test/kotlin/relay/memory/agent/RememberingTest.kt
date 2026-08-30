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
import relay.memory.MemoryScope
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.EpisodeCommand
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryWriterKind
import relay.memory.api.OverwritePolicy
import relay.memory.api.RawEventDraft
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.api.StateFieldSpec
import relay.memory.api.StateSchemaSnapshot
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.testContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RememberingTest {

    @Test
    fun recallingPadsStateAndEpisode() = runTest {
        val runtime = SqliteLedgerRuntime(testContext())
        val space = "space"
        val owner = "owner"
        runtime.registerStateSchema(
            StateSchemaSnapshot(
                spaceId = space,
                clockDomain = ClockDomain.WALL_CLOCK,
                fields = listOf(
                    StateFieldSpec(
                        spaceId = space,
                        fieldId = "note",
                        overwritePolicy = OverwritePolicy.USER_LOCK,
                    ),
                ),
            ),
        )
        val rawId = runtime.capture(
            RawEventDraft(
                spaceId = space,
                ownerId = owner,
                role = "user",
                content = "我花生过敏，火锅别放花生。",
                clockDomain = ClockDomain.WALL_CLOCK,
                sessionId = "s1",
            ),
        )
        val now = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis())
        val result = runtime.commit(
            MemoryBatch(
                spaceId = space,
                ownerId = owner,
                writerKind = MemoryWriterKind.HOST,
                writerId = "test",
                writerRunId = "seed",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "raw:$rawId",
                        occurredAt = now,
                        rendered = RenderedText("user: 我花生过敏，火锅别放花生。"),
                        sources = listOf(SourceRef(SourceType.RAW_EVENT, rawId)),
                        scope = MemoryScope.SESSION,
                        scopeId = "s1",
                    ),
                    StateCommand(
                        fieldId = "note",
                        payload = JsonObject(mapOf("value" to JsonPrimitive("花生"))),
                        rendered = RenderedText("花生"),
                        sources = listOf(SourceRef(SourceType.RAW_EVENT, rawId)),
                        validFrom = now,
                    ),
                ),
                commitRawIds = listOf(rawId),
            ),
        )
        assertTrue(result.ok)

        val pad = runtime.recallPad(
            spaceId = space,
            ownerId = owner,
            query = "火锅",
            at = now,
            sessionId = "s1",
        )
        assertTrue("花生" in pad)

        val augmenter = runtime.recalling(space, owner, sessionId = { "s1" })
        val out = augmenter.augment(listOf(Message.user("今晚想吃火锅")))
        assertTrue(out.messages.any { "花生" in (it.content.orEmpty()) })
    }
}
