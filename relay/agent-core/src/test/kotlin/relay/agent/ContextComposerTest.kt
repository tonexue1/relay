package relay.agent

import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.llm.model.Message
import relay.llm.model.ToolDef

class ContextComposerTest {

    @Test
    fun composesProjectedTranscriptAndTemporaryAdditionsWithoutMutatingTranscript() = runTest {
        val original = listOf(
            Message.user("discard"),
            Message.user("keep"),
        )
        val composer = DefaultContextComposer(
            projector = ContextProjector { messages -> messages.drop(1) },
            augmenters = listOf(
                ContextAugmenter {
                    ContextAugmentation(listOf(Message.user("remembered fact")))
                },
            ),
        )

        val view = composer.compose(
            ContextInput(
                transcript = original,
                systemPrompt = "system",
                tools = emptyList<ToolDef>(),
                model = "fake-model",
                contextWindow = 1_000,
                reserveOutputTokens = 0,
            ),
        )

        assertEquals(listOf("discard", "keep"), original.map { it.content })
        assertEquals(
            listOf("system", "remembered fact", "keep"),
            view.messages.map { it.content },
        )
    }
}
