package relay.agent

import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.model.ToolDef
import relay.llm.token.HeuristicTokenCounter
import relay.llm.token.TokenCounter

/** Builds the temporary message view sent to the model for one agent turn. */
fun interface ContextComposer {
    suspend fun compose(input: ContextInput): ContextView
}

data class ContextInput(
    val transcript: List<Message>,
    val systemPrompt: String,
    val tools: List<ToolDef>,
    val model: String,
    val contextWindow: Int,
    val reserveOutputTokens: Int,
)

data class ContextView(
    val messages: List<Message>,
)

/** Projects the normal transcript before its size is reduced for a request. */
fun interface ContextProjector {
    suspend fun project(transcript: List<Message>): List<Message>
}

/** Selects the transcript portion that fits in the request budget. */
fun interface ContextReducer {
    suspend fun reduce(input: ContextReductionInput): ContextReduction
}

data class ContextReductionInput(
    val transcript: List<Message>,
    val budgetTokens: Int,
    val model: String,
    val tokenCounter: TokenCounter,
)

data class ContextReduction(
    val messages: List<Message>,
)

/** Current sliding-window behavior, exposed as the default reduction strategy. */
class WindowTrimReducer : ContextReducer {
    override suspend fun reduce(input: ContextReductionInput): ContextReduction = ContextReduction(
        messages = WindowTrim(
            contextWindow = input.budgetTokens,
            tokenCounter = input.tokenCounter,
            model = input.model,
        )(input.transcript),
    )
}

/**
 * Default request-context pipeline.
 *
 * The source transcript is never mutated. Projection, additions, and reduction only affect the
 * returned [ContextView].
 */
class DefaultContextComposer(
    private val projector: ContextProjector = ContextProjector { it },
    private val augmenters: List<ContextAugmenter> = emptyList(),
    private val reducer: ContextReducer = WindowTrimReducer(),
    private val tokenCounter: TokenCounter = HeuristicTokenCounter(),
) : ContextComposer {
    override suspend fun compose(input: ContextInput): ContextView {
        val additions = buildList {
            for (augmenter in augmenters) {
                addAll(augmenter.augment(input.transcript).messages)
            }
        }
        val projected = projector.project(input.transcript)
        val budget = (
            input.contextWindow -
                input.reserveOutputTokens -
                reservedTokens(input, additions)
            ).coerceAtLeast(1)
        val reduced = reducer.reduce(
            ContextReductionInput(
                transcript = projected,
                budgetTokens = budget,
                model = input.model,
                tokenCounter = tokenCounter,
            ),
        )
        return ContextView(withSystem(input.systemPrompt, additions + reduced.messages))
    }

    private fun reservedTokens(input: ContextInput, additions: List<Message>): Int {
        val systemTokens = if (input.systemPrompt.isBlank()) {
            0
        } else {
            tokenCounter.count(listOf(Message.system(input.systemPrompt)), input.model)
        }
        return systemTokens +
            tokenCounter.countTools(input.tools, input.model) +
            tokenCounter.count(additions, input.model)
    }

    private fun withSystem(systemPrompt: String, messages: List<Message>): List<Message> {
        val withoutSystem = messages.filter { it.role != Role.SYSTEM }
        if (systemPrompt.isBlank()) return withoutSystem
        return listOf(Message.system(systemPrompt)) + withoutSystem
    }
}
