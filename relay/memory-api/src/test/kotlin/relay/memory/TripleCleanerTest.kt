package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TripleCleanerTest {

    @Test
    fun dropsAssistantEntity() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "助理", "likes", "茶")),
        )
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun rewritesTasteAllergenToAllergicTo() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "花生过敏")),
        )
        assertEquals(listOf(CleanTriple("用户", "allergic_to", "花生")), cleaned)
    }

    @Test
    fun dropsLikesThatClashWithAllergen() {
        val cleaned = cleanTriples(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "花生酱"),
            ),
        )
        assertEquals(listOf(CleanTriple("用户", "allergic_to", "花生")), cleaned)
    }

    @Test
    fun userLocatedInBecomesLivesIn() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "located_in", "杭州")),
        )
        assertEquals(listOf(CleanTriple("用户", "lives_in", "杭州")), cleaned)
    }

    @Test
    fun flipsColleagueAndFamilyTowardUser() {
        assertEquals(
            listOf(CleanTriple("用户", "colleague_of", "王磊")),
            cleanTriples(listOf(TripleDraft(GRAPH_ASSISTANT, "王磊", "colleague_of", "用户"))),
        )
        assertEquals(
            listOf(CleanTriple("用户", "child_of", "妈妈")),
            cleanTriples(listOf(TripleDraft(GRAPH_ASSISTANT, "妈妈", "parent_of", "用户"))),
        )
        assertEquals(
            listOf(CleanTriple("用户", "parent_of", "小米")),
            cleanTriples(listOf(TripleDraft(GRAPH_ASSISTANT, "小米", "child_of", "用户"))),
        )
    }

    @Test
    fun stripsVerbPrefixAndCoffeeSuffix() {
        val cleaned = cleanTriples(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "prefers", "坐地铁"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式咖啡"),
            ),
        )
        assertTrue(cleaned.any { it.p == "prefers" && it.o == "地铁" })
        assertTrue(cleaned.any { it.p == "likes" && it.o == "美式" })
    }

    @Test
    fun dropsUnknownPredicate() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "relates_to", "世界")),
        )
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun keepsFamilyMembersLikes() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生")),
            chunk = "用户: 我妈爱吃花生。",
        )
        assertTrue(cleaned.any { it == CleanTriple("妈妈", "likes", "花生") })
        assertTrue(cleaned.any { it == CleanTriple("用户", "child_of", "妈妈") })
    }

    @Test
    fun momLikesPeanutDoesNotClashWithUserAllergy() {
        val cleaned = cleanTriples(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生"),
                TripleDraft(GRAPH_ASSISTANT, "妈妈", "likes", "花生"),
            ),
        )
        assertTrue(cleaned.any { it == CleanTriple("用户", "allergic_to", "花生") })
        assertTrue(cleaned.any { it == CleanTriple("妈妈", "likes", "花生") })
    }

    @Test
    fun namedPetImpliesHasPetAndFlipsDirection() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "芝麻", "named", "猫")),
        )
        assertTrue(cleaned.any { it == CleanTriple("猫", "named", "芝麻") })
        assertTrue(cleaned.any { it == CleanTriple("用户", "has_pet", "猫") })
    }

    @Test
    fun dropsHallucinatedObjectNotInChunk() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")),
            chunk = "今天雨好大，随便聊聊。",
        )
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun keepsFamilyMentionedAs妈妈() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "child_of", "妈妈")),
            chunk = "我妈住宁波。",
        )
        assertEquals(listOf(CleanTriple("用户", "child_of", "妈妈")), cleaned)
    }

    @Test
    fun unfinishedHomeworkBecomesHasTask() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "plans", "作业")),
            chunk = "用户: 我作业没做完。",
        )
        assertEquals(listOf(CleanTriple("用户", "has_task", "作业")), cleaned)
    }

    @Test
    fun dedupesIdenticalTriples() {
        val cleaned = cleanTriples(
            listOf(
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式"),
                TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式"),
            ),
        )
        assertEquals(1, cleaned.size)
    }

    @Test
    fun workingTwoYearsBecomesWorkYears() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "works_at", "两年了")),
            chunk = "用户: 我工作两年了。",
        )
        assertEquals(listOf(CleanTriple("用户", "work_years", "两年")), cleaned)
    }
}
