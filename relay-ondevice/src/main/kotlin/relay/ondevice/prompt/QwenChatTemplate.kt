package relay.ondevice.prompt

import relay.llm.model.Message
import relay.llm.model.Role

/**
 * Qwen2.5 ChatML formatter.
 *
 * Produces the prompt that Instruct checkpoints expect, ending with the assistant
 * header so the model continues from there.
 */
object QwenChatTemplate {

    fun format(messages: List<Message>): String {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val body = buildString {
            for (message in messages) {
                when (message.role) {
                    Role.SYSTEM, Role.USER, Role.ASSISTANT -> {
                        append("<|im_start|>")
                        append(roleName(message.role))
                        append('\n')
                        append(message.content.orEmpty())
                        append("<|im_end|>\n")
                    }
                    Role.TOOL -> error("QwenChatTemplate does not support TOOL turns")
                }
            }
            append("<|im_start|>assistant\n")
        }
        return body
    }

    private fun roleName(role: Role): String = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> error("unreachable")
    }
}
