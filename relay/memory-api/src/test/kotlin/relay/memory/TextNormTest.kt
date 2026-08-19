package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TextNormTest {

    @Test
    fun closedPredicateSetMatchesLabels() {
        assertEquals(30, PREDICATES.size)
        assertEquals(PREDICATES, PREDICATE_ZH.keys)
        assertTrue(FUNCTIONAL_PREDICATES.all { it in PREDICATES })
        assertEquals(10, NOVEL_PREDICATES.size)
        assertEquals(NOVEL_PREDICATES, NOVEL_PREDICATE_ZH.keys)
    }

    @Test
    fun compactWhitespaceAndNfkc() {
        assertEquals("花生酱", normalizeText("花生酱"))
        assertEquals("杭州", normalizeText("  杭 州  "))
    }

    @Test
    fun queryTokensKeepObjectAndPredicateBigrams() {
        val tokens = queryTokens("我过敏什么")
        assertTrue("过敏" in tokens)
        val peanut = queryTokens("火锅别放花生")
        assertTrue(peanut.any { "花生" in it })
    }
}
