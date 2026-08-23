package relay.memory.extract.eval

import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.llm.provider.DeepSeek
import relay.memory.GRAPH_ASSISTANT
import relay.memory.agent.graphTools
import relay.memory.dream.DREAM_SYSTEM
import relay.memory.extract.CloudTripleExtractor
import relay.memory.extract.testStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveMemoryEvalTest {

    @Test
    fun cloudExtractScoresAgainstGold() = runBlocking {
        assumeTrue("pass -Drelay.liveEval=true (or =all) to hit DeepSeek", liveEvalRequested())
        val key = deepseekKey()
        assumeTrue("set RELAY_DEEPSEEK_API_KEY or local.properties relay.deepseek.apiKey", !key.isNullOrBlank())

        val samples = if (liveEvalAll()) {
            ExtractEvalCorpus.samples
        } else {
            ExtractEvalCorpus.samples.filter { "bulk" !in it.tags }
        }
        val extractor = CloudTripleExtractor(
            DeepSeek.provider(apiKey = key!!, httpClient = httpClient()),
        )
        var tp = 0
        var fp = 0
        var fn = 0
        var forbidden = 0
        var claimTp = 0
        var claimFp = 0
        var claimFn = 0
        var claimForbidden = 0
        val misses = mutableListOf<String>()
        for (sample in samples) {
            val result = extractor.extract(
                graphId = GRAPH_ASSISTANT,
                dialogue = sample.dialogue,
                rawEventIds = listOf(sample.id),
                priorFacts = sample.prior.map { it.asFact() },
            )
            val pred = result.drafts.map { GoldTriple(it.s, it.p, it.o, it.retract) }
            val score = scoreExtract(sample, pred)
            val claimPred = result.claims.map { GoldClaim(it.text, it.subject) }
            val claimScore = scoreClaims(sample, claimPred)
            tp += score.tp
            fp += score.fp
            fn += score.fn
            forbidden += score.forbiddenHits
            claimTp += claimScore.tp
            claimFp += claimScore.fp
            claimFn += claimScore.fn
            claimForbidden += claimScore.forbiddenHits
            if (
                score.fn > 0 || score.fp > 0 || score.forbiddenHits > 0 ||
                claimScore.fn > 0 || claimScore.fp > 0 || claimScore.forbiddenHits > 0
            ) {
                misses += "${sample.id} P=${"%.2f".format(score.precision)} R=${"%.2f".format(score.recall)} " +
                    "forb=${score.forbiddenHits} claimP=${"%.2f".format(claimScore.precision)} " +
                    "claimR=${"%.2f".format(claimScore.recall)} pred=$pred claims=$claimPred"
            }
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        val claimPrecision = if (claimTp + claimFp == 0) 1.0 else claimTp.toDouble() / (claimTp + claimFp)
        val claimRecall = if (claimTp + claimFn == 0) 1.0 else claimTp.toDouble() / (claimTp + claimFn)
        val report = buildString {
            append("extract n=${samples.size} tp=$tp fp=$fp fn=$fn ")
            append("P=${"%.3f".format(precision)} R=${"%.3f".format(recall)} forbidden=$forbidden")
            append(" claimP=${"%.3f".format(claimPrecision)} claimR=${"%.3f".format(claimRecall)}")
            if (misses.isNotEmpty()) {
                append("\n")
                append(misses.joinToString("\n"))
            }
        }
        println(report)
        assertTrue(forbidden == 0, report)
        assertTrue(claimForbidden == 0, report)
        assertTrue(precision >= 0.70, report)
        assertTrue(recall >= 0.50, report)
        assertTrue(claimPrecision >= 0.70, report)
        assertTrue(claimRecall >= 0.50, report)
    }

    @Test
    fun nightAgentMergesGoldSubset() = runBlocking {
        assumeTrue("pass -Drelay.liveEval=true to hit DeepSeek", liveEvalRequested())
        val key = deepseekKey()
        assumeTrue("set RELAY_DEEPSEEK_API_KEY or local.properties relay.deepseek.apiKey", !key.isNullOrBlank())

        val provider = DeepSeek.provider(apiKey = key!!, httpClient = httpClient())
        val failures = mutableListOf<String>()
        for (sample in DreamEvalCorpus.samples.filter { it.id in DreamEvalCorpus.liveSubsetIds }) {
            val store = testStore()
            store.ingest(sample.seed.map { it.asDraft(GRAPH_ASSISTANT) })
            val tools = store.graphTools(GRAPH_ASSISTANT).filter { it.def.name in NIGHT_TOOLS }
            val agent = Agent(
                provider = provider,
                config = AgentConfig(
                    model = DeepSeek.CHAT,
                    systemPrompt = DREAM_SYSTEM,
                    maxTurns = 8,
                    timeoutMillis = 90_000,
                ),
                tools = tools,
            )
            val since = System.currentTimeMillis() - SEVEN_DAYS_MS
            agent.prompt(
                "since=$since。查看 recent 和需要的 neighborhood，把近义节点 merge_nodes " +
                    "（例如 离职→跳槽、美式咖啡→美式）。不要把不同城市或不同人并在一起。不要编造新事实。" +
                    "做完用中文说改了什么。",
            ).toList()
            val live = store.facts(GRAPH_ASSISTANT).facts.map { TripleKey(it.s, it.p, it.o).folded() }.toSet()
            for (must in sample.liveMust) {
                if (TripleKey(must.s, must.p, must.o).folded() !in live) {
                    failures += "${sample.id} missing ${must.s} ${must.p} ${must.o} live=$live"
                }
            }
            for (mustNot in sample.liveMustNot) {
                if (TripleKey(mustNot.s, mustNot.p, mustNot.o).folded() in live) {
                    failures += "${sample.id} still live ${mustNot.s} ${mustNot.p} ${mustNot.o}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        val NIGHT_TOOLS = setOf(
            "memory_recent",
            "memory_neighborhood",
            "memory_merge_nodes",
            "memory_facts",
            "memory_ingest",
        )
    }
}
