package relay.assistant.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import relay.llm.model.Message
import relay.uikit.ChatTurn
import relay.uikit.TurnItem
import relay.uikit.CardSpec
import relay.uikit.ChoiceFormSpec
import relay.uikit.ChoiceOption
import relay.uikit.ChoiceQuestion
import relay.uikit.FileSpec

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    @Test
    fun `turns survive session persistence`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SessionStore(context)
        val session = AssistantSession(
            id = "session",
            title = "真实会话",
            updatedAt = 1,
            turns = listOf(
                ChatTurn("turn", "user", listOf(TurnItem.Text("text", "你好 Relay"))),
            ),
        )

        store.save(listOf(session))

        assertEquals(session, store.load().single())
    }

    @Test
    fun `memory preference persists`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SessionStore(context)

        store.saveMemoryEnabled(false)

        assertEquals(false, store.loadMemoryEnabled())
    }

    @Test
    fun `submitted choice form survives session persistence`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SessionStore(context)
        val form = ChoiceFormSpec(
            title = "研究方式",
            taskAnchor = "规划 Android 学习路径",
            questions = listOf(
                ChoiceQuestion(
                    id = "path",
                    title = "从哪里开始？",
                    options = listOf(ChoiceOption("architecture", "架构总览")),
                ),
            ),
            submittedAnswers = mapOf("path" to listOf("architecture")),
        )
        val session = AssistantSession(
            id = "choice-session",
            title = "选择题",
            updatedAt = 2,
            turns = listOf(
                ChatTurn("assistant", "assistant", listOf(TurnItem.Widget("widget", "call", form))),
            ),
        )

        store.save(listOf(session))

        val restored = store.load().single().turns.single().items.single() as TurnItem.Widget
        val restoredForm = restored.spec as ChoiceFormSpec
        assertEquals("规划 Android 学习路径", restoredForm.taskAnchor)
        assertEquals(mapOf("path" to listOf("architecture")), restoredForm.submittedAnswers)
        val transcript = store.load().single().turns.toAgentTranscript()
        assertEquals(2, transcript.size)
        assertTrue(transcript[0].content.orEmpty().contains("研究方式"))
        assertTrue(transcript[1].content.orEmpty().contains("架构总览"))
        assertTrue(transcript[1].content.orEmpty().contains("规划 Android 学习路径"))
    }

    @Test
    fun `research session starts with host question`() {
        val session = SessionStore.research("H5")

        assertEquals("研究 · H5", session.title)
        assertEquals("H5", session.researchEntity)
        assertEquals("research:h5", session.memoryScopeId)
        assertEquals(
            "已为「H5」开启扩展研究。你想先研究哪个方面？",
            (session.turns.single().items.single() as TurnItem.Text).text,
        )
    }

    @Test
    fun `fresh session uses itself as memory scope`() {
        val session = SessionStore.fresh()

        assertEquals(session.id, session.effectiveMemoryScopeId)
    }

    @Test
    fun `visible user and assistant text maps to agent transcript without tool process`() {
        val turns = listOf(
            ChatTurn(
                "user",
                "user",
                listOf(TurnItem.Text("user-text", "你好 Relay")),
            ),
            ChatTurn(
                "assistant",
                "assistant",
                listOf(
                    TurnItem.Text("before-tool", "先检查"),
                    TurnItem.Process(
                        id = "process",
                        callId = "call",
                        label = "memory_query",
                        argumentsSummary = """{"query":"私密工具参数"}""",
                        resultSummary = "私密工具结果",
                    ),
                    TurnItem.Text("after-tool", "再回答"),
                ),
            ),
            ChatTurn(
                "tool-shaped-ui-turn",
                "tool",
                listOf(TurnItem.Text("tool-text", "不能成为模型历史")),
            ),
            ChatTurn(
                "process-only",
                "assistant",
                listOf(
                    TurnItem.Process(
                        id = "only-process",
                        callId = "only-call",
                        label = "memory_facts",
                        argumentsSummary = "{}",
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                Message.user("你好 Relay"),
                Message.assistant("先检查\n再回答"),
            ),
            turns.toAgentTranscript(),
        )
    }

    @Test
    fun `cold start restores widget and artifact summaries without tool process`() {
        val turns = listOf(
            ChatTurn("user", "user", listOf(TurnItem.Text("ask", "给我一张卡片"))),
            ChatTurn(
                "assistant",
                "assistant",
                listOf(
                    TurnItem.Text("say", "已生成卡片"),
                    TurnItem.Process(
                        id = "process",
                        callId = "call",
                        label = "render_card",
                        argumentsSummary = """{"title":"私密"}""",
                    ),
                    TurnItem.Widget(
                        "widget",
                        "call",
                        CardSpec(title = "研究进度", body = "已完成架构分层"),
                    ),
                    TurnItem.Artifact(
                        "file",
                        "write",
                        FileSpec(
                            artifactId = "art-1",
                            artifactVersion = 1,
                            name = "android-notes.md",
                            mime = "text/markdown",
                            status = "ready",
                            summaryText = "分层与 ART",
                        ),
                    ),
                ),
            ),
        )

        val transcript = turns.toAgentTranscript()

        assertEquals(2, transcript.size)
        assertEquals("给我一张卡片", transcript[0].content)
        assertTrue(transcript[1].content.orEmpty().contains("已生成卡片"))
        assertTrue(transcript[1].content.orEmpty().contains("研究进度"))
        assertTrue(transcript[1].content.orEmpty().contains("android-notes.md"))
        assertTrue(transcript.none { it.content.orEmpty().contains("私密") })
    }
}
