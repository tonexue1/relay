package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.llm.model.Message
import relay.memory.GRAPH_ASSISTANT
import relay.memory.PREDICATES
import relay.memory.agent.recalling

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayEvolutionTest {

    @Test
    fun goldStaysInsideClosedSetAndCoversRegressions() {
        val used = AssistantPlay.GOLD.map { it.p }.toSet()
        assertTrue(used.all { it in PREDICATES }, used.filter { it !in PREDICATES }.toString())
        assertTrue(AssistantPlay.MUST_COVER.all { it in used }, AssistantPlay.MUST_COVER.minus(used).toString())
    }

    @Test
    fun extractorPassesThroughModelJson() = runTest {
        val result = CloudTripleExtractor(
            RecordingProvider(AssistantPlay.noisyJson()),
            model = "fake-model",
        ).extract(GRAPH_ASSISTANT, AssistantPlay.DIALOGUE, listOf("play"))
        assertEquals(
            AssistantPlay.NOISY.map { Triple(it.s, it.p, it.o) }.toSet(),
            result.drafts.map { Triple(it.s, it.p, it.o) }.toSet(),
        )
        assertTrue(result.drafts.all { it.rawEventIds == listOf("play") })
    }

    @Test
    fun secondActRecallsStandingFacts() = runTest {
        val store = testStore()
        store.ingest(AssistantPlay.GOLD)
        for (cue in AssistantPlay.CUES) {
            val hit = store.query(GRAPH_ASSISTANT, cue.query)
            assertTrue(
                hit.facts.any { it.s == cue.s && it.p == cue.p && it.o == cue.o },
                "miss ${cue.p} ${cue.o} for 「${cue.query}」 → ${hit.render()}",
            )
        }
    }

    @Test
    fun recallingPadsWhenQueryHitsPeanut() = runTest {
        val store = testStore()
        store.ingest(AssistantPlay.GOLD)
        val padded = store.recalling(GRAPH_ASSISTANT)
            .augment(listOf(Message.user("今晚能吃花生吗")))
            .messages
            .single()
            .content
            .orEmpty()
        assertTrue(padded.contains("已知事实"))
        assertTrue(padded.contains("花生"))
        assertTrue(padded.contains("过敏"))
    }
}
