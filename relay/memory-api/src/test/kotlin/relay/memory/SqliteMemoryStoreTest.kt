package relay.memory

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteMemoryStoreTest {

    @Test
    fun blankIngestDoesNotConsume() = runTest {
        val store = InMemoryMemoryStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我作业没做完"))
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "", rawEventIds = listOf(id))),
        )
        assertEquals(listOf(id), store.unconsumed(GRAPH_ASSISTANT).map { it.id })
    }

    @Test
    fun extractorCannotSeePrivateAllergyEdges() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "美式")))
        assertTrue(store.query(GRAPH_ASSISTANT, "花生", principal = "user").facts.any { it.p == "allergic_to" })
        assertTrue(store.query(GRAPH_ASSISTANT, "花生", principal = "extractor").facts.none { it.p == "allergic_to" })
        assertTrue(store.query(GRAPH_ASSISTANT, "美式", principal = "extractor").facts.any { it.p == "likes" })
    }

    @Test
    fun extractorOnlySeesCloudOkRawEvents() = runTest {
        val store = InMemoryMemoryStore()
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏", scope = "private"))
        val cloud = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我喜欢美式", scope = "cloud_ok"))
        assertEquals(2, store.unconsumed(GRAPH_ASSISTANT).size)
        assertEquals(listOf(cloud), store.unconsumed(GRAPH_ASSISTANT, principal = "extractor").map { it.id })
    }

    @Test
    fun fileStoreSurvivesReopen(@TempDir dir: File) = runTest {
        val file = File(dir, "memory.db")
        val first = SqliteMemoryStore(file)
        first.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        first.close()
        val second = SqliteMemoryStore(file)
        val hit = second.query(GRAPH_ASSISTANT, "花生")
        assertTrue(hit.facts.any { it.p == "allergic_to" && it.o == "花生" })
        second.close()
    }

    @Test
    fun ftsHitsPeanutWithoutHotpotHint() = runTest {
        val store = InMemoryMemoryStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val query = "别放花生"
        assertTrue(listOf("火锅", "过敏", "忌口", "踩雷", "蘸料").none { it in query })
        val hit = store.query(GRAPH_ASSISTANT, query)
        assertTrue(hit.facts.any { it.p == "allergic_to" && it.o == "花生" })
    }

    @Test
    fun markScopePromotesPrivateRawToExtractor() = runTest {
        val store = InMemoryMemoryStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏", scope = "private"))
        assertTrue(store.unconsumed(GRAPH_ASSISTANT, principal = "extractor").isEmpty())
        assertEquals(listOf(id), store.unconsumed(GRAPH_ASSISTANT, principal = "user").map { it.id })
        store.markScope(GRAPH_ASSISTANT, listOf(id), "cloud_ok")
        val extractor = store.unconsumed(GRAPH_ASSISTANT, principal = "extractor")
        assertEquals(listOf(id), extractor.map { it.id })
        assertEquals("cloud_ok", extractor.single().scope)
        assertEquals("我花生过敏", extractor.single().text)
    }

    @Test
    fun duplicateCaptureSharesOneArtifact(@TempDir dir: File) = runTest {
        val artifacts = FileArtifactStore(File(dir, "art"))
        val store = SqliteMemoryStore(JdbcMemoryDb.file(File(dir, "memory.db")), artifacts)
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        assertEquals(1, artifacts.size())
        val events = store.unconsumed(GRAPH_ASSISTANT)
        assertEquals(2, events.size)
        assertEquals(1, events.map { it.textRef }.toSet().size)
        assertTrue(events.all { it.text == "我花生过敏" && it.textRef.isNotBlank() })
        store.close()
    }

    @Test
    fun rebuildFromFactLogDoesNotNeedArtifacts(@TempDir dir: File) = runTest {
        val artDir = File(dir, "art")
        val artifacts = FileArtifactStore(artDir)
        val store = SqliteMemoryStore(JdbcMemoryDb.file(File(dir, "memory.db")), artifacts)
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我花生过敏"))
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生", rawEventIds = listOf(id))),
        )
        artDir.listFiles()?.forEach { it.delete() }
        assertEquals(0, artifacts.size())
        store.rebuildFromFactLog(GRAPH_ASSISTANT)
        assertTrue(store.facts(GRAPH_ASSISTANT).facts.any { it.p == "allergic_to" && it.o == "花生" })
        store.close()
    }
}
