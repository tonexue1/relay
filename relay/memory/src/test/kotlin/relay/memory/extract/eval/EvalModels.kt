package relay.memory.extract.eval

import relay.memory.Fact
import relay.memory.TripleDraft

internal data class GoldTriple(
    val s: String,
    val p: String,
    val o: String,
    val retract: Boolean = false,
) {
    fun key(aliases: Map<String, String>): TripleKey =
        TripleKey(alias(s, aliases), p, alias(o, aliases), retract).folded()

    fun asFact(): Fact = Fact(s, p, o)

    fun asDraft(graphId: String): TripleDraft =
        TripleDraft(graphId, s, p, o, retract = retract)
}

internal data class GoldClaim(
    val text: String,
    val subject: String = "用户",
)

internal data class TripleKey(val s: String, val p: String, val o: String, val retract: Boolean = false) {
    fun folded(): TripleKey = TripleKey(s.lowercase(), p, o.lowercase(), retract)
}

internal data class ExtractSample(
    val id: String,
    val tags: Set<String>,
    val dialogue: String,
    val gold: List<GoldTriple>,
    val prior: List<GoldTriple> = emptyList(),
    val forbidden: List<GoldTriple> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val goldClaims: List<GoldClaim> = emptyList(),
    val forbiddenClaims: List<GoldClaim> = emptyList(),
)

internal data class DreamMerge(val keep: String, val drop: String)

internal data class DreamSample(
    val id: String,
    val tags: Set<String>,
    val seed: List<GoldTriple>,
    val merges: List<DreamMerge> = emptyList(),
    val liveMust: List<GoldTriple> = emptyList(),
    val liveMustNot: List<GoldTriple> = emptyList(),
)

internal data class Score(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val forbiddenHits: Int,
) {
    val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
    val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
}

internal val EVAL_ALIASES: Map<String, String> = mapOf(
    "花生米" to "花生",
    "花生酱" to "花生",
    "美式咖啡" to "美式",
    "坐地铁" to "地铁",
    "离职" to "跳槽",
    "换工作" to "跳槽",
    "说拜拜" to "跳槽",
    "吃素" to "素食",
    "素食主义" to "素食",
    "我妈" to "妈妈",
    "我爸" to "爸爸",
    "功课" to "作业",
    "两年了" to "两年",
    "2年" to "两年",
    "三年了" to "三年",
    "英文" to "英语",
    "杭州市" to "杭州",
)

internal fun alias(text: String, extra: Map<String, String> = emptyMap()): String {
    val trimmed = text.trim()
    return extra[trimmed] ?: EVAL_ALIASES[trimmed] ?: trimmed
}

internal fun mergedAliases(extra: Map<String, String>): Map<String, String> = EVAL_ALIASES + extra

internal fun scoreExtract(sample: ExtractSample, predicted: List<GoldTriple>): Score {
    val aliases = mergedAliases(sample.aliases)
    val gold = sample.gold.map { it.key(aliases) }.toSet()
    val pred = predicted.map { it.key(aliases) }.toSet()
    val forbidden = sample.forbidden.map { it.key(aliases) }.toSet()
    val tp = gold.intersect(pred).size
    val fp = pred.minus(gold).size
    val fn = gold.minus(pred).size
    val forbiddenHits = pred.intersect(forbidden).size
    return Score(tp, fp, fn, forbiddenHits)
}

internal fun scoreClaims(sample: ExtractSample, predicted: List<GoldClaim>): Score {
    fun key(claim: GoldClaim): String = (claim.subject + ":" + claim.text)
        .lowercase()
        .replace(Regex("[\\s，。；、,:：+/_-]+"), "")
    val gold = sample.goldClaims.map(::key).toSet()
    val pred = predicted.map(::key).toSet()
    val forbidden = sample.forbiddenClaims.map(::key).toSet()
    val matchedGold = gold.filter { expected ->
        pred.any { actual -> actual.contains(expected) || expected.contains(actual) }
    }.toSet()
    val matchedPred = pred.filter { actual ->
        gold.any { expected -> actual.contains(expected) || expected.contains(actual) }
    }.toSet()
    return Score(
        tp = matchedGold.size,
        fp = pred.minus(matchedPred).size,
        fn = gold.minus(matchedGold).size,
        forbiddenHits = pred.count { actual ->
            forbidden.any { blocked -> actual.contains(blocked) || blocked.contains(actual) }
        },
    )
}

internal fun chat(user: String, assistant: String = "记下了。"): String =
    "用户: $user\n助理: $assistant"

internal fun g(s: String, p: String, o: String, retract: Boolean = false): GoldTriple =
    GoldTriple(s, p, o, retract)
