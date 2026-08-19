package relay.memory

const val GRAPH_ASSISTANT: String = "assistant"
const val GRAPH_LINWAN: String = "novel:linwan"

internal fun isNovelGraph(graphId: String): Boolean = graphId.startsWith("novel:")

val PREDICATES: Set<String> = linkedSetOf(
    "allergic_to",
    "likes",
    "dislikes",
    "prefers",
    "diet",
    "lives_in",
    "work_location",
    "born_in",
    "works_at",
    "works_as",
    "alumni_of",
    "member_of",
    "skilled_in",
    "knows_language",
    "colleague_of",
    "friend_of",
    "family_of",
    "spouse_of",
    "parent_of",
    "child_of",
    "sibling_of",
    "has_pet",
    "named",
    "owns",
    "takes",
    "attends",
    "plans",
    "has_task",
    "work_years",
    "located_in",
)

val PREDICATE_ZH: Map<String, String> = mapOf(
    "allergic_to" to "过敏",
    "likes" to "喜欢",
    "dislikes" to "不喜欢",
    "prefers" to "更倾向",
    "diet" to "饮食",
    "lives_in" to "住在",
    "work_location" to "办公地",
    "born_in" to "出生于",
    "works_at" to "就职于",
    "works_as" to "职位是",
    "alumni_of" to "毕业于",
    "member_of" to "属于",
    "skilled_in" to "擅长",
    "knows_language" to "会说",
    "colleague_of" to "同事是",
    "friend_of" to "朋友是",
    "family_of" to "家人是",
    "spouse_of" to "配偶是",
    "parent_of" to "子女是",
    "child_of" to "父母是",
    "sibling_of" to "兄弟姐妹是",
    "has_pet" to "养宠物",
    "named" to "名叫",
    "owns" to "拥有",
    "takes" to "在服用",
    "attends" to "参加",
    "plans" to "打算",
    "has_task" to "待办",
    "work_years" to "工龄",
    "located_in" to "位于",
)

val FUNCTIONAL_PREDICATES: Set<String> = setOf(
    "lives_in",
    "work_location",
    "born_in",
    "works_at",
    "works_as",
    "spouse_of",
    "diet",
    "work_years",
)

internal val DEFAULT_ALIASES: Map<String, String> = mapOf(
    "美式咖啡" to "美式",
    "坐地铁" to "地铁",
    "花生酱" to "花生",
    "花生米" to "花生",
    "杭州市" to "杭州",
    "离职" to "跳槽",
    "换工作" to "跳槽",
    "吃素" to "素食",
    "素食主义" to "素食",
    "我妈" to "妈妈",
    "我爸" to "爸爸",
    "功课" to "作业",
    "两年了" to "两年",
    "2年" to "两年",
    "英文" to "英语",
)

internal val OTHER_SUBJECT_PREDICATES: Set<String> = setOf(
    "named",
    "lives_in",
    "located_in",
    "born_in",
    "work_location",
    "family_of",
    "spouse_of",
    "parent_of",
    "child_of",
    "sibling_of",
    "friend_of",
    "likes",
    "dislikes",
    "prefers",
    "allergic_to",
    "diet",
    "takes",
    "plans",
    "has_task",
    "work_years",
    "owns",
)

internal val PREDICATE_QUERY_HINTS: Map<String, List<String>> = mapOf(
    "allergic_to" to listOf("过敏", "火锅", "忌口", "踩雷", "蘸料"),
    "diet" to listOf("饮食", "吃素", "素食", "清真", "吃肉", "荤"),
    "takes" to listOf("在服用", "吃药", "停药", "维生素", "钙片", "药"),
    "has_task" to listOf("作业", "功课", "待办", "做完了"),
    "work_years" to listOf("工龄", "工作几", "工作多", "工作两", "工作年"),
    "works_at" to listOf("就职于", "哪上班", "在哪上班", "公司"),
    "works_as" to listOf("职位是", "岗位"),
    "work_location" to listOf("办公地", "办公"),
    "lives_in" to listOf("住在", "住哪"),
    "prefers" to listOf("更倾向", "通勤"),
    "has_pet" to listOf("养宠物", "猫叫", "养狗"),
    "named" to listOf("名叫", "叫什么"),
    "plans" to listOf("打算", "下周去", "跳槽"),
    "colleague_of" to listOf("同事是", "同事"),
    "child_of" to listOf("父母是", "我妈", "我爸"),
    "likes" to listOf("喜欢", "爱吃"),
    "knows_language" to listOf("会说", "英语"),
    "alumni_of" to listOf("毕业于", "毕业"),
    "related_to" to listOf("相关", "师父", "徒儿"),
    "wants" to listOf("想要", "翻案"),
    "foreshadow" to listOf("伏笔", "未收束"),
    "has_item" to listOf("持有", "腰牌", "旧牌", "账本"),
    "status" to listOf("状态", "已死", "失踪"),
    "knows" to listOf("知道", "秘密", "假腰牌"),
    "appears_in" to listOf("出场于"),
)

internal val LANGUAGES: Set<String> = setOf("英语", "中文", "汉语", "日语", "法语", "德语", "韩语", "西班牙语")

internal val DIET_OBJECTS: Set<String> = setOf("吃素", "素食", "素食主义", "素", "清真")

internal val PETS: Set<String> = setOf("猫", "狗", "宠物")

internal val KINSHIP_CHILD_OF: Map<String, String> = mapOf(
    "妈妈" to "妈妈",
    "妈" to "妈妈",
    "母亲" to "妈妈",
    "爸爸" to "爸爸",
    "爸" to "爸爸",
    "父亲" to "爸爸",
)

val NOVEL_PREDICATES: Set<String> = linkedSetOf(
    "is_a",
    "named",
    "located_in",
    "knows",
    "wants",
    "has_item",
    "related_to",
    "status",
    "foreshadow",
    "appears_in",
)

val NOVEL_PREDICATE_ZH: Map<String, String> = mapOf(
    "is_a" to "是",
    "named" to "名叫",
    "located_in" to "位于",
    "knows" to "知道",
    "wants" to "想要",
    "has_item" to "持有",
    "related_to" to "相关",
    "status" to "状态",
    "foreshadow" to "伏笔",
    "appears_in" to "出场于",
)

internal val NOVEL_FUNCTIONAL_PREDICATES: Set<String> = setOf(
    "located_in",
    "status",
    "is_a",
    "wants",
)

internal fun graphPredicates(graphId: String): Set<String> =
    if (isNovelGraph(graphId)) NOVEL_PREDICATES else PREDICATES

internal fun graphFunctionalPredicates(graphId: String): Set<String> =
    if (isNovelGraph(graphId)) NOVEL_FUNCTIONAL_PREDICATES else FUNCTIONAL_PREDICATES

internal fun predicateLabel(p: String): String =
    PREDICATE_ZH[p] ?: NOVEL_PREDICATE_ZH[p] ?: p

internal val SENSITIVE_PREDICATES: Set<String> = setOf("allergic_to", "takes", "diet")

internal fun defaultScope(p: String): String =
    if (p in SENSITIVE_PREDICATES) "private" else "cloud_ok"

internal fun scopeAllowed(scope: String, principal: String): Boolean =
    when (principal) {
        "extractor" -> scope == "cloud_ok"
        else -> true
    }

