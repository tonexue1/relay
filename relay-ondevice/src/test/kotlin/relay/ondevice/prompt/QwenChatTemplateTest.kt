package relay.ondevice.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.llm.model.Message
import relay.llm.model.Role

class QwenChatTemplateTest {

    @Test
    fun formatsSystemAndUserTurnsIntoChatMlEndingWithAssistantHeader() {
        val prompt = QwenChatTemplate.format(
            listOf(
                Message.system("be brief"),
                Message.user("hello"),
            ),
        )

        assertEquals(
            """
            |<|im_start|>system
            |be brief<|im_end|>
            |<|im_start|>user
            |hello<|im_end|>
            |<|im_start|>assistant
            |""".trimMargin(),
            prompt,
        )
    }

    @Test
    fun includesPriorAssistantTurns() {
        val prompt = QwenChatTemplate.format(
            listOf(
                Message.user("hi"),
                Message.assistant("hey"),
                Message.user("again"),
            ),
        )

        assertTrue(prompt.contains("<|im_start|>assistant\nhey<|im_end|>\n"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsToolTurns() {
        QwenChatTemplate.format(
            listOf(
                Message.user("call a tool"),
                Message(role = Role.TOOL, content = "{}", toolCallId = "1"),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyMessageList() {
        QwenChatTemplate.format(emptyList())
    }
}
