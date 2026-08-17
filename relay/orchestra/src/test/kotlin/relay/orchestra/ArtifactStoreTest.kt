package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ArtifactStoreTest {

    @Test
    fun putThenGetReturnsTheSameText() = runTest {
        val store = InMemoryArtifactStore()
        val ref = store.put("run-1", "researcher/output", "full report")

        assertEquals("run-1", ref.runId)
        assertEquals("researcher/output", ref.name)
        assertEquals("artifact://run-1/researcher/output", ref.uri)
        assertEquals("full report", store.get(ref))
    }

    @Test
    fun missingRefThrows() = runTest {
        val store = InMemoryArtifactStore()
        val error = assertFailsWith<NoSuchElementException> {
            store.get(ArtifactRef("run-1", "missing"))
        }
        assertTrue(error.message!!.contains("artifact://run-1/missing"))
    }

    @Test
    fun sameNameOverwrites() = runTest {
        val store = InMemoryArtifactStore()
        store.put("run-1", "w/output", "first")
        val ref = store.put("run-1", "w/output", "second")
        assertEquals("second", store.get(ref))
    }
}
