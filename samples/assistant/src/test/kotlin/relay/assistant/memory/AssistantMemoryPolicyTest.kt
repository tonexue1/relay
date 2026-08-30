package relay.assistant.memory

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.LifecycleState
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryWriterKind
import relay.memory.api.RecallRequest
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.api.StateHistoryRequest
import relay.memory.api.StateReadRequest
import relay.memory.api.StateSelector
import relay.memory.engine.SqliteLedgerRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantMemoryPolicyTest {

    private fun runtime() = SqliteLedgerRuntime(ApplicationProvider.getApplicationContext())

    private fun policy(runtime: SqliteLedgerRuntime) =
        AssistantMemoryPolicy(runtime, SPACE_ASSISTANT, OWNER_USER)

    @Test
    fun emptyProposalDoesNotCommit() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val out = policy(runtime).remember(StateProposal("", ""))
        assertFalse(out.ok)
        assertEquals("没有可记的内容", out.message)
        assertTrue(runtime.listItems(SPACE_ASSISTANT, OWNER_USER).isEmpty())
    }

    @Test
    fun allergiesAreActiveAndRecallAcrossSessions() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val out = policy(runtime).remember(StateProposal("过敏", "花生"))
        assertTrue(out.ok)
        assertEquals("allergies", out.fieldId)
        assertFalse(out.candidate)

        val now = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis())
        val states = runtime.getStates(
            StateReadRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                at = now,
                selectors = setOf(StateSelector("allergies")),
            ),
        )
        assertEquals("花生", states.states.getValue("allergies").text)

        val otherSession = runtime.recall(
            RecallRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                query = "花生",
                at = now,
                sessionId = "other",
            ),
        )
        assertTrue(otherSession.hits.any { "花生" in it.text })
    }

    @Test
    fun unknownFieldIsCandidateAndNotCurrent() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val out = policy(runtime).remember(StateProposal("巴拉巴拉", "随便"))
        assertTrue(out.ok)
        assertTrue(out.candidate)
        assertEquals("巴拉巴拉", out.fieldId)

        val now = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis())
        val states = runtime.getStates(
            StateReadRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                at = now,
                selectors = setOf(StateSelector("巴拉巴拉")),
            ),
        )
        assertTrue(states.states.isEmpty())
        val history = runtime.getStateHistory(
            StateHistoryRequest(SPACE_ASSISTANT, OWNER_USER, "巴拉巴拉"),
        )
        assertEquals(listOf(LifecycleState.CANDIDATE), history.map { it.lifecycle })
    }

    @Test
    fun userLockDowngradesExtractorToCandidate() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        assertTrue(
            runtime.commit(
                MemoryBatch(
                    spaceId = SPACE_ASSISTANT,
                    ownerId = OWNER_USER,
                    writerKind = MemoryWriterKind.USER_EDIT,
                    writerId = "ui",
                    writerRunId = "edit",
                    commands = listOf(
                        StateCommand(
                            fieldId = "allergies",
                            payload = JsonObject(mapOf("value" to JsonPrimitive("花生"))),
                            rendered = RenderedText("花生"),
                            sources = listOf(SourceRef(SourceType.USER_EDIT, "ui")),
                            validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                        ),
                    ),
                ),
            ).ok,
        )
        val out = policy(runtime).remember(StateProposal("allergies", "无"))
        assertTrue(out.ok)
        assertTrue(out.candidate)

        val states = runtime.getStates(
            StateReadRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                at = ClockStamp(ClockDomain.WALL_CLOCK, 3),
                selectors = setOf(StateSelector("allergies")),
            ),
        )
        assertEquals("花生", states.states.getValue("allergies").text)
    }

    @Test
    fun rememberStateToolWritesThroughPolicy() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val tool = runtime.rememberTools(SPACE_ASSISTANT, OWNER_USER).single()
        val message = tool.execute("c1", """{"field_id":"location","value":"杭州"}""")
        assertTrue("记下了" in message)
        val states = runtime.getStates(
            StateReadRequest(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                at = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()),
                selectors = setOf(StateSelector("location")),
            ),
        )
        assertEquals("杭州", states.states.getValue("location").text)
    }
}
