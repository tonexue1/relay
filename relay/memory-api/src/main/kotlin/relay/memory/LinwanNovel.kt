package relay.memory

internal val LINWAN_CAST: Set<String> = setOf("林晚", "赵捕头", "王二", "阿秀", "司事", "县尊")

internal data class NovelChapter(
    val n: Int,
    val title: String,
    val facts: List<CleanTriple>,
) {
    fun drafts(graphId: String = GRAPH_LINWAN): List<TripleDraft> {
        val appears = LINWAN_CAST
            .filter { name -> facts.any { it.s == name || it.o == name } }
            .map { CleanTriple(it, "appears_in", "第${n}回") }
        return (facts + appears)
            .distinctBy { Triple(it.s, it.p, it.o) }
            .map { TripleDraft(graphId, it.s, it.p, it.o) }
    }
}

internal object LinwanNovel {
    val CHAPTERS: List<NovelChapter> = listOf(
        NovelChapter(
            1,
            "腰牌",
            listOf(
                CleanTriple("林晚", "is_a", "捕快"),
                CleanTriple("林晚", "has_item", "腰牌"),
                CleanTriple("林晚", "located_in", "码头"),
                CleanTriple("林晚", "related_to", "赵捕头"),
                CleanTriple("林晚", "related_to", "王二"),
                CleanTriple("赵捕头", "located_in", "货栈"),
                CleanTriple("王二", "status", "已死"),
            ),
        ),
        NovelChapter(
            2,
            "码头",
            listOf(
                CleanTriple("赵捕头", "is_a", "捕快"),
                CleanTriple("货栈", "located_in", "码头"),
                CleanTriple("林晚", "has_item", "账本"),
                CleanTriple("林晚", "located_in", "码头"),
                CleanTriple("林晚", "related_to", "赵捕头"),
                CleanTriple("账本", "foreshadow", "未收束"),
                CleanTriple("王二", "status", "已死"),
            ),
        ),
        NovelChapter(
            3,
            "西巷",
            listOf(
                CleanTriple("王二", "is_a", "脚夫"),
                CleanTriple("林晚", "located_in", "码头"),
                CleanTriple("林晚", "knows", "账本秘密"),
                CleanTriple("林晚", "knows", "假腰牌"),
                CleanTriple("赵捕头", "knows", "假腰牌"),
                CleanTriple("林晚", "related_to", "赵捕头"),
                CleanTriple("账本", "foreshadow", "未收束"),
            ),
        ),
        NovelChapter(
            4,
            "客栈",
            listOf(
                CleanTriple("林晚", "located_in", "客栈"),
                CleanTriple("客栈", "located_in", "码头"),
                CleanTriple("阿秀", "is_a", "店主"),
                CleanTriple("阿秀", "related_to", "王二"),
                CleanTriple("阿秀", "related_to", "林晚"),
                CleanTriple("林晚", "has_item", "旧牌"),
                CleanTriple("林晚", "wants", "翻案"),
            ),
        ),
        NovelChapter(
            5,
            "刀",
            listOf(
                CleanTriple("司事", "status", "受伤"),
                CleanTriple("林晚", "has_item", "短刀"),
                CleanTriple("林晚", "has_item", "腰牌"),
                CleanTriple("林晚", "located_in", "码头"),
                CleanTriple("阿秀", "has_item", "旧牌"),
                CleanTriple("林晚", "related_to", "赵捕头"),
            ),
        ),
        NovelChapter(
            6,
            "已死",
            listOf(
                CleanTriple("王二", "status", "已死"),
                CleanTriple("林晚", "has_item", "铁丝"),
                CleanTriple("林晚", "related_to", "赵捕头"),
                CleanTriple("账本", "foreshadow", "未收束"),
            ),
        ),
        NovelChapter(
            7,
            "师父",
            listOf(
                CleanTriple("林晚", "related_to", "赵捕头"),
                CleanTriple("王二", "related_to", "赵捕头"),
                CleanTriple("赵捕头", "knows", "假腰牌"),
                CleanTriple("林晚", "located_in", "客栈"),
                CleanTriple("林晚", "has_item", "旧牌"),
                CleanTriple("赵捕头", "is_a", "捕快"),
            ),
        ),
        NovelChapter(
            8,
            "知道",
            listOf(
                CleanTriple("林晚", "knows", "账本秘密"),
                CleanTriple("林晚", "wants", "翻案"),
                CleanTriple("林晚", "has_item", "腰牌"),
                CleanTriple("林晚", "has_item", "旧牌"),
                CleanTriple("林晚", "has_item", "铁丝"),
                CleanTriple("林晚", "has_item", "账本"),
                CleanTriple("赵捕头", "located_in", "货栈"),
                CleanTriple("阿秀", "related_to", "林晚"),
            ),
        ),
        NovelChapter(
            9,
            "翻案",
            listOf(
                CleanTriple("林晚", "located_in", "耳房"),
                CleanTriple("林晚", "wants", "翻案"),
                CleanTriple("阿秀", "has_item", "账本副本"),
                CleanTriple("账本", "foreshadow", "未收束"),
                CleanTriple("司事", "knows", "短刀"),
                CleanTriple("赵捕头", "knows", "假腰牌"),
                CleanTriple("林晚", "related_to", "赵捕头"),
            ),
        ),
        NovelChapter(
            10,
            "未收束",
            listOf(
                CleanTriple("林晚", "located_in", "耳房"),
                CleanTriple("林晚", "has_item", "旧牌"),
                CleanTriple("阿秀", "status", "失踪"),
                CleanTriple("账本", "foreshadow", "未收束"),
                CleanTriple("赵捕头", "related_to", "林晚"),
                CleanTriple("王二", "status", "已死"),
                CleanTriple("林晚", "related_to", "赵捕头"),
            ),
        ),
    )

    fun allDrafts(): List<TripleDraft> = CHAPTERS.flatMap { it.drafts() }
}

internal suspend fun MemoryStore.saveChapter(chapter: NovelChapter, graphId: String = GRAPH_LINWAN) {
    require(isNovelGraph(graphId)) { "saveChapter is for novel graphs" }
    ingest(chapter.drafts(graphId))
}
