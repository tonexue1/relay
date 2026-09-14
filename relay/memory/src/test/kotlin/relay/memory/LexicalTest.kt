package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import relay.memory.api.LexicalScorer
import relay.memory.engine.bm25RawScores
import relay.memory.engine.lexicalScores
import relay.memory.engine.queryTokenList
import relay.memory.engine.queryTokens

class LexicalTest {

    @Test
    fun bm25RanksRareTermOverCommonUserPrefix() {
        val docs = listOf(
            "用户喜欢吃西红柿",
            "用户花生过敏",
            "用户对青霉素过敏",
        )
        val scores = lexicalScores(LexicalScorer.BM25, "青霉素", docs)
        assertEquals(2, scores.indices.maxBy { scores[it] })
        assertTrue(scores[2] > scores[0])
    }

    @Test
    fun coverageIsQueryTokenOverlap() {
        val scores = lexicalScores(LexicalScorer.COVERAGE, "过敏", listOf("用户花生过敏", "用户住在上海"))
        assertTrue(scores[0] > scores[1])
    }

    @Test
    fun queryTokenListKeepsRepeatingNgramsForTf() {
        val list = queryTokenList("青霉素青霉素")
        assertTrue(list.count { it == "青霉素" } >= 2)
        assertTrue("青霉素" in queryTokens("我对青霉素过敏"))
    }

    @Test
    fun bm25RawIsHigherForRarerDocument() {
        val q = setOf("青霉素")
        val docs = listOf(
            queryTokenList("用户喜欢吃西红柿"),
            queryTokenList("用户对青霉素过敏"),
        )
        val raw = bm25RawScores(q, docs)
        assertTrue(raw[1] > raw[0])
    }
}
