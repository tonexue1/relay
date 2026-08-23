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
    "worked_on",
    "has_component",
    "uses_technology",
    "target_role",
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
    "worked_on" to "参与过",
    "has_component" to "包含组件",
    "uses_technology" to "使用技术",
    "target_role" to "求职方向",
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

fun predicateLabel(p: String): String =
    PREDICATE_ZH[p] ?: NOVEL_PREDICATE_ZH[p] ?: p

