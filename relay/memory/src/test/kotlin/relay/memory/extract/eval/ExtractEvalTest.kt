package relay.memory.extract.eval

import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import relay.memory.PREDICATES

class ExtractEvalTest {

    @Test
    fun corpusIdsAreUniqueAndGoldStaysInClosedSet() {
        val ids = ExtractEvalCorpus.samples.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }}")
        val used = mutableSetOf<String>()
        for (sample in ExtractEvalCorpus.samples) {
            for (triple in sample.gold + sample.prior + sample.forbidden) {
                assertTrue(triple.p in PREDICATES, "${sample.id} unknown p=${triple.p}")
                assertTrue(triple.s.isNotBlank() && triple.o.isNotBlank(), sample.id)
            }
            assertTrue(sample.gold.none { it.s == "助理" }, "${sample.id} gold has 助理")
            assertTrue(
                (sample.goldClaims + sample.forbiddenClaims).all {
                    it.subject.isNotBlank() && it.text.isNotBlank()
                },
                "${sample.id} has blank claim",
            )
            val aliases = mergedAliases(sample.aliases)
            val goldKeys = sample.gold.map { it.key(aliases) }.toSet()
            val forbiddenKeys = sample.forbidden.map { it.key(aliases) }.toSet()
            assertTrue(goldKeys.intersect(forbiddenKeys).isEmpty(), "${sample.id} gold overlaps forbidden")
            used += sample.gold.map { it.p }
            val self = scoreExtract(sample, sample.gold)
            assertEquals(0, self.fp, sample.id)
            assertEquals(0, self.fn, sample.id)
            assertEquals(0, self.forbiddenHits, sample.id)
            val claimSelf = scoreClaims(sample, sample.goldClaims)
            assertEquals(0, claimSelf.fp, sample.id)
            assertEquals(0, claimSelf.fn, sample.id)
            assertEquals(0, claimSelf.forbiddenHits, sample.id)
        }
        assertTrue(PREDICATES.all { it in used }, "uncovered predicates: ${PREDICATES.minus(used)}")
    }

    @Test
    fun intensitySplitIsLargeEnough() {
        val traps = ExtractEvalCorpus.samples.filter { "trap" in it.tags }
        val bulk = ExtractEvalCorpus.samples.filter { "bulk" in it.tags }
        assertTrue(traps.size >= 35, "traps=${traps.size}")
        assertTrue(bulk.size >= 100, "bulk=${bulk.size}")
        assertTrue(ExtractEvalCorpus.samples.size >= 140, "total=${ExtractEvalCorpus.samples.size}")
        assertTrue(traps.any { "buried" in it.tags })
        assertTrue(traps.any { "retract" in it.tags })
    }

    @Test
    fun aliasesSaveNearMissObjects() {
        val sample = ExtractEvalCorpus.samples.single { it.id == "trap-quit-means-job-change-and-rest" }
        val pred = listOf(g("用户", "plans", "离职"), g("用户", "plans", "休息"))
        val score = scoreExtract(sample, pred)
        assertEquals(0, score.fn)
        assertEquals(0, score.fp)
    }
}

internal fun liveEvalRequested(): Boolean {
    val flag = System.getProperty("relay.liveEval").orEmpty().ifBlank {
        System.getenv("RELAY_LIVE_EVAL").orEmpty()
    }
    return flag.equals("true", ignoreCase = true) || flag.equals("all", ignoreCase = true)
}

internal fun liveEvalAll(): Boolean {
    val flag = System.getProperty("relay.liveEval").orEmpty().ifBlank {
        System.getenv("RELAY_LIVE_EVAL").orEmpty()
    }
    return flag.equals("all", ignoreCase = true)
}

internal fun deepseekKey(): String? {
    System.getenv("RELAY_DEEPSEEK_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val props = Properties()
    val file = repoRoot().resolve("local.properties")
    if (!file.isRegularFile()) return null
    file.inputStream().use { props.load(it) }
    return props.getProperty("relay.deepseek.apiKey")?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun repoRoot(): java.nio.file.Path {
    var dir = Path("").toAbsolutePath()
    while (true) {
        if (dir.resolve("settings.gradle.kts").exists()) return dir
        dir = dir.parent ?: error("settings.gradle.kts not found from ${Path("").toAbsolutePath()}")
    }
}
