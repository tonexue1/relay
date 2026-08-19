package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.llm.model.Message
import relay.memory.GRAPH_ASSISTANT
import relay.memory.InMemoryMemoryStore
import relay.memory.PREDICATES

class PlayEvolutionTest {

    @Test
    fun goldStaysInsideClosedSetAndCoversRegressions() {
        val used = AssistantPlay.GOLD.map { it.p }.toSet()
        assertTrue(used.all { it in PREDICATES }, used.filter { it !in PREDICATES }.toString())
        assertTrue(AssistantPlay.MUST_COVER.all { it in used }, AssistantPlay.MUST_COVER.minus(used).toString())
    }

    @Test
    fun extractorPassesThroughModelJson() = runTest {
        val drafts = CloudTripleExtractor(
            RecordingProvider(AssistantPlay.noisyJson()),
            model = "fake-model",
        ).extract(GRAPH_ASSISTANT, AssistantPlay.DIALOGUE, listOf("play"))
        assertEquals(
            AssistantPlay.NOISY.map { Triple(it.s, it.p, it.o) }.toSet(),
            drafts.map { Triple(it.s, it.p, it.o) }.toSet(),
        )
        assertTrue(drafts.all { it.rawEventIds == listOf("play") })
    }

    @Test
    fun secondActRecallsStandingFacts() = runTest {
        val store = InMemoryMemoryStore()
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
    fun rememberingPadsWhenQueryHitsPeanut() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(AssistantPlay.GOLD)
        val out = store.remembering(GRAPH_ASSISTANT)(
            listOf(Message.user("今晚能吃花生吗")),
        )
        val padded = out.first().content.orEmpty()
        assertTrue(padded.contains("已知事实"))
        assertTrue(padded.contains("花生"))
        assertTrue(padded.contains("过敏"))
    }
}
