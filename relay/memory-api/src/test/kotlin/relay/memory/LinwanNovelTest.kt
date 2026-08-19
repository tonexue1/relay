package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LinwanNovelTest {

    @Test
    fun novelClosedSetIsTenAndDisjointFromAssistantExceptNamedLocated() {
        assertEquals(10, NOVEL_PREDICATES.size)
        assertEquals(NOVEL_PREDICATES, NOVEL_PREDICATE_ZH.keys)
        assertTrue(NOVEL_FUNCTIONAL_PREDICATES.all { it in NOVEL_PREDICATES })
        val overlap = PREDICATES.intersect(NOVEL_PREDICATES)
        assertEquals(setOf("named", "located_in"), overlap)
    }

    @Test
    fun goldUsesOnlyNovelPredicatesAndKeepsRelatedTo() {
        val used = LinwanNovel.CHAPTERS.flatMap { it.facts }.map { it.p }.toSet()
        assertTrue(used.all { it in NOVEL_PREDICATES }, used.filter { it !in NOVEL_PREDICATES }.toString())
        assertTrue("related_to" in used)
        assertEquals(10, LinwanNovel.CHAPTERS.size)
        assertTrue(LinwanNovel.CHAPTERS.all { chapter -> chapter.facts.any { it.p == "related_to" } })
    }

    @Test
    fun cleanerKeepsEveryChapterFactIncludingRelatedTo() {
        for (chapter in LinwanNovel.CHAPTERS) {
            val drafts = chapter.drafts()
            val cleaned = cleanTriples(drafts)
            assertEquals(
                drafts.map { Triple(it.s, it.p, it.o) }.toSet(),
                cleaned.map { Triple(it.s, it.p, it.o) }.toSet(),
                "chapter ${chapter.n} ${chapter.title}",
            )
        }
    }

    @Test
    fun assistantPredicatesAreDroppedOnNovelGraph() {
        val cleaned = cleanTriples(
            listOf(TripleDraft(GRAPH_LINWAN, "林晚", "allergic_to", "花生")),
        )
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun savesOneChapterAtATimeAndRelatedEdgesPersist() = runTest {
        val store = InMemoryMemoryStore()
        store.saveChapter(LinwanNovel.CHAPTERS[0])
        val after1 = store.facts(GRAPH_LINWAN)
        assertTrue(after1.facts.any { it.s == "林晚" && it.p == "related_to" && it.o == "赵捕头" })
        assertTrue(after1.facts.any { it.s == "王二" && it.p == "status" && it.o == "已死" })
        assertTrue(after1.facts.any { it.p == "appears_in" && it.o == "第1回" })
        assertTrue(after1.facts.none { it.s == "阿秀" })

        store.saveChapter(LinwanNovel.CHAPTERS[1])
        store.saveChapter(LinwanNovel.CHAPTERS[2])
        store.saveChapter(LinwanNovel.CHAPTERS[3])
        val after4 = store.facts(GRAPH_LINWAN)
        assertTrue(after4.facts.any { it.s == "阿秀" && it.p == "related_to" && it.o == "王二" })
        assertTrue(after4.facts.any { it.s == "阿秀" && it.p == "related_to" && it.o == "林晚" })
        assertTrue(after4.facts.any { it.p == "appears_in" && it.o == "第4回" })

        for (chapter in LinwanNovel.CHAPTERS.drop(4)) {
            store.saveChapter(chapter)
        }
        val all = store.facts(GRAPH_LINWAN)
        assertTrue(all.facts.any { it.s == "王二" && it.p == "related_to" && it.o == "赵捕头" })
        assertTrue(all.facts.any { it.s == "赵捕头" && it.p == "related_to" && it.o == "林晚" })
        assertTrue(all.facts.any { it.s == "账本" && it.p == "foreshadow" && it.o == "未收束" })
        assertEquals("耳房", all.facts.single { it.s == "林晚" && it.p == "located_in" }.o)
        assertEquals("失踪", all.facts.single { it.s == "阿秀" && it.p == "status" }.o)
        assertTrue(all.facts.any { it.p == "appears_in" && it.o == "第10回" })
    }

    @Test
    fun queryLinwanReturnsRelatedCastAndOpenThreads() = runTest {
        val store = InMemoryMemoryStore()
        for (chapter in LinwanNovel.CHAPTERS) store.saveChapter(chapter)

        val linwan = store.query(GRAPH_LINWAN, "林晚")
        assertTrue(linwan.facts.any { it.p == "related_to" && it.o == "赵捕头" }, linwan.render())
        assertTrue(linwan.facts.any { it.p == "wants" && it.o == "翻案" }, linwan.render())
        assertTrue(linwan.facts.any { it.p == "knows" && it.o == "账本秘密" }, linwan.render())
        assertTrue(linwan.facts.any { it.p == "knows" && it.o == "假腰牌" }, linwan.render())

        val master = store.query(GRAPH_LINWAN, "林晚的师父")
        assertTrue(master.facts.any { it.p == "related_to" && (it.s == "林晚" || it.o == "赵捕头") }, master.render())

        val axiu = store.query(GRAPH_LINWAN, "阿秀")
        assertTrue(axiu.facts.any { it.p == "related_to" && it.o == "王二" }, axiu.render())
        assertTrue(axiu.facts.any { it.p == "status" && it.o == "失踪" }, axiu.render())

        val thread = store.query(GRAPH_LINWAN, "未收束的伏笔")
        assertTrue(thread.facts.any { it.s == "账本" && it.p == "foreshadow" }, thread.render())
    }

    @Test
    fun novelGraphDoesNotJoinAssistant() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        store.saveChapter(LinwanNovel.CHAPTERS[0])
        assertTrue(store.query(GRAPH_LINWAN, "花生").isEmpty)
        assertTrue(store.query(GRAPH_ASSISTANT, "林晚").isEmpty)
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.none { it.s == "林晚" })
        assertTrue(store.facts(GRAPH_LINWAN).facts.none { it.o == "花生" })
    }

    @Test
    fun existingNovelDemoLocatedInStillIngests() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft("novel:demo", "林晚", "located_in", "码头")))
        assertEquals("码头", store.facts("novel:demo").facts.single { it.p == "located_in" }.o)
        assertEquals("- 林晚 位于 码头", store.facts("novel:demo").render())
    }
}
