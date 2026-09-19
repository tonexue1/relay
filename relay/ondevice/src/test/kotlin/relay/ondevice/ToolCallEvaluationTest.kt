package relay.ondevice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import relay.llm.Provider
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.ChatResponse
import relay.llm.model.ProviderInfo
import relay.llm.model.ToolCallDelta
import relay.llm.model.ToolDef

class ToolCallEvaluationTest {

    @Test
    fun defaultCasesContainTwentyChineseTimeRequests() {
        assertEquals(20, ToolCallEvaluation.defaultCases.size)
    }

    @Test
    fun runCountsOnlyExpectedToolCallsAsSuccessful() = runTest {
        val result = ToolCallEvaluation.run(
            provider = object : Provider {
                override val info = ProviderInfo(id = "fake", models = emptyList())

                override suspend fun chat(request: ChatRequest): ChatResponse = error("unused")

                override fun stream(request: ChatRequest): Flow<ChatChunk> = flowOf(
                    ChatChunk.ToolCalls(
                        ToolCallDelta(
                            index = 0,
                            name = if (request.messages.single().content == "现在几点") {
                                "get_current_time"
                            } else {
                                "wrong_tool"
                            },
                            argumentsDelta = "{}",
                        ),
                    ),
                    ChatChunk.Done(),
                )
            },
            model = "qwen",
            tools = listOf(ToolDef("get_current_time", parameters = buildJsonObject {})),
            cases = listOf(
                ToolCallEvaluation.Case("time", "现在几点", "get_current_time"),
                ToolCallEvaluation.Case("date", "今天几号", "get_current_time"),
            ),
        )

        assertEquals(2, result.total)
        assertEquals(1, result.successes)
        assertEquals(listOf("date"), result.failures.map { it.case.id })
    }
}
