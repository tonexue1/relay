package relay.memory

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.engine.FileArtifactStore
import relay.memory.engine.RoomMemoryDb
import relay.memory.engine.SqliteMemoryStore
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteMemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun blankIngestDoesNotConsume() = runTest {
        val store = testStore()
        val id = store.capture(RawTurn(GRAPH_ASSISTANT, "user", "我作业没做完"))
        store.ingest(
            listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "likes", "", rawEventIds = listOf(id))),
        )
        assertEquals(listOf(id), store.unconsumed(GRAPH_ASSISTANT).map { it.id })
    }

    @Test
    fun fileStoreSurvivesReopen() = runTest {
        val file = File(tmp.root, "memory.db")
        val first = SqliteMemoryStore(testContext(), file)
        first.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        first.close()
        val second = SqliteMemoryStore(testContext(), file)
        val hit = second.query(GRAPH_ASSISTANT, "花生")
        assertTrue(hit.facts.any { it.p == "allergic_to" && it.o == "花生" })
        second.close()
    }

    @Test
    fun ftsHitsPeanutWithoutHotpotHint() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val query = "别放花生"
        assertTrue(listOf("火锅", "过敏", "忌口", "踩雷", "蘸料").none { it in query })
        val hit = store.query(GRAPH_ASSISTANT, query)
        assertTrue(hit.facts.any { it.p == "allergic_to" && it.o == "花生" })
    }

    @Test
    fun duplicateCaptureSharesOneArtifact() = runTest {
        val artifacts = FileArtifactStore(File(tmp.root, "art"))
        val store = SqliteMemoryStore(RoomMemoryDb.file(testContext(), File(tmp.root, "memory.db")), artifacts)
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
    fun rebuildFromFactLogDoesNotNeedArtifacts() = runTest {
        val artDir = File(tmp.root, "art")
        val artifacts = FileArtifactStore(artDir)
        val store = SqliteMemoryStore(RoomMemoryDb.file(testContext(), File(tmp.root, "memory.db")), artifacts)
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

    @Test
    fun migrationFromV5KeepsRawEventsAndAddsClaims() = runTest {
        val file = File(tmp.root, "v5.db")
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        listOf(
            "CREATE TABLE raw_event (id TEXT NOT NULL, graph_id TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "session_id TEXT NOT NULL, role TEXT NOT NULL, text_ref TEXT NOT NULL, source TEXT NOT NULL, " +
                "consumed INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE fact_log (id TEXT NOT NULL, graph_id TEXT NOT NULL, ts INTEGER NOT NULL, s TEXT NOT NULL, " +
                "p TEXT NOT NULL, o TEXT NOT NULL, confidence REAL NOT NULL, raw_event_ids TEXT NOT NULL, " +
                "retract INTEGER NOT NULL, valid_at INTEGER NOT NULL, invalid_at INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE node (id TEXT NOT NULL, graph_id TEXT NOT NULL, type TEXT NOT NULL, " +
                "canonical_name TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE UNIQUE INDEX index_node_graph_id_canonical_name ON node (graph_id, canonical_name)",
            "CREATE TABLE node_alias (graph_id TEXT NOT NULL, alias TEXT NOT NULL, node_id TEXT NOT NULL, " +
                "PRIMARY KEY(graph_id, alias))",
            "CREATE TABLE edge (id TEXT NOT NULL, graph_id TEXT NOT NULL, src TEXT NOT NULL, dst TEXT NOT NULL, " +
                "relation TEXT NOT NULL, confidence REAL NOT NULL, created_at INTEGER NOT NULL, expired_at INTEGER, " +
                "valid_at INTEGER NOT NULL, invalid_at INTEGER, updated_at INTEGER NOT NULL, provenance TEXT NOT NULL, " +
                "PRIMARY KEY(id))",
            "CREATE TABLE pending_review (edge_id TEXT NOT NULL, reason TEXT NOT NULL, confidence REAL NOT NULL, " +
                "s TEXT NOT NULL, p TEXT NOT NULL, o TEXT NOT NULL, PRIMARY KEY(edge_id))",
            "CREATE VIRTUAL TABLE node_fts USING FTS5(node_id UNINDEXED, graph_id UNINDEXED, canonical_name, aliases, " +
                "tokenize=`unicode61`)",
            "INSERT INTO raw_event VALUES ('raw-1','assistant',1,'legacy','user','missing','chat',0)",
            "PRAGMA user_version = 5",
        ).forEach(connection::execSQL)
        connection.close()

        val store = SqliteMemoryStore(
            RoomMemoryDb.file(testContext(), file),
            FileArtifactStore(File(tmp.root, "art-v5")),
        )

        assertEquals(listOf("raw-1"), store.unconsumed(GRAPH_ASSISTANT).map { it.id })
        assertTrue(store.claims(GRAPH_ASSISTANT).isEmpty())
        store.close()
    }

    @Test
    fun migrationFromV6AddsScopeStateAndPreservesLegacyRows() = runTest {
        val file = File(tmp.root, "v6.db")
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        listOf(
            "CREATE TABLE raw_event (id TEXT NOT NULL, graph_id TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "session_id TEXT NOT NULL, role TEXT NOT NULL, text_ref TEXT NOT NULL, source TEXT NOT NULL, " +
                "consumed INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE fact_log (id TEXT NOT NULL, graph_id TEXT NOT NULL, ts INTEGER NOT NULL, s TEXT NOT NULL, " +
                "p TEXT NOT NULL, o TEXT NOT NULL, confidence REAL NOT NULL, raw_event_ids TEXT NOT NULL, " +
                "retract INTEGER NOT NULL, valid_at INTEGER NOT NULL, invalid_at INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE claim_log (id TEXT NOT NULL, graph_id TEXT NOT NULL, session_id TEXT NOT NULL, " +
                "run_id TEXT NOT NULL, subject TEXT NOT NULL, text TEXT NOT NULL, confidence REAL NOT NULL, " +
                "raw_event_ids TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE INDEX index_claim_log_graph_id_created_at ON claim_log (graph_id, created_at)",
            "CREATE INDEX index_claim_log_run_id ON claim_log (run_id)",
            "CREATE TABLE extraction_run (id TEXT NOT NULL, graph_id TEXT NOT NULL, session_id TEXT NOT NULL, " +
                "status TEXT NOT NULL, event_ids TEXT NOT NULL, context_event_ids TEXT NOT NULL, " +
                "started_at INTEGER NOT NULL, finished_at INTEGER, response_ref TEXT NOT NULL, error TEXT NOT NULL, " +
                "PRIMARY KEY(id))",
            "CREATE INDEX index_extraction_run_graph_id_started_at ON extraction_run (graph_id, started_at)",
            "CREATE TABLE node (id TEXT NOT NULL, graph_id TEXT NOT NULL, type TEXT NOT NULL, " +
                "canonical_name TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE UNIQUE INDEX index_node_graph_id_canonical_name ON node (graph_id, canonical_name)",
            "CREATE TABLE node_alias (graph_id TEXT NOT NULL, alias TEXT NOT NULL, node_id TEXT NOT NULL, " +
                "PRIMARY KEY(graph_id, alias))",
            "CREATE TABLE edge (id TEXT NOT NULL, graph_id TEXT NOT NULL, src TEXT NOT NULL, dst TEXT NOT NULL, " +
                "relation TEXT NOT NULL, confidence REAL NOT NULL, created_at INTEGER NOT NULL, expired_at INTEGER, " +
                "valid_at INTEGER NOT NULL, invalid_at INTEGER, updated_at INTEGER NOT NULL, provenance TEXT NOT NULL, " +
                "PRIMARY KEY(id))",
            "CREATE TABLE pending_review (edge_id TEXT NOT NULL, reason TEXT NOT NULL, confidence REAL NOT NULL, " +
                "s TEXT NOT NULL, p TEXT NOT NULL, o TEXT NOT NULL, PRIMARY KEY(edge_id))",
            "CREATE VIRTUAL TABLE node_fts USING FTS5(node_id UNINDEXED, graph_id UNINDEXED, canonical_name, aliases, " +
                "tokenize=`unicode61`)",
            "CREATE VIRTUAL TABLE claim_fts USING FTS5(claim_id UNINDEXED, graph_id UNINDEXED, subject, text, " +
                "tokenize=`unicode61`)",
            "INSERT INTO node VALUES ('user','assistant','person','用户')",
            "INSERT INTO node VALUES ('peanut','assistant','other','花生')",
            "INSERT INTO node VALUES ('android','assistant','other','Android')",
            "INSERT INTO node VALUES ('binder','assistant','other','Binder')",
            "INSERT INTO node_fts(node_id,graph_id,canonical_name,aliases) VALUES " +
                "('peanut','assistant','花生','花生')",
            "INSERT INTO node_fts(node_id,graph_id,canonical_name,aliases) VALUES " +
                "('android','assistant','Android','Android')",
            "INSERT INTO node_fts(node_id,graph_id,canonical_name,aliases) VALUES " +
                "('binder','assistant','Binder','Binder')",
            "INSERT INTO edge VALUES ('edge-1','assistant','user','peanut','allergic_to',0.9,1,NULL,1,NULL,1,'[]')",
            "INSERT INTO edge VALUES ('edge-2','assistant','android','binder','has_component',0.9,1,NULL,1,NULL,1,'[]')",
            "INSERT INTO fact_log VALUES ('fact-1','assistant',1,'用户','allergic_to','花生',0.9,'[]',0,1,NULL)",
            "INSERT INTO fact_log VALUES ('fact-2','assistant',1,'Android','has_component','Binder',0.9,'[]',0,1,NULL)",
            "INSERT INTO claim_log VALUES " +
                "('claim-1','assistant','legacy-session','run-1','项目','项目使用云端卡片',0.7,'[]',1)",
            "INSERT INTO claim_fts(claim_id,graph_id,subject,text) VALUES " +
                "('claim-1','assistant','项目','项目使用云端卡片')",
            "PRAGMA user_version = 6",
        ).forEach(connection::execSQL)
        connection.close()

        val store = SqliteMemoryStore(
            RoomMemoryDb.file(testContext(), file),
            FileArtifactStore(File(tmp.root, "art-v6")),
        )

        val facts = store.facts(GRAPH_ASSISTANT).facts
        val fact = facts.single { it.p == "allergic_to" }
        assertEquals(MemoryScope.PROFILE, fact.scope)
        assertEquals(MemoryState.CONFIRMED, fact.state)
        val legacyResearch = facts.single { it.p == "has_component" }
        assertEquals(MemoryScope.SESSION, legacyResearch.scope)
        assertEquals(MemoryState.CANDIDATE, legacyResearch.state)
        assertEquals("legacy", legacyResearch.scopeId)
        assertTrue(store.query(GRAPH_ASSISTANT, "花生", RecallContext()).facts.isNotEmpty())
        assertTrue(store.query(GRAPH_ASSISTANT, "Android Binder", RecallContext()).isEmpty)
        val claim = store.claims(GRAPH_ASSISTANT).single()
        assertEquals(MemoryScope.SESSION, claim.scope)
        assertEquals(MemoryState.CANDIDATE, claim.state)
        assertEquals("legacy-session", claim.scopeId)
        store.close()
    }
}
