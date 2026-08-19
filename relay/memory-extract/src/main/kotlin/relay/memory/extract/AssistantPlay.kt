package relay.memory.extract

import relay.memory.GRAPH_ASSISTANT
import relay.memory.TripleDraft

/**
 * Regression play for extract → ingest → recall.
 * Lives next to the cloud extractor, not in the store.
 */
object AssistantPlay {
    const val SAMPLE_PROMPT: String =
        "我妈爱吃花生，今晚她来杭州吃饭。我自己花生过敏，火锅底料得分开。家里猫叫芝麻。" +
            "我作业没做完。白天在阿里做客户端，工作两年了，办公还在西溪。浙大毕业的。同事王磊，明天评审 JNI 留给他。" +
            "咖啡我喝美式，不爱香菜。通勤宁可坐地铁。最近吃素。医生让我吃钙片。下周去上海住静安。英语还能对付。朋友李娜。我打算离职。"

    internal val DIALOGUE: String =
        """
        |用户: 我妈爱吃花生，今晚她来杭州吃饭。我自己花生过敏，火锅底料得分开。家里猫叫芝麻。
        |助理: 妈妈花生、你过敏、芝麻。
        |用户: 我作业没做完。白天在阿里做客户端，工作两年了，办公还在西溪。浙大毕业的。同事王磊，明天评审 JNI 留给他。
        |助理: 作业、阿里、两年、西溪、王磊。
        |用户: 咖啡我喝美式，不爱香菜。通勤宁可坐地铁。最近吃素。医生让我吃钙片。下周去上海住静安。英语还能对付。朋友李娜。我打算离职。
        |助理: 记下了。
        """.trimMargin()

    internal val GOLD: List<TripleDraft> = listOf(
        TripleDraft(GRAPH_ASSISTANT, "用户", "child_of", "妈妈"),
        TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "lives_in", "杭州"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "has_pet", "猫"),
        TripleDraft(GRAPH_ASSISTANT, "猫", "named", "芝麻"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "has_task", "作业"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "阿里"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "works_as", "客户端"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "work_years", "两年"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "work_location", "西溪"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "alumni_of", "浙大"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "colleague_of", "王磊"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "dislikes", "香菜"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "prefers", "地铁"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "diet", "素食"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "takes", "钙片"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "上海"),
        TripleDraft(GRAPH_ASSISTANT, "上海", "located_in", "静安"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "knows_language", "英语"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "friend_of", "李娜"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职"),
    )

    internal val MUST_COVER: Set<String> = setOf(
        "allergic_to",
        "likes",
        "has_task",
        "work_years",
        "child_of",
        "lives_in",
        "works_at",
        "has_pet",
        "named",
        "diet",
        "takes",
        "plans",
        "colleague_of",
        "prefers",
    )

    internal val NOISY: List<TripleDraft> = listOf(
        TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "花生过敏"),
        TripleDraft(GRAPH_ASSISTANT, "芝麻", "named", "猫"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "作业没做完"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "两年"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "阿里"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "works_as", "客户端"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "located_in", "杭州"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "work_location", "西溪"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "alumni_of", "浙大"),
        TripleDraft(GRAPH_ASSISTANT, "王磊", "colleague_of", "用户"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式咖啡"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "dislikes", "香菜"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "prefers", "坐地铁"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "吃素"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "takes", "钙片"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "上海"),
        TripleDraft(GRAPH_ASSISTANT, "上海", "located_in", "静安"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "skilled_in", "英语"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "friend_of", "李娜"),
        TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "离职"),
    )

    internal val CUES: List<PlayCue> = listOf(
        PlayCue("花生", "用户", "allergic_to", "花生"),
        PlayCue("妈妈", "妈妈", "likes", "花生"),
        PlayCue("作业", "用户", "has_task", "作业"),
        PlayCue("两年", "用户", "work_years", "两年"),
        PlayCue("芝麻", "猫", "named", "芝麻"),
        PlayCue("阿里", "用户", "works_at", "阿里"),
        PlayCue("地铁", "用户", "prefers", "地铁"),
        PlayCue("素食", "用户", "diet", "素食"),
        PlayCue("钙片", "用户", "takes", "钙片"),
        PlayCue("上海", "用户", "plans", "上海"),
        PlayCue("王磊", "用户", "colleague_of", "王磊"),
        PlayCue("英语", "用户", "knows_language", "英语"),
    )

    internal fun goldKeys(): Set<Triple<String, String, String>> =
        GOLD.map { Triple(it.s, it.p, it.o) }.toSet()

    internal fun goldJson(): String = encodeTriples(GOLD.map { Triple(it.s, it.p, it.o) })

    internal fun noisyJson(): String = encodeTriples(NOISY.map { Triple(it.s, it.p, it.o) })

    private fun encodeTriples(rows: List<Triple<String, String, String>>): String =
        rows.joinToString(prefix = """{"triples":[""", postfix = "]}", separator = ",") {
            """{"s":"${it.first}","p":"${it.second}","o":"${it.third}"}"""
        }
}

internal data class PlayCue(
    val query: String,
    val s: String,
    val p: String,
    val o: String,
)