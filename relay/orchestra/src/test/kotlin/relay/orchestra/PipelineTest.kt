package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.agent.Agent
import relay.agent.AgentConfig

class PipelineTest {

    @Test
    fun twoStepsPassResolvedArtifactToTheNext() = runTest {
        val longBody = "pad-".repeat(200) + "PIPELINE_SECRET"
        val gatherProvider = ScriptedProvider(listOf { ScriptedProvider.text(longBody) })
        val draftProvider = ScriptedProvider(listOf { ScriptedProvider.text("drafted") })
        val artifacts = InMemoryArtifactStore()
        val ledger = TeamLedger(runId = "pipe-1", goal = "article")
        val pipeline = Pipeline(
            steps = listOf(
                WorkerSpec(
                    id = "gather",
                    description = "collect",
                    spawn = {
                        Agent(
                            provider = gatherProvider,
                            config = AgentConfig(model = "fake-model", maxTurns = 2),
                            transformContext = { it },
                        )
                    },
                ),
                WorkerSpec(
                    id = "draft",
                    description = "write",
                    spawn = {
                        Agent(
                            provider = draftProvider,
                            config = AgentConfig(model = "fake-model", maxTurns = 2),
                            transformContext = { it },
                        )
                    },
                ),
            ),
            artifacts = artifacts,
            ledger = ledger,
        )

        val events = pipeline.prompt("topic: cats").toList()
        val ended = events.filterIsInstance<TeamEvent.CallEnded>()
        assertEquals(listOf("gather", "draft"), ended.map { it.workerId })
        assertEquals(1, ended[0].result.artifactRefs.size)
        assertEquals(longBody, artifacts.get(ended[0].result.artifactRefs.single()))

        val draftRequest = draftProvider.receivedRequests.single()
        val draftInput = draftRequest.messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(draftInput.contains("PIPELINE_SECRET"), "step 2 must receive resolved ref content")
        assertTrue(draftInput.contains("artifact://pipe-1/gather/gather"))
        assertEquals("drafted", ended.last().result.findings.single())
        assertEquals(listOf("gather", "draft"), ledger.assignments.map { it.workerId })
    }
}
