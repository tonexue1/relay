package relay.demo.memory

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.OWNER_USER
import relay.memory.SPACE_ASSISTANT
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryWriterKind
import relay.memory.api.RecallRequest
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.api.StateReadRequest
import relay.memory.api.StateSelector
import relay.memory.captureTurn
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.ensureAssistantSpace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantCorpusRecallTest {

    @Test
    fun episodeClaimReplayKeepsExportedTwelveTurns() {
        val replay = AssistantCorpus.episodeClaimReplay
        assertEquals(12, replay.size)
        assertTrue(replay.any { "卡片引擎" in it && "jsb" in it })
        assertTrue(replay.any { "repository" in it && "401重试" in it })
        assertEquals("客户端开发", replay[9])
    }

    @Test
    fun ledgerStoresStateAndRecallsPeanut() = runBlocking {
        val runtime = SqliteLedgerRuntime(ApplicationProvider.getApplicationContext())
        runtime.ensureAssistantSpace()
        val raw = runtime.captureTurn(
            spaceId = SPACE_ASSISTANT,
            ownerId = OWNER_USER,
            domain = ClockDomain.WALL_CLOCK,
            role = "user",
            text = "我花生过敏，住杭州。",
            sessionId = "s1",
        )
        val source = SourceRef(SourceType.RAW_EVENT, raw)
        val now = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis())
        assertTrue(
            runtime.commit(
                MemoryBatch(
                    spaceId = SPACE_ASSISTANT,
                    ownerId = OWNER_USER,
                    writerKind = MemoryWriterKind.HOST,
                    writerId = "test",
                    writerRunId = "wave1",
                    commands = listOf(
                        StateCommand(
                            fieldId = "allergies",
                            payload = JsonObject(mapOf("value" to JsonPrimitive("花生"))),
                            rendered = RenderedText("花生"),
                            sources = listOf(source),
                            validFrom = now,
                        ),
                        StateCommand(
                            fieldId = "location",
                            payload = JsonObject(mapOf("value" to JsonPrimitive("杭州"))),
                            rendered = RenderedText("杭州"),
                            sources = listOf(source),
                            validFrom = now,
                        ),
                    ),
                ),
            ).ok,
        )
        val peanut = runtime.recall(
            RecallRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                query = "花生",
                at = now,
                sessionId = "s1",
            ),
        )
        assertTrue(peanut.hits.any { "花生" in it.text })
        val hotpot = runtime.recall(
            RecallRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                query = "火锅",
                at = now,
                sessionId = "s1",
            ),
        )
        assertTrue(hotpot.hits.none { it.text == "火锅" })

        val later = ClockStamp(ClockDomain.WALL_CLOCK, now.t + 1)
        assertTrue(
            runtime.commit(
                MemoryBatch(
                    spaceId = SPACE_ASSISTANT,
                    ownerId = OWNER_USER,
                    writerKind = MemoryWriterKind.HOST,
                    writerId = "test",
                    writerRunId = "wave2",
                    commands = listOf(
                        StateCommand(
                            fieldId = "location",
                            payload = JsonObject(mapOf("value" to JsonPrimitive("上海"))),
                            rendered = RenderedText("上海"),
                            sources = listOf(source),
                            validFrom = later,
                        ),
                    ),
                ),
            ).ok,
        )
        val live = runtime.getStates(
            StateReadRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                at = later,
                selectors = setOf(StateSelector("location")),
            ),
        )
        assertEquals("上海", live.states.getValue("location").text)
        val linwan = runtime.recall(
            RecallRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                query = "林晚",
                at = later,
                sessionId = "s1",
            ),
        )
        assertTrue(linwan.hits.none { "林晚" in it.text })
    }
}
