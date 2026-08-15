package relay.llm.token

import kotlin.math.ceil
import relay.llm.model.Message
import relay.llm.model.ToolDef

/**
 * Estimates prompt size *before* a call.
 *
 * Actual billing comes from [relay.llm.model.Usage] in the response; this abstraction
 * exists for the decisions that must be made beforehand -- trimming history to fit
 * [relay.llm.model.ModelInfo.contextWindow], routing a prompt on-device vs to the cloud,
 * or rejecting an oversized request without paying for it.
 *
 * Implementations are per-tokenizer. A real BPE tokenizer can be dropped in later without
 * touching callers.
 */
interface TokenCounter {

    fun count(text: String, model: String = ""): Int

    fun count(messages: List<Message>, model: String = ""): Int

    /** Named distinctly because generic erasure would collide with the [Message] overload. */
    fun countTools(tools: List<ToolDef>, model: String = ""): Int
}

/**
 * Character-class heuristic, no tokenizer data required.
 *
 * BPE tokenizers split CJK far more finely than Latin text, so a single chars-per-token
 * ratio is wrong for mixed Chinese/English prompts -- the two classes are counted
 * separately. Expect roughly +/-15% against a real tokenizer: good enough for budgeting,
 * not for billing.
 */
class HeuristicTokenCounter(
    private val charsPerTokenLatin: Double = 4.0,
    private val charsPerTokenCjk: Double = 1.7,
    private val perMessageOverhead: Int = 4,
    private val replyPrimingTokens: Int = 3,
) : TokenCounter {

    override fun count(text: String, model: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { if (it.isCjk()) cjk++ else other++ }
        return ceil(cjk / charsPerTokenCjk + other / charsPerTokenLatin).toInt()
    }

    override fun count(messages: List<Message>, model: String): Int {
        if (messages.isEmpty()) return 0
        val body = messages.sumOf { message ->
            perMessageOverhead +
                count(message.content.orEmpty(), model) +
                message.toolCalls.sumOf { count(it.name, model) + count(it.argumentsJson, model) }
        }
        return body + replyPrimingTokens
    }

    /** Tool schemas are injected into the prompt by the server and therefore billed as input. */
    override fun countTools(tools: List<ToolDef>, model: String): Int =
        tools.sumOf { tool ->
            perMessageOverhead +
                count(tool.name, model) +
                count(tool.description.orEmpty(), model) +
                count(tool.parameters.toString(), model)
        }
}

private fun Char.isCjk(): Boolean = when (code) {
    in 0x2E80..0x2EFF,   // CJK radicals supplement
    in 0x3000..0x303F,   // CJK symbols and punctuation
    in 0x3040..0x30FF,   // Hiragana + Katakana
    in 0x3400..0x4DBF,   // CJK extension A
    in 0x4E00..0x9FFF,   // CJK unified ideographs
    in 0xAC00..0xD7AF,   // Hangul syllables
    in 0xF900..0xFAFF,   // CJK compatibility ideographs
    in 0xFF00..0xFFEF,   // Halfwidth and fullwidth forms
    -> true
    else -> false
}
