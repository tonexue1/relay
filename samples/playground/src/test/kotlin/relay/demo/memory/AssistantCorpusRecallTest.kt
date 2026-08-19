package relay.demo.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.memory.GRAPH_ASSISTANT
import relay.memory.InMemoryMemoryStore

class AssistantCorpusRecallTest {

    @Test
    fun threeWavesStoreAndRecall() = runBlocking {
        val store = InMemoryMemoryStore()
        store.ingest(AssistantCorpus.waves[0].drafts)

        val peanut = store.query(GRAPH_ASSISTANT, "花生")
        assertTrue(peanut.facts.any { it.p == "allergic_to" && it.o == "花生" })
        assertTrue(store.query(GRAPH_ASSISTANT, "妈妈").facts.any { it.s == "妈妈" && it.p == "likes" && it.o == "花生" })
        assertTrue(store.query(GRAPH_ASSISTANT, "火锅").isEmpty)
        assertEquals("杭州", store.facts(GRAPH_ASSISTANT).facts.single { it.p == "lives_in" && it.s == "用户" }.o)

        store.ingest(AssistantCorpus.waves[1].drafts)
        val coffee = store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "likes" && it.s == "用户" }.map { it.o }.toSet()
        assertTrue(coffee.containsAll(setOf("美式", "拿铁", "手冲", "火锅", "米线")))
        assertTrue(store.query(GRAPH_ASSISTANT, "火锅").facts.any { it.p == "likes" && it.o == "火锅" })
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.filter { it.p == "plans" }.map { it.o }.containsAll(listOf("美国", "跳槽", "考研")))

        store.ingest(AssistantCorpus.waves[2].drafts)
        val live = store.facts(GRAPH_ASSISTANT).facts
        assertEquals("上海", live.single { it.p == "lives_in" && it.s == "用户" }.o)
        assertEquals("字节", live.single { it.p == "works_at" && it.s == "用户" }.o)
        assertEquals("三年", live.single { it.p == "work_years" }.o)
        assertEquals("清真", live.single { it.p == "diet" }.o)
        assertTrue(live.none { it.p == "plans" && it.o == "美国" })
        assertTrue(live.filter { it.p == "plans" }.map { it.o }.containsAll(listOf("跳槽", "考研", "买房")))

        assertEquals("上海", store.query(GRAPH_ASSISTANT, "上海").facts.single { it.p == "lives_in" && it.s == "用户" }.o)
        assertTrue(store.query(GRAPH_ASSISTANT, "美国").facts.none { it.p == "plans" && it.o == "美国" })
        assertTrue(store.query(GRAPH_ASSISTANT, "林晚").isEmpty)
    }
}
