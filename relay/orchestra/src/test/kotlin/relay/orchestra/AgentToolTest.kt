@file:OptIn(ExperimentalCoroutinesApi::class)

package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.agent.FunTool
import relay.llm.model.ChatChunk
import relay.llm.model.FinishReason
import relay.llm.model.ToolCall
import relay.llm.model.ToolCallDelta
import relay.llm.model.Usage

class AgentToolTest {

    @Test
    fun leadSeesShortJsonAndRefNotWorkerBody() = runTest {
        val longBody = "pad-".repeat(200) + UNIQUE_TAIL
        val workerProvider = ScriptedProvider(listOf { ScriptedProvider.text(longBody) })
        val leadProvider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "researcher", """{"task":"dig"}""")) },
                { ScriptedProvider.text("summary for user") },
            ),
        )
        val artifacts = InMemoryArtifactStore()
        val ledger = TeamLedger(runId = "run-a", goal = "dig")
        val team = Supervisor(
            spawnLead = { tools ->
                Agent(
                    provider = leadProvider,
                    config = AgentConfig(model = "fake-model", systemPrompt = "you are lead"),
                    tools = tools,
                    transformContext = { it },
                )
            },
            workers = listOf(
                WorkerSpec(
                    id = "researcher",
                    description = "Look things up",
                    spawn = {
                        Agent(
                            provider = workerProvider,
                            config = AgentConfig(model = "fake-model", maxTurns = 4),
                            transformContext = { it },
                        )
                    },
                ),
            ),
            artifacts = artifacts,
            ledger = ledger,
        )

        val events = team.prompt("please research").toList()

        val ended = events.filterIsInstance<TeamEvent.CallEnded>().single()
        assertEquals("researcher", ended.workerId)
        assertEquals(WorkerStatus.ok, ended.result.status)
        assertEquals(1, ended.result.artifactRefs.size)
        val ref = ended.result.artifactRefs.single()
        assertEquals(longBody, artifacts.get(ref))
        assertFalse(ended.result.toJson().contains(UNIQUE_TAIL))

        val leadSecond = leadProvider.receivedRequests[1]
        val joined = leadSecond.messages.joinToString("\n") { it.content.orEmpty() }
        assertFalse(joined.contains(UNIQUE_TAIL), "lead must not see worker body")
        assertTrue(joined.contains(ref.runId) || joined.contains("artifactRefs"))
        assertEquals("summary for user", events.filterIsInstance<TeamEvent.Lead>()
            .mapNotNull { (it.event as? relay.agent.AgentEvent.MessageEnd)?.message?.content }
            .last())
        assertEquals(1, ledger.assignments.size)
        assertEquals("researcher", ledger.assignments.single().workerId)
    }

    @Test
    fun workerReturnIsFinalAnswerNotSearchPreamble() = runTest {
        val workerProvider = ScriptedProvider(
            listOf(
                {
                    flow {
                        emit(ChatChunk.Text("我先搜索过程 SERP dump"))
                        emit(
                            ChatChunk.ToolCalls(
                                ToolCallDelta(
                                    index = 0,
                                    id = "1",
                                    name = "echo",
                                    argumentsDelta = "{}",
                                ),
                            ),
                        )
                        emit(ChatChunk.Done(Usage(1, 1, 2), FinishReason.TOOL_CALLS))
                    }
                },
                { ScriptedProvider.text("- 结论A url: https://example.com/a") },
            ),
        )
        val leadProvider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "scout", """{"task":"dig"}""")) },
                { ScriptedProvider.text("ok") },
            ),
        )
        val team = Supervisor(
            spawnLead = { tools ->
                Agent(
                    provider = leadProvider,
                    config = AgentConfig(model = "fake-model"),
                    tools = tools,
                    transformContext = { it },
                )
            },
            workers = listOf(
                WorkerSpec(
                    id = "scout",
                    description = "research",
                    spawn = {
                        Agent(
                            provider = workerProvider,
                            config = AgentConfig(model = "fake-model", maxTurns = 2),
                            tools = listOf(FunTool("echo") { "hit" }),
                            transformContext = { it },
                        )
                    },
                ),
            ),
            artifacts = InMemoryArtifactStore(),
            ledger = TeamLedger(runId = "run-final", goal = "dig"),
        )

        val ended = team.prompt("go").toList().filterIsInstance<TeamEvent.CallEnded>().single()
        assertEquals(WorkerStatus.ok, ended.result.status)
        assertTrue(ended.result.findings.any { it.contains("结论A") })
        assertFalse(ended.result.findings.any { it.contains("我先搜索") })
    }

    @Test
    fun parallelCallsEmitBothStarted() = runTest {
        val leadProvider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("1", "scout", """{"task":"a"}"""),
                        ToolCall("2", "numbers", """{"task":"b"}"""),
                    )
                },
                { ScriptedProvider.text("done") },
            ),
        )
        val team = Supervisor(
            spawnLead = { tools ->
                Agent(
                    provider = leadProvider,
                    config = AgentConfig(model = "fake-model"),
                    tools = tools,
                    transformContext = { it },
                )
            },
            workers = listOf(
                hangingWorker("scout", "alpha"),
                hangingWorker("numbers", "beta"),
            ),
            artifacts = InMemoryArtifactStore(),
            ledger = TeamLedger(runId = "run-p", goal = "both"),
        )

        val events = team.prompt("dispatch both").toList()
        val started = events.filterIsInstance<TeamEvent.CallStarted>().map { it.workerId }
        assertEquals(setOf("scout", "numbers"), started.toSet())
        assertEquals(2, events.filterIsInstance<TeamEvent.CallEnded>().size)
    }

    @Test
    fun cancellingOuterJobCancelsWorkerCollect() = runTest {
        var workerCancelled = false
        val workerProvider = ScriptedProvider(
            listOf {
                flow {
                    try {
                        emit(ChatChunk.Text("partial"))
                        delay(60_000)
                        emit(ChatChunk.Done(Usage(1, 1, 2), FinishReason.STOP))
                    } finally {
                        workerCancelled = true
                    }
                }
            },
        )
        val leadProvider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "researcher", """{"task":"hang"}""")) },
            ),
        )
        val team = Supervisor(
            spawnLead = { tools ->
                Agent(
                    provider = leadProvider,
                    config = AgentConfig(model = "fake-model", maxTurns = 2),
                    tools = tools,
                    transformContext = { it },
                )
            },
            workers = listOf(
                WorkerSpec(
                    id = "researcher",
                    description = "hangs",
                    spawn = {
                        Agent(
                            provider = workerProvider,
                            config = AgentConfig(model = "fake-model", maxTurns = 4),
                            transformContext = { it },
                        )
                    },
                ),
            ),
            artifacts = InMemoryArtifactStore(),
            ledger = TeamLedger(runId = "run-c", goal = "cancel"),
        )

        val job = launch { team.prompt("go").toList() }
        testScheduler.advanceTimeBy(10)
        job.cancelAndJoin()

        assertTrue(workerCancelled)
        assertTrue(job.isCancelled)
    }

    @Test
    fun parallelCallsToSameWorkerWriteDistinctArtifacts() = runTest {
        val longA = "pad-".repeat(200) + "ALPHA_SECRET"
        val longB = "pad-".repeat(200) + "BETA_SECRET"
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        val artifacts = InMemoryArtifactStore()
        val leadProvider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("call-a", "scout", """{"task":"aspect A"}"""),
                        ToolCall("call-b", "scout", """{"task":"aspect B"}"""),
                    )
                },
                { ScriptedProvider.text("summary") },
            ),
        )
        val team = Supervisor(
            spawnLead = { tools ->
                Agent(
                    provider = leadProvider,
                    config = AgentConfig(model = "fake-model"),
                    tools = tools,
                    transformContext = { it },
                )
            },
            workers = listOf(
                WorkerSpec(
                    id = "scout",
                    description = "research one aspect",
                    spawn = {
                        val body = if (n.getAndIncrement() == 0) longA else longB
                        Agent(
                            provider = ScriptedProvider(listOf { ScriptedProvider.text(body) }),
                            config = AgentConfig(model = "fake-model", maxTurns = 2),
                            transformContext = { it },
                        )
                    },
                ),
            ),
            artifacts = artifacts,
            ledger = TeamLedger(runId = "run-dup", goal = "two scouts"),
        )

        val events = team.prompt("split").toList()
        val refs = events.filterIsInstance<TeamEvent.CallEnded>().flatMap { it.result.artifactRefs }
        assertEquals(2, refs.size)
        assertEquals(setOf("scout/call-a", "scout/call-b"), refs.map { it.name }.toSet())
        val bodies = refs.map { artifacts.get(it) }.toSet()
        assertTrue(bodies.any { it.contains("ALPHA_SECRET") })
        assertTrue(bodies.any { it.contains("BETA_SECRET") })
    }

    private fun hangingWorker(id: String, text: String): WorkerSpec = WorkerSpec(
        id = id,
        description = id,
        spawn = {
            Agent(
                provider = ScriptedProvider(
                    listOf {
                        flow {
                            emit(ChatChunk.Text(text))
                            delay(50)
                            emit(ChatChunk.Done(Usage(1, text.length, 1 + text.length), FinishReason.STOP))
                        }
                    },
                ),
                config = AgentConfig(model = "fake-model", maxTurns = 2),
                transformContext = { it },
            )
        },
    )

    companion object {
        private const val UNIQUE_TAIL = "UNIQUE_SECRET_TAIL"
    }
}
