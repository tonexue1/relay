package relay.memory.extract.eval

/** Night-consolidation gold. Engine applies [DreamSample.merges]; live agent is optional. */
internal object DreamEvalCorpus {

    val samples: List<DreamSample> by lazy { named + volume }

    private val named: List<DreamSample> = listOf(
        sample(
            "dream-quit-into-job-change",
            "merge", "plans",
            seed = listOf(
                g("用户", "plans", "离职"),
                g("用户", "plans", "跳槽"),
                g("用户", "likes", "火锅"),
            ),
            merges = listOf(DreamMerge(keep = "跳槽", drop = "离职")),
            liveMust = listOf(g("用户", "plans", "跳槽"), g("用户", "likes", "火锅")),
            liveMustNot = listOf(g("用户", "plans", "离职")),
        ),
        sample(
            "dream-job-change-alias-only-drop",
            "merge", "plans",
            seed = listOf(
                g("用户", "plans", "换工作"),
                g("用户", "allergic_to", "花生"),
            ),
            merges = listOf(DreamMerge(keep = "跳槽", drop = "换工作")),
            liveMust = listOf(g("用户", "plans", "跳槽"), g("用户", "allergic_to", "花生")),
            liveMustNot = listOf(g("用户", "plans", "换工作")),
        ),
        sample(
            "dream-americano-node",
            "merge", "taste",
            seed = listOf(
                g("用户", "likes", "美式咖啡"),
                g("用户", "likes", "手冲"),
            ),
            merges = listOf(DreamMerge(keep = "美式", drop = "美式咖啡")),
            liveMust = listOf(g("用户", "likes", "美式"), g("用户", "likes", "手冲")),
            liveMustNot = listOf(g("用户", "likes", "美式咖啡")),
        ),
        sample(
            "dream-peanut-butter-allergy",
            "merge", "allergy",
            seed = listOf(
                g("用户", "allergic_to", "花生酱"),
                g("用户", "allergic_to", "花生"),
                g("妈妈", "likes", "花生"),
            ),
            merges = listOf(DreamMerge(keep = "花生", drop = "花生酱")),
            liveMust = listOf(
                g("用户", "allergic_to", "花生"),
                g("妈妈", "likes", "花生"),
            ),
            liveMustNot = listOf(g("用户", "allergic_to", "花生酱")),
        ),
        sample(
            "dream-mom-alias-node",
            "merge", "family",
            seed = listOf(
                g("用户", "child_of", "我妈"),
                g("我妈", "likes", "花生"),
                g("用户", "child_of", "妈妈"),
            ),
            merges = listOf(DreamMerge(keep = "妈妈", drop = "我妈")),
            liveMust = listOf(
                g("用户", "child_of", "妈妈"),
                g("妈妈", "likes", "花生"),
            ),
            liveMustNot = listOf(
                g("用户", "child_of", "我妈"),
                g("我妈", "likes", "花生"),
            ),
        ),
        sample(
            "dream-subway-object",
            "merge", "prefer",
            seed = listOf(g("用户", "prefers", "坐地铁"), g("用户", "dislikes", "开车")),
            merges = listOf(DreamMerge(keep = "地铁", drop = "坐地铁")),
            liveMust = listOf(g("用户", "prefers", "地铁"), g("用户", "dislikes", "开车")),
            liveMustNot = listOf(g("用户", "prefers", "坐地铁")),
        ),
        sample(
            "dream-gongke-into-homework",
            "merge", "task",
            seed = listOf(g("用户", "has_task", "功课"), g("用户", "has_task", "报销")),
            merges = listOf(DreamMerge(keep = "作业", drop = "功课")),
            liveMust = listOf(g("用户", "has_task", "作业"), g("用户", "has_task", "报销")),
            liveMustNot = listOf(g("用户", "has_task", "功课")),
        ),
        sample(
            "dream-vegetarian-alias",
            "merge", "diet",
            seed = listOf(g("用户", "diet", "吃素"), g("用户", "likes", "米线")),
            merges = listOf(DreamMerge(keep = "素食", drop = "吃素")),
            liveMust = listOf(g("用户", "diet", "素食"), g("用户", "likes", "米线")),
            liveMustNot = listOf(g("用户", "diet", "吃素")),
        ),
        sample(
            "dream-english-alias",
            "merge", "lang",
            seed = listOf(g("用户", "knows_language", "英文"), g("用户", "skilled_in", "Kotlin")),
            merges = listOf(DreamMerge(keep = "英语", drop = "英文")),
            liveMust = listOf(g("用户", "knows_language", "英语"), g("用户", "skilled_in", "Kotlin")),
            liveMustNot = listOf(g("用户", "knows_language", "英文")),
        ),
        sample(
            "dream-hangzhou-city-suffix",
            "merge", "place",
            seed = listOf(
                g("用户", "lives_in", "杭州市"),
                g("用户", "work_location", "西溪"),
            ),
            merges = listOf(DreamMerge(keep = "杭州", drop = "杭州市")),
            liveMust = listOf(g("用户", "lives_in", "杭州"), g("用户", "work_location", "西溪")),
            liveMustNot = listOf(g("用户", "lives_in", "杭州市")),
        ),
        sample(
            "dream-two-pets-keep-both-names",
            "merge", "pet",
            seed = listOf(
                g("用户", "has_pet", "猫"),
                g("猫", "named", "芝麻"),
                g("用户", "has_pet", "橘猫"),
            ),
            merges = listOf(DreamMerge(keep = "猫", drop = "橘猫")),
            liveMust = listOf(g("用户", "has_pet", "猫"), g("猫", "named", "芝麻")),
            liveMustNot = listOf(g("用户", "has_pet", "橘猫")),
        ),
        sample(
            "dream-colleague-nickname",
            "merge", "people",
            seed = listOf(
                g("用户", "colleague_of", "老王"),
                g("用户", "colleague_of", "王磊"),
                g("王磊", "likes", "围棋"),
            ),
            merges = listOf(DreamMerge(keep = "王磊", drop = "老王")),
            liveMust = listOf(
                g("用户", "colleague_of", "王磊"),
                g("王磊", "likes", "围棋"),
            ),
            liveMustNot = listOf(g("用户", "colleague_of", "老王")),
        ),
        sample(
            "no-merge-cities",
            "no-merge", "place",
            seed = listOf(
                g("用户", "lives_in", "上海"),
                g("用户", "plans", "杭州"),
                g("妈妈", "lives_in", "宁波"),
            ),
            liveMust = listOf(
                g("用户", "lives_in", "上海"),
                g("用户", "plans", "杭州"),
                g("妈妈", "lives_in", "宁波"),
            ),
        ),
        sample(
            "no-merge-allergens",
            "no-merge", "allergy",
            seed = listOf(
                g("用户", "allergic_to", "花生"),
                g("用户", "allergic_to", "青霉素"),
                g("妈妈", "likes", "花生"),
            ),
            liveMust = listOf(
                g("用户", "allergic_to", "花生"),
                g("用户", "allergic_to", "青霉素"),
                g("妈妈", "likes", "花生"),
            ),
        ),
        sample(
            "no-merge-two-friends",
            "no-merge", "people",
            seed = listOf(
                g("用户", "friend_of", "李娜"),
                g("用户", "colleague_of", "王磊"),
            ),
            liveMust = listOf(
                g("用户", "friend_of", "李娜"),
                g("用户", "colleague_of", "王磊"),
            ),
        ),
        sample(
            "no-merge-cat-and-dog",
            "no-merge", "pet",
            seed = listOf(
                g("用户", "has_pet", "猫"),
                g("猫", "named", "芝麻"),
                g("用户", "has_pet", "狗"),
                g("狗", "named", "豆豆"),
            ),
            liveMust = listOf(
                g("用户", "has_pet", "猫"),
                g("用户", "has_pet", "狗"),
                g("猫", "named", "芝麻"),
                g("狗", "named", "豆豆"),
            ),
        ),
        sample(
            "no-merge-company-and-role",
            "no-merge", "work",
            seed = listOf(
                g("用户", "works_at", "阿里"),
                g("用户", "works_as", "客户端"),
                g("用户", "work_years", "两年"),
            ),
            liveMust = listOf(
                g("用户", "works_at", "阿里"),
                g("用户", "works_as", "客户端"),
                g("用户", "work_years", "两年"),
            ),
        ),
        sample(
            "dream-clutter-then-quit-merge",
            "merge", "clutter",
            seed = fillerLikes(40) + listOf(
                g("用户", "plans", "离职"),
                g("用户", "plans", "跳槽"),
                g("用户", "plans", "考研"),
            ),
            merges = listOf(DreamMerge(keep = "跳槽", drop = "离职")),
            liveMust = fillerLikes(40) + listOf(
                g("用户", "plans", "跳槽"),
                g("用户", "plans", "考研"),
            ),
            liveMustNot = listOf(g("用户", "plans", "离职")),
        ),
        sample(
            "dream-chain-two-aliases-to-canonical",
            "merge", "chain",
            seed = listOf(
                g("用户", "plans", "离职"),
                g("用户", "plans", "换工作"),
                g("用户", "plans", "跳槽"),
            ),
            merges = listOf(
                DreamMerge(keep = "跳槽", drop = "离职"),
                DreamMerge(keep = "跳槽", drop = "换工作"),
            ),
            liveMust = listOf(g("用户", "plans", "跳槽")),
            liveMustNot = listOf(
                g("用户", "plans", "离职"),
                g("用户", "plans", "换工作"),
            ),
        ),
    )

    private val volume: List<DreamSample> = listOf(
        run {
            val pairs = (1..40).map { i -> "别名$i" to "正名$i" }
            val filler = (1..80).map { i -> g("用户", "owns", "物$i") }
            val seed = pairs.flatMap { (drop, keep) ->
                listOf(g("用户", "likes", drop), g("用户", "likes", keep))
            } + filler
            sample(
                "dream-volume-40-pairs",
                "volume", "merge",
                seed = seed,
                merges = pairs.map { (drop, keep) -> DreamMerge(keep = keep, drop = drop) },
                liveMust = pairs.map { (_, keep) -> g("用户", "likes", keep) } + filler,
                liveMustNot = pairs.map { (drop, _) -> g("用户", "likes", drop) },
            )
        },
        run {
            val people = (1..25).map { i -> "外号$i" to "同事$i" }
            sample(
                "dream-volume-25-people",
                "volume", "merge", "people",
                seed = people.flatMap { (drop, keep) ->
                    listOf(
                        g("用户", "colleague_of", drop),
                        g("用户", "colleague_of", keep),
                        g(keep, "likes", "茶"),
                    )
                },
                merges = people.map { (drop, keep) -> DreamMerge(keep = keep, drop = drop) },
                liveMust = people.flatMap { (_, keep) ->
                    listOf(g("用户", "colleague_of", keep), g(keep, "likes", "茶"))
                },
                liveMustNot = people.map { (drop, _) -> g("用户", "colleague_of", drop) },
            )
        },
    )

    val liveSubsetIds: Set<String> = setOf(
        "dream-quit-into-job-change",
        "dream-americano-node",
        "no-merge-cities",
    )

    private fun fillerLikes(n: Int): List<GoldTriple> =
        (1..n).map { i -> g("用户", "likes", "食物$i") }

    private fun sample(
        id: String,
        vararg tags: String,
        seed: List<GoldTriple>,
        merges: List<DreamMerge> = emptyList(),
        liveMust: List<GoldTriple> = emptyList(),
        liveMustNot: List<GoldTriple> = emptyList(),
    ): DreamSample = DreamSample(
        id = id,
        tags = tags.toSet(),
        seed = seed,
        merges = merges,
        liveMust = liveMust,
        liveMustNot = liveMustNot,
    )
}
