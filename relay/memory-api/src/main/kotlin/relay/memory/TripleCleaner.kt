package relay.memory

internal data class CleanTriple(val s: String, val p: String, val o: String)

internal fun cleanTriples(
    triples: List<TripleDraft>,
    chunk: String = "",
): List<CleanTriple> {
    val kept = mutableListOf<CleanTriple>()
    for (draft in triples) {
        if (isNovelGraph(draft.graphId)) {
            cleanNovel(draft, chunk)?.let { kept += it }
            continue
        }
        var s = draft.s.trim()
        var p = draft.p.trim()
        var o = draft.o.trim()
        if (s == "助理" || o == "助理") continue
        if (o.endsWith("过敏") && p in setOf("likes", "dislikes", "prefers", "allergic_to")) {
            val stem = o.removeSuffix("过敏").trim()
            if (stem.isNotEmpty()) {
                o = stem
                p = "allergic_to"
            }
        }
        if (s == "用户" && p == "located_in") p = "lives_in"
        if (p in setOf("colleague_of", "friend_of", "spouse_of", "sibling_of") &&
            o == "用户" && s != "用户"
        ) {
            val tmp = s
            s = "用户"
            o = tmp
        }
        if (p == "parent_of" && o == "用户" && s != "用户") {
            o = s
            s = "用户"
            p = "child_of"
        }
        if (p == "child_of" && o == "用户" && s != "用户") {
            o = s
            s = "用户"
            p = "parent_of"
        }
        if (o in DIET_OBJECTS || o.endsWith("清真")) {
            p = "diet"
            o = if ("清真" in o) "清真" else "素食"
        }
        if (p in setOf("likes", "prefers") && o.isNotEmpty() && o.first() in "坐喝吃") {
            o = o.drop(1).trim()
        }
        if (p == "likes" && o.endsWith("咖啡") && o.length > 2) {
            o = o.removeSuffix("咖啡").trim()
        }
        if (o.endsWith("没做完") || o.endsWith("没写完") || o.endsWith("没交")) {
            val stem = o.removeSuffix("没做完").removeSuffix("没写完").removeSuffix("没交").trim()
            if (stem.isNotEmpty()) o = stem
            p = "has_task"
        }
        if (p in setOf("plans", "likes") &&
            (o == "作业" || o == "功课" || o.endsWith("作业")) &&
            (chunk.contains("没做") || chunk.contains("没写") || chunk.contains("没交") || chunk.isEmpty())
        ) {
            p = "has_task"
        }
        s = normalizeText(s)
        o = normalizeText(o)
        p = nfkcCompact(p)
        if (looksLikeYearSpan(o) &&
            (
                p in setOf("works_at", "works_as", "work_years", "plans", "likes") ||
                    chunk.contains("工作") ||
                    chunk.isEmpty()
                )
        ) {
            p = "work_years"
        }
        if (o in LANGUAGES && p in setOf("skilled_in", "knows_language", "likes")) {
            p = "knows_language"
        }
        if (s.isEmpty() || o.isEmpty() || s == o) continue
        if (p !in PREDICATES) continue
        if (p == "named" && o in PETS && s !in PETS) {
            val tmp = s
            s = o
            o = tmp
        }
        if (p == "named" && s !in PETS) continue
        if (p == "named" && o in PETS) continue
        if (p == "has_pet" && (s != "用户" || o !in PETS)) continue
        if (s != "用户" && p !in OTHER_SUBJECT_PREDICATES) continue
        if (chunk.isNotEmpty() && !(mentioned(s, chunk) && mentioned(o, chunk))) continue
        kept += CleanTriple(s, p, o)
    }

    val allergensBySubject = kept.filter { it.p == "allergic_to" }
        .groupBy { it.s }
        .mapValues { (_, rows) -> rows.map { it.o }.toSet() }
    val out = mutableListOf<CleanTriple>()
    val seen = mutableSetOf<Triple<String, String, String>>()
    for (item in kept) {
        if (item.p in setOf("likes", "prefers", "dislikes")) {
            val allergens = allergensBySubject[item.s].orEmpty()
            if (allergens.any { item.o == it || item.o.startsWith(it) || it.startsWith(item.o) }) {
                continue
            }
        }
        val key = Triple(item.s, item.p, item.o)
        if (!seen.add(key)) continue
        out += item
    }
    val pets = out.filter { it.p == "named" && it.s in PETS }.map { it.s }.toSet()
    for (pet in pets) {
        val implied = CleanTriple("用户", "has_pet", pet)
        val key = Triple(implied.s, implied.p, implied.o)
        if (seen.add(key)) out += implied
    }
    val kin = out.map { it.s } + out.map { it.o } + out.filter { it.p == "likes" || it.p == "dislikes" || it.p == "prefers" }.map { it.s }
    for (name in kin) {
        val parent = KINSHIP_CHILD_OF[name] ?: continue
        val implied = CleanTriple("用户", "child_of", parent)
        val key = Triple(implied.s, implied.p, implied.o)
        if (seen.add(key)) out += implied
    }
    return out
}

private fun cleanNovel(draft: TripleDraft, chunk: String): CleanTriple? {
    val s = normalizeText(draft.s)
    val o = normalizeText(draft.o)
    val p = nfkcCompact(draft.p)
    if (s.isEmpty() || o.isEmpty() || s == o) return null
    if (p !in NOVEL_PREDICATES) return null
    if (chunk.isNotEmpty()) {
        val objectOk = p == "appears_in" || mentioned(o, chunk)
        if (!(mentioned(s, chunk) && objectOk)) return null
    }
    return CleanTriple(s, p, o)
}

internal fun looksLikeYearSpan(value: String): Boolean =
    value.matches(Regex("^(两|三|四|五|六|七|八|九|十|\\d+)年$"))

internal fun mentioned(name: String, chunk: String): Boolean {
    if (chunk.isEmpty() || name == "用户") return true
    val hay = normalizeText(chunk)
    val needle = normalizeText(name)
    if (needle.isNotEmpty() && needle in hay) return true
    if (name in setOf("妈妈", "妈") && ("妈" in chunk || "母亲" in chunk)) return true
    if (name in setOf("爸爸", "爸") && ("爸" in chunk || "父亲" in chunk)) return true
    return false
}
