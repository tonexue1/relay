package relay.agent

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.token.HeuristicTokenCounter

class WindowTrimTest {

    private val counter = HeuristicTokenCounter()

    @Test
    fun dropsOldestNonSystemMessagesWithoutMutatingTheInput() {
        val original = listOf(
            Message.user("a".repeat(80)),
            Message.assistant("b".repeat(80)),
            Message.user("keep-me"),
        )
        val trim = WindowTrim(
            contextWindow = 40,
            tokenCounter = counter,
        )

        val projected = trim(original)

        assertTrue(projected.size < original.size)
        assertEquals(3, original.size)
        assertEquals("keep-me", projected.last().content)
        assertTrue(projected.none { it.content == original.first().content })
    }

    @Test
    fun neverDropsSystemMessages() {
        val messages = listOf(
            Message.system("sys"),
            Message.user("a".repeat(80)),
            Message.user("b".repeat(80)),
        )
        val projected = WindowTrim(contextWindow = 30, tokenCounter = counter)(messages)
        assertEquals(Role.SYSTEM, projected.first().role)
        assertEquals("sys", projected.first().content)
    }

    @Test
    fun dropsToolResultsTogetherWithTheAssistantTurnThatRequestedThem() {
        val messages = listOf(
            Message.user("first"),
            Message(
                role = Role.ASSISTANT,
                toolCalls = listOf(
                    relay.llm.model.ToolCall(id = "c1", name = "echo", argumentsJson = "{}"),
                ),
            ),
            Message.toolResult("c1", "huge-tool-result-" + "x".repeat(80)),
            Message.user("later"),
        )
        val projected = WindowTrim(contextWindow = 20, tokenCounter = counter)(messages)
        assertTrue(projected.none { it.role == Role.TOOL })
        assertEquals("later", projected.last().content)
    }
}
