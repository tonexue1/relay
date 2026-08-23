package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.model.Capability
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.ModelInfo
import relay.llm.model.ProviderInfo
import relay.llm.model.Usage
import relay.memory.dream.AgentConsolidator
import relay.memory.extract.MemoryExtractor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryRuntimeTest {

    @Test
    fun emptyDraftConsumesIdleChat() = runTest {
        val store = testStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = "今天天气怎么样"))
        val memory = MemoryRuntime(store, FixedExtractor(emptyList()))

        val report = memory.learn(GRAPH_ASSISTANT)

        assertEquals(listOf(id), report.eventIds)
        assertTrue(report.drafts.isEmpty())
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
    }

    @Test
    fun extractFailureLeavesQueueForRetry() = runTest {
        val store = testStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = "我花生过敏"))
        val memory = MemoryRuntime(
            store,
            object : MemoryExtractor {
                override suspend fun extract(
                    graphId: String,
                    dialogue: String,
                    rawEventIds: List<String>,
                    priorFacts: List<Fact>,
                ): ExtractResult = error("cloud down")
            },
        )

        assertFailsWith<IllegalStateException> { memory.learn(GRAPH_ASSISTANT) }
        assertEquals(1, store.unconsumed(GRAPH_ASSISTANT).size)
    }

    @Test
    fun mixedBatchWritesLegalDraftsAndConsumesEvents() = runTest {
        val store = testStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = "我花生过敏"))
        val memory = MemoryRuntime(
            store,
            FixedExtractor(
                listOf(
                    TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生", rawEventIds = listOf(id)),
                    TripleDraft(GRAPH_ASSISTANT, "用户", "not_a_pred", "x", rawEventIds = listOf(id)),
                ),
            ),
        )

        val report = memory.learn(GRAPH_ASSISTANT)

        assertEquals(1, report.errors.size)
        assertEquals("unknown predicate", report.errors.single().reason)
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.any { it.p == "allergic_to" && it.o == "花生" })
        assertTrue(store.unconsumed(GRAPH_ASSISTANT).isEmpty())
    }

    @Test
    fun parseFailureLeavesBatchForRetry() = runTest {
        val store = testStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我做过车管家", sessionId = "s"))
        val memory = MemoryRuntime(
            store,
            object : MemoryExtractor {
                override suspend fun extract(
                    graphId: String,
                    dialogue: String,
                    rawEventIds: List<String>,
                    priorFacts: List<Fact>,
                ): ExtractResult = ExtractResult(
                    outcome = ExtractOutcome.PARSE_FAILED,
                    raw = "{",
                    finishReason = FinishReason.STOP,
                    errors = listOf("bad json"),
                )
            },
        )

        val report = memory.learn(GRAPH_ASSISTANT, "s")

        assertEquals(ExtractOutcome.PARSE_FAILED, report.outcome)
        assertEquals(1, store.unconsumed(GRAPH_ASSISTANT, "s").size)
    }

    @Test
    fun claimOnlyBatchPersistsAndConsumes() = runTest {
        val store = testStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "鸿蒙项目用了策略仓库", sessionId = "s"))
        val memory = MemoryRuntime(
            store,
            object : MemoryExtractor {
                override suspend fun extract(
                    graphId: String,
                    dialogue: String,
                    rawEventIds: List<String>,
                    priorFacts: List<Fact>,
                ): ExtractResult = ExtractResult(
                    outcome = ExtractOutcome.SUCCESS,
                    claims = listOf(
                        ClaimDraft(
                            graphId,
                            "用户",
                            "用户在鸿蒙项目中用策略模式实现 Repository",
                            rawEventIds,
                        ),
                    ),
                    finishReason = FinishReason.STOP,
                )
            },
        )

        memory.learn(GRAPH_ASSISTANT, "s")

        assertEquals(1, store.claims(GRAPH_ASSISTANT).size)
        assertTrue(store.unconsumed(GRAPH_ASSISTANT, "s").isEmpty())
    }

    @Test
    fun nextTurnRecallsWhatLearnWrote() = runTest {
        val store = testStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, role = "user", text = "我花生过敏"))
        val memory = MemoryRuntime(
            store,
            FixedExtractor(
                listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生", rawEventIds = listOf(id))),
            ),
        )
        memory.learn(GRAPH_ASSISTANT)
        val pad = memory.recalling(GRAPH_ASSISTANT)
            .augment(listOf(Message.user("今晚能吃花生吗")))
            .messages
            .single()
            .content
            .orEmpty()
        assertTrue(pad.contains("花生"))
        assertTrue(pad.contains("过敏"))
    }

    @Test
    fun consolidatorGetsNightToolsBoundToGraph() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职")))
        val provider = ScriptChat("没有近义。")
        val report = AgentConsolidator(provider, store, model = "fake-model")
            .consolidate(GRAPH_ASSISTANT, since = 0)

        assertEquals("没有近义。", report.summary)
        val names = provider.streams.single().tools.map { it.name }.toSet()
        assertTrue("memory_merge_nodes" in names)
        assertTrue("memory_recent" in names)
        assertFalse("memory_query" in names)
    }
}

private class ScriptChat(private val reply: String) : Provider {
    override val info: ProviderInfo = ProviderInfo(
        id = "script",
        models = listOf(
            ModelInfo(
                "fake-model",
                contextWindow = 4_096,
                capabilities = setOf(Capability.STREAMING, Capability.TOOLS),
            ),
        ),
    )
    val streams = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse =
        ChatResponse(Message.assistant(reply), Usage(1, 1, 2), FinishReason.STOP)

    override fun stream(request: ChatRequest): Flow<ChatChunk> {
        streams += request
        return flow {
            if (reply.isNotEmpty()) emit(ChatChunk.Text(reply))
            emit(ChatChunk.Done(Usage(1, reply.length, 1 + reply.length), FinishReason.STOP))
        }
    }
}

private class FixedExtractor(
    private val drafts: List<TripleDraft>,
) : MemoryExtractor {
    override suspend fun extract(
        graphId: String,
        dialogue: String,
        rawEventIds: List<String>,
        priorFacts: List<Fact>,
    ): ExtractResult = ExtractResult(
        outcome = if (drafts.isEmpty()) ExtractOutcome.SUCCESS_EMPTY else ExtractOutcome.SUCCESS,
        drafts = drafts,
        finishReason = FinishReason.STOP,
    )
}
