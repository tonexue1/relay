@file:OptIn(ExperimentalCoroutinesApi::class)

package relay.agent

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.llm.RelayLlmException
import relay.llm.model.Capability
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.model.ToolCall

class AgentTest {

    @Test
    fun promptWithoutToolsEmitsPiLifecycleAndKeepsTranscript() = runTest {
        val provider = ScriptedProvider(listOf { ScriptedProvider.text("hello") })
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", systemPrompt = "be brief"),
            transformContext = { it },
        )

        val events = agent.prompt("hi").toList()

        assertEquals(AgentEvent.AgentStart, events.first())
        assertTrue(events.any { it is AgentEvent.TurnStart })
        assertTrue(events.any { it is AgentEvent.TurnEnd })
        assertEquals(agent.state.messages, (events.last() as AgentEvent.AgentEnd).messages)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), agent.state.messages.map { it.role })
        assertEquals("hello", agent.state.messages.last().content)
        assertEquals("be brief", provider.receivedRequests.single().messages.first().content)
        assertFalse(agent.state.isRunning)
    }

    @Test
    fun runFoldsTheStreamIntoAResult() = runTest {
        val agent = Agent(
            provider = ScriptedProvider(listOf { ScriptedProvider.text("done") }),
            config = AgentConfig(model = "fake-model"),
            transformContext = { it },
        )

        val result = agent.run("go")

        assertEquals("done", result.text)
        assertEquals(FinishReason.STOP, result.finishReason)
        assertEquals(2, result.messages.size)
    }

    @Test
    fun oneToolTurnThenStopWritesResultsInSourceOrder() = runTest {
        val provider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("1", "echo", """{"text":"a"}"""),
                        ToolCall("2", "echo", """{"text":"b"}"""),
                    )
                },
                { ScriptedProvider.text("after tools") },
            ),
        )
        val seen = mutableListOf<String>()
        val echo = FunTool("echo", "echo args") { args ->
            seen += args
            "echoed:$args"
        }
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            tools = listOf(echo),
            transformContext = { it },
        )

        val events = agent.prompt("use echo").toList()

        val ends = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
        assertEquals(2, ends.size)
        assertTrue(ends.all { !it.isError })

        val roles = agent.state.messages.map { it.role }
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL, Role.ASSISTANT),
            roles,
        )
        val toolMessages = agent.state.messages.filter { it.role == Role.TOOL }
        assertEquals("1", toolMessages[0].toolCallId)
        assertEquals("2", toolMessages[1].toolCallId)
        assertEquals("echoed:{\"text\":\"a\"}", toolMessages[0].content)
        assertEquals("after tools", agent.state.messages.last().content)
        assertEquals(2, provider.receivedRequests.size)
        assertTrue(provider.receivedRequests.last().messages.any { it.role == Role.TOOL })
        assertEquals(2, events.filterIsInstance<AgentEvent.TurnStart>().size)
    }

    @Test
    fun parallelToolsOverlapInVirtualTime() = runTest {
        val provider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("1", "slow_a", "{}"),
                        ToolCall("2", "slow_b", "{}"),
                    )
                },
                { ScriptedProvider.text("ok") },
            ),
        )
        val tools = listOf(
            FunTool("slow_a") { delay(50); "A" },
            FunTool("slow_b") { delay(50); "B" },
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", toolExecution = ToolExecutionMode.Parallel),
            tools = tools,
            transformContext = { it },
        )

        agent.prompt("go").toList()

        assertEquals(50, testScheduler.currentTime)
    }

    @Test
    fun unknownToolAndThrownExecuteBecomeErrorResultsAndLoopContinues() = runTest {
        val provider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("1", "missing", "{}"),
                        ToolCall("2", "boom", "{}"),
                    )
                },
                { ScriptedProvider.text("recovered") },
            ),
        )
        val boom = FunTool("boom") { error("nope") }
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            tools = listOf(boom),
            transformContext = { it },
        )

        val events = agent.prompt("call").toList()
        val ends = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
        assertEquals(2, ends.size)
        assertTrue(ends.all { it.isError })
        assertTrue(ends[0].result.contains("Unknown tool"))
        assertEquals("nope", ends[1].result)
        assertEquals("recovered", agent.state.messages.last().content)
    }

    @Test
    fun beforeToolCallBlockBecomesAnErrorResult() = runTest {
        val provider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "echo", "{}")) },
                { ScriptedProvider.text("blocked-ok") },
            ),
        )
        var executed = false
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            tools = listOf(FunTool("echo") { executed = true; "hi" }),
            transformContext = { it },
            beforeToolCall = { BeforeToolCallResult(block = true, reason = "not allowed") },
        )

        val events = agent.prompt("x").toList()

        assertFalse(executed)
        val end = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertTrue(end.isError)
        assertEquals("not allowed", end.result)
        assertEquals("blocked-ok", agent.state.messages.last().content)
    }

    @Test
    fun continueRunResumesFromAUserTurnAfterAFailedPrompt() = runTest {
        var calls = 0
        val provider = ScriptedProvider(
            listOf(
                {
                    calls++
                    throw RelayLlmException.Network("down")
                },
                {
                    calls++
                    ScriptedProvider.text("recovered")
                },
            ),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            transformContext = { it },
        )

        assertFailsWith<RelayLlmException.Network> { agent.prompt("hi").toList() }
        assertEquals(Role.USER, agent.state.messages.single().role)

        val events = agent.continueRun().toList()
        assertEquals("recovered", agent.state.messages.last().content)
        assertEquals(2, calls)
        assertTrue(events.last() is AgentEvent.AgentEnd)
    }

    @Test
    fun continueRunRejectsAnAssistantTail() = runTest {
        val agent = Agent(
            provider = ScriptedProvider(listOf { ScriptedProvider.text("ok") }),
            config = AgentConfig(model = "fake-model"),
            transformContext = { it },
        )
        agent.prompt("hi").toList()

        val error = assertFailsWith<AgentException.CannotContinue> {
            agent.continueRun().toList()
        }
        assertTrue(error.message!!.contains("ASSISTANT"))
    }

    @Test
    fun toolCallPastMaxTurnsReceivesSummarizeResult() = runTest {
        var echoCount = 0
        val provider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "echo", "{}")) },
                { ScriptedProvider.tools(ToolCall("2", "echo", "{}")) },
                { ScriptedProvider.text("final") },
            ),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", maxTurns = 1),
            tools = listOf(FunTool("echo") { echoCount++; "ok" }),
            transformContext = { it },
        )

        agent.prompt("loop").toList()

        assertEquals(1, echoCount)
        assertEquals("final", agent.state.messages.last().content)
        assertTrue(provider.receivedRequests[0].tools.isNotEmpty())
        assertTrue(provider.receivedRequests[1].tools.isNotEmpty())
        assertTrue(provider.receivedRequests[2].tools.isEmpty())
        val refused = agent.state.messages.filter { it.role == Role.TOOL }
        assertEquals(2, refused.size)
        assertEquals("ok", refused[0].content)
        assertTrue(refused[1].content!!.contains("Summarize"))
        assertFalse(agent.state.isRunning)
    }

    @Test
    fun cancellingStopsTheNextSequentialTool() = runTest {
        var secondRan = false
        val provider = ScriptedProvider(
            listOf(
                {
                    ScriptedProvider.tools(
                        ToolCall("1", "hang", "{}"),
                        ToolCall("2", "second", "{}"),
                    )
                },
            ),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(
                model = "fake-model",
                maxTurns = 2,
                toolExecution = ToolExecutionMode.Sequential,
            ),
            tools = listOf(
                FunTool("hang") { delay(60_000); "nope" },
                FunTool("second") { secondRan = true; "y" },
            ),
            transformContext = { it },
        )

        val job = launch { agent.prompt("x").toList() }
        testScheduler.advanceTimeBy(10)
        job.cancelAndJoin()

        assertFalse(secondRan)
        assertFalse(agent.state.isRunning)
        assertTrue(job.isCancelled)
    }

    @Test
    fun toolsOnAModelWithoutCapabilityFailFast() = runTest {
        val provider = ScriptedProvider(
            scripts = listOf { ScriptedProvider.text("should not run") },
            info = ScriptedProvider.toolsInfo(
                capabilities = setOf(Capability.STREAMING),
            ),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            tools = listOf(FunTool("echo") { "x" }),
            transformContext = { it },
        )

        val error = assertFailsWith<RelayLlmException.InvalidRequest> {
            agent.prompt("hi").toList()
        }
        assertTrue(error.message!!.contains("tools"))
        assertTrue(provider.receivedRequests.isEmpty())
    }

    @Test
    fun windowTrimShortensTheProviderRequestButNotWorkingMemory() = runTest {
        val provider = ScriptedProvider(
            scripts = listOf { ScriptedProvider.text("ok") },
            info = ScriptedProvider.toolsInfo(contextWindow = 24),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", maxTokens = 4),
        )
        agent.state.messages = listOf(
            Message.user("alpha-".repeat(20)),
            Message.assistant("beta-".repeat(20)),
        )

        agent.prompt("keep").toList()

        val sent = provider.receivedRequests.single().messages.filter { it.role != Role.SYSTEM }
        assertTrue(sent.size < agent.state.messages.size)
        assertEquals(4, agent.state.messages.size)
        assertEquals("keep", agent.state.messages.last { it.role == Role.USER }.content)
    }

    @Test
    fun augmenterPadsRequestWithoutWritingTranscript() = runTest {
        val provider = ScriptedProvider(listOf { ScriptedProvider.text("ok") })
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", systemPrompt = "sys"),
            contextAugmenters = listOf(
                ContextAugmenter { ContextAugmentation(listOf(Message.user("已知事实:\n- 用户 过敏 花生"))) },
            ),
        )

        agent.prompt("今晚能吃花生吗").toList()

        val sent = provider.receivedRequests.single().messages
        assertEquals("sys", sent.first { it.role == Role.SYSTEM }.content)
        assertTrue(sent.any { it.content == "已知事实:\n- 用户 过敏 花生" })
        assertEquals(listOf(Role.USER, Role.ASSISTANT), agent.state.messages.map { it.role })
        assertEquals("今晚能吃花生吗", agent.state.messages.first().content)
        assertFalse(agent.state.messages.any { it.content.orEmpty().contains("已知事实") })
    }

    @Test
    fun augmenterTokensAreReservedSoTranscriptStillTrims() = runTest {
        val provider = ScriptedProvider(
            scripts = listOf { ScriptedProvider.text("ok") },
            info = ScriptedProvider.toolsInfo(contextWindow = 40),
        )
        val pad = "MEM-" + "x".repeat(40)
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", maxTokens = 4),
            contextAugmenters = listOf(
                ContextAugmenter { ContextAugmentation(listOf(Message.user(pad))) },
            ),
        )
        agent.state.messages = listOf(
            Message.user("alpha-".repeat(20)),
            Message.assistant("beta-".repeat(20)),
        )

        agent.prompt("keep").toList()

        val sent = provider.receivedRequests.single().messages.filter { it.role != Role.SYSTEM }
        assertTrue(sent.any { it.content == pad })
        assertTrue(sent.any { it.content == "keep" })
        assertTrue(sent.none { it.content == "alpha-".repeat(20) })
        assertEquals("keep", agent.state.messages.last { it.role == Role.USER }.content)
        assertFalse(agent.state.messages.any { it.content == pad })
    }

    @Test
    fun augmenterRunsOnEveryProviderCall() = runTest {
        val seen = mutableListOf<Int>()
        val provider = ScriptedProvider(
            listOf(
                { ScriptedProvider.tools(ToolCall("1", "echo", "{}")) },
                { ScriptedProvider.text("after") },
            ),
        )
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model"),
            tools = listOf(FunTool("echo") { "ok" }),
            contextAugmenters = listOf(
                ContextAugmenter {
                    seen += it.size
                    ContextAugmentation.Empty
                },
            ),
        )

        agent.prompt("go").toList()

        assertEquals(listOf(1, 3), seen)
    }
}
