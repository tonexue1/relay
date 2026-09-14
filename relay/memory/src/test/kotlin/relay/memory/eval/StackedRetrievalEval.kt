package relay.memory.eval

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.api.AddMemory
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.LexicalScorer
import relay.memory.api.SearchMemories
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.ensureSpace
import relay.memory.testContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StackedRetrievalEval {

    @Test
    fun gridCoverageVsBm25WritesTable() = runTest {
        val root = repoRoot()
        val json = Json { ignoreUnknownKeys = true }
        val facts = json.decodeFromString<List<StackedFact>>(
            File(root, "eval/memory/retrieval/stacked/facts.json").readText(),
        )
        val queries = json.decodeFromString<List<StackedQuery>>(
            File(root, "eval/memory/retrieval/stacked/queries.json").readText(),
        )
        assertEquals(100, facts.size)
        assertEquals(48, queries.size)

        val mem = SqliteLedgerRuntime(testContext())
        mem.ensureSpace(SPACE)
        val ids = LinkedHashMap<String, String>()
        val pending = facts.sortedWith(compareBy({ it.supersedes != null }, { it.t }))
        for (fact in pending) {
            val linked = fact.supersedes?.let { listOf(ids.getValue(it)) }.orEmpty()
            val added = mem.addMemory(
                AddMemory(
                    spaceId = SPACE,
                    ownerId = OWNER,
                    text = fact.text,
                    linkedMemoryIds = linked,
                    at = ClockStamp(ClockDomain.WALL_CLOCK, fact.t),
                ),
            )
            ids[fact.id] = added.id
        }
        val reverse = ids.entries.associate { it.value to it.key }
        val at = ClockStamp(ClockDomain.WALL_CLOCK, 10_000)

        val rows = mutableListOf<GridRow>()
        val byType = LinkedHashMap<String, MutableList<String>>()
        for (scorer in listOf(LexicalScorer.COVERAGE, LexicalScorer.BM25)) {
            for (thr in listOf(0.0, 0.3, 0.5)) {
                for (k in listOf(3, 5, 10)) {
                    val per = queries.map { q ->
                        val hits = mem.searchMemories(
                            SearchMemories(
                                spaceId = SPACE,
                                ownerId = OWNER,
                                query = q.query,
                                at = at,
                                limit = k,
                                minScore = thr,
                                lexical = scorer,
                            ),
                        )
                        val got = hits.mapNotNull { reverse[it.id] }.toSet()
                        val relevant = q.relevant.toSet()
                        val stale = q.stale.toSet()
                        val recall = if (relevant.isEmpty()) {
                            null
                        } else {
                            relevant.count { it in got }.toDouble() / relevant.size
                        }
                        val precision = when {
                            got.isEmpty() && relevant.isEmpty() -> 1.0
                            got.isEmpty() -> 0.0
                            else -> got.count { it in relevant }.toDouble() / got.size
                        }
                        QueryEval(
                            type = q.type,
                            recall = recall,
                            precision = precision,
                            chars = injectedChars(hits.map { it.text }),
                            stale = stale.any { it in got },
                            returned = hits.size,
                        )
                    }
                    val withGold = per.mapNotNull { it.recall }
                    val staleSlice = per.zip(queries).filter { it.second.stale.isNotEmpty() }
                    val row = GridRow(
                        scorer = scorer.name.lowercase(),
                        thr = thr,
                        k = k,
                        recall = withGold.average(),
                        precision = per.map { it.precision }.average(),
                        chars = per.map { it.chars }.average(),
                        stale = if (staleSlice.isEmpty()) {
                            0.0
                        } else {
                            staleSlice.count { it.first.stale }.toDouble() / staleSlice.size
                        },
                        noneN = per.filter { it.type == "none" }.map { it.returned }.average(),
                    )
                    rows += row
                    for (type in per.map { it.type }.distinct()) {
                        val slice = per.filter { it.type == type }
                        val r = slice.mapNotNull { it.recall }
                        val line = "| ${row.scorer} | ${fmt(thr)} | $k | $type | ${fmt(r.averageOrNa())} | ${fmt(slice.map { it.precision }.average())} |"
                        byType.getOrPut(type) { mutableListOf() }.add(line)
                    }
                }
            }
        }

        val table = buildString {
            appendLine("# stacked retrieval grid")
            appendLine()
            appendLine("Lexical only (no query vector). Mix is 0.7 lexical + 0.3 recency. BM25 min-max to 0–1 on the visible set.")
            appendLine()
            appendLine("| scorer | thr | K | R | P | chars | stale | none_n |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            for (row in rows) {
                appendLine(
                    "| ${row.scorer} | ${fmt(row.thr)} | ${row.k} | ${fmt(row.recall)} | ${fmt(row.precision)} | ${fmt(row.chars)} | ${fmt(row.stale)} | ${fmt(row.noneN)} |",
                )
            }
            appendLine()
            appendLine("## by type")
            appendLine()
            appendLine("| scorer | thr | K | type | R | P |")
            appendLine("|---|---:|---:|---|---:|---:|")
            for (type in listOf("lexical", "paraphrase", "update", "none", "distractor")) {
                byType[type].orEmpty().forEach { appendLine(it) }
            }
        }

        val outDir = File(root, "eval/memory/out")
        outDir.mkdirs()
        val out = File(outDir, "retrieval-grid.md")
        out.writeText(table)
        println(table)

        val baseline = rows.single { it.scorer == "coverage" && it.thr == 0.0 && it.k == 10 }
        assertTrue(baseline.recall > 0.3, "coverage@10 should retrieve some gold, was ${baseline.recall}")
        assertTrue(out.length() > 100)
    }
}

private const val SPACE = "assistant"
private const val OWNER = "user"

@Serializable
private data class StackedFact(
    val id: String,
    val text: String,
    val t: Long,
    val supersedes: String? = null,
)

@Serializable
private data class StackedQuery(
    val id: String,
    val query: String,
    val type: String,
    val relevant: List<String> = emptyList(),
    val stale: List<String> = emptyList(),
)

private data class QueryEval(
    val type: String,
    val recall: Double?,
    val precision: Double,
    val chars: Int,
    val stale: Boolean,
    val returned: Int,
)

private data class GridRow(
    val scorer: String,
    val thr: Double,
    val k: Int,
    val recall: Double,
    val precision: Double,
    val chars: Double,
    val stale: Double,
    val noneN: Double,
)

private fun injectedChars(texts: List<String>): Int {
    if (texts.isEmpty()) return 0
    return "相关记忆:\n".length + texts.sumOf { "- $it".length + 1 }
}

private fun fmt(value: Double): String =
    if (value.isNaN()) "—" else String.format(java.util.Locale.US, "%.3f", value)

private fun List<Double>.averageOrNa(): Double = if (isEmpty()) Double.NaN else average()

private fun repoRoot(): File {
    val start = System.getProperty("user.dir") ?: "."
    var dir = File(start).canonicalFile
    repeat(8) {
        if (File(dir, "eval/memory/retrieval/stacked/facts.json").isFile) return dir
        dir = dir.parentFile ?: return@repeat
    }
    error("stacked corpus not found from $start")
}
