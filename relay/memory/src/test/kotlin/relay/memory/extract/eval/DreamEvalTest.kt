package relay.memory.extract.eval

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.GRAPH_ASSISTANT
import relay.memory.PREDICATES
import relay.memory.extract.testStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DreamEvalTest {

    @Test
    fun corpusIdsAreUniqueAndSeedStaysInClosedSet() {
        val ids = DreamEvalCorpus.samples.map { it.id }
        assertTrue(ids.size == ids.toSet().size, "duplicate ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }}")
        for (sample in DreamEvalCorpus.samples) {
            for (triple in sample.seed + sample.liveMust + sample.liveMustNot) {
                assertTrue(triple.p in PREDICATES, "${sample.id} unknown p=${triple.p}")
            }
            if ("no-merge" in sample.tags) {
                assertTrue(sample.merges.isEmpty(), sample.id)
            }
        }
        assertTrue(DreamEvalCorpus.samples.any { "volume" in it.tags })
        assertTrue(DreamEvalCorpus.samples.any { "no-merge" in it.tags })
        val mergeCount = DreamEvalCorpus.samples.sumOf { it.merges.size }
        assertTrue(mergeCount >= 60, "merges=$mergeCount")
    }

    @Test
    fun goldMergesYieldExpectedLiveGraph() = runTest {
        for (sample in DreamEvalCorpus.samples) {
            val store = testStore()
            val ingested = store.ingest(sample.seed.map { it.asDraft(GRAPH_ASSISTANT) })
            assertTrue(ingested.errors.isEmpty(), "${sample.id} ${ingested.errors}")
            for (merge in sample.merges) {
                store.mergeNodes(GRAPH_ASSISTANT, keep = merge.keep, drop = merge.drop)
            }
            val live = store.facts(GRAPH_ASSISTANT).facts
                .map { TripleKey(it.s, it.p, it.o).folded() }
                .toSet()
            for (must in sample.liveMust) {
                assertTrue(
                    TripleKey(must.s, must.p, must.o).folded() in live,
                    "${sample.id} missing ${must.s} ${must.p} ${must.o}\n$live",
                )
            }
            for (mustNot in sample.liveMustNot) {
                assertTrue(
                    TripleKey(mustNot.s, mustNot.p, mustNot.o).folded() !in live,
                    "${sample.id} still live ${mustNot.s} ${mustNot.p} ${mustNot.o}",
                )
            }
        }
    }
}
