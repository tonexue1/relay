package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.llm.model.Message

class PlayEvolutionTest {

    @Test
    fun goldStaysInsideClosedSetAndCoversRegressions() {
        val used = AssistantPlay.GOLD.map { it.p }.toSet()
        assertTrue(used.all { it in PREDICATES }, used.filter { it !in PREDICATES }.toString())
        assertTrue(AssistantPlay.MUST_COVER.all { it in used }, AssistantPlay.MUST_COVER.minus(used).toString())
    }

    @Test
    fun noisyCloudDumpCleansToGold() {
        val cleaned = cleanTriples(AssistantPlay.NOISY, chunk = AssistantPlay.DIALOGUE)
        assertEquals(AssistantPlay.goldKeys(), cleaned.map { Triple(it.s, it.p, it.o) }.toSet())
    }

    @Test
    fun extractorCleansPlayNoise() = runTest {
        val drafts = CloudTripleExtractor(
            RecordingProvider(AssistantPlay.noisyJson()),
            model = "fake-model",
        ).extract(GRAPH_ASSISTANT, AssistantPlay.DIALOGUE, listOf("play"))
        assertEquals(AssistantPlay.goldKeys(), drafts.map { Triple(it.s, it.p, it.o) }.toSet())
        assertTrue(drafts.all { it.rawEventIds == listOf("play") })
    }

    @Test
    fun secondActRecallsStandingFacts() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(AssistantPlay.GOLD.map { TripleDraft(GRAPH_ASSISTANT, it.s, it.p, it.o) })
        for (cue in AssistantPlay.CUES) {
            val hit = store.query(GRAPH_ASSISTANT, cue.query)
            assertTrue(
                hit.facts.any { it.s == cue.s && it.p == cue.p && it.o == cue.o },
                "miss ${cue.p} ${cue.o} for 「${cue.query}」 → ${hit.render()}",
            )
        }
    }

    @Test
    fun rememberingPadsFirepotWithoutSayingPeanut() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(AssistantPlay.GOLD.map { TripleDraft(GRAPH_ASSISTANT, it.s, it.p, it.o) })
        val out = store.remembering(GRAPH_ASSISTANT)(
            listOf(Message.user("今晚想吃火锅，有什么别踩的雷？")),
        )
        val padded = out.first().content.orEmpty()
        assertTrue(padded.contains("已知事实"))
        assertTrue(padded.contains("花生"))
        assertTrue(padded.contains("过敏"))
    }
}
