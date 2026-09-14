package relay.memory.engine

import kotlin.math.ln
import relay.memory.api.LexicalScorer

internal fun coverageScore(query: Set<String>, doc: Set<String>): Double {
    if (query.isEmpty() || doc.isEmpty()) return 0.0
    return query.count { it in doc }.toDouble() / query.size.toDouble()
}

internal fun lexicalScores(kind: LexicalScorer, query: String, docs: List<String>): List<Double> {
    val qList = queryTokenList(query)
    val qSet = qList.toSet()
    val docLists = docs.map { queryTokenList(it) }
    return when (kind) {
        LexicalScorer.COVERAGE -> docLists.map { coverageScore(qSet, it.toSet()) }
        LexicalScorer.BM25 -> minMaxNorm(bm25RawScores(qSet, docLists)).toList()
    }
}

internal fun bm25RawScores(
    query: Set<String>,
    docs: List<List<String>>,
    k1: Double = 1.2,
    b: Double = 0.75,
): DoubleArray {
    val n = docs.size
    if (n == 0) return DoubleArray(0)
    val df = HashMap<String, Int>()
    for (doc in docs) {
        val seen = HashSet<String>()
        for (token in doc) {
            if (token in query && seen.add(token)) {
                df[token] = (df[token] ?: 0) + 1
            }
        }
    }
    val avgdl = docs.map { it.size.toDouble() }.average().let { if (it == 0.0) 1.0 else it }
    return DoubleArray(n) { i ->
        val doc = docs[i]
        val tf = HashMap<String, Int>()
        for (token in doc) {
            if (token in query) tf[token] = (tf[token] ?: 0) + 1
        }
        var score = 0.0
        val dl = doc.size.coerceAtLeast(1).toDouble()
        for (term in query) {
            val freq = tf[term] ?: continue
            val nq = df[term] ?: 0
            val idf = ln((n - nq + 0.5) / (nq + 0.5) + 1.0)
            score += idf * freq * (k1 + 1.0) / (freq + k1 * (1.0 - b + b * dl / avgdl))
        }
        score
    }
}

internal fun minMaxNorm(raw: DoubleArray): DoubleArray {
    if (raw.isEmpty()) return raw
    val min = raw.min()
    val max = raw.max()
    if (max - min < 1e-12) {
        return DoubleArray(raw.size) { if (max > 0.0) 1.0 else 0.0 }
    }
    return DoubleArray(raw.size) { (raw[it] - min) / (max - min) }
}
