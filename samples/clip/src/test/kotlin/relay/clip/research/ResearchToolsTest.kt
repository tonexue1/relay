package relay.clip.research

import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchToolsTest {

    @Test
    fun parseArtifactUriKeepsSlashesInName() {
        val ref = parseArtifactUri("artifact://clip-1/scout/call-a")
        assertEquals("clip-1", ref.runId)
        assertEquals("scout/call-a", ref.name)
    }

    @Test
    fun jsonArgReadsQueryAndBareStringAndNestedJson() {
        assertEquals("Mate 70", jsonArg("""{"query":"Mate 70"}""", "query"))
        assertEquals("Mate 70", jsonArg("""{"task":"Mate 70"}""", "query", "task"))
        assertEquals("Mate 70", jsonArg("\"Mate 70\"", "query"))
        assertEquals("Mate 70", jsonArg("\"{\\\"query\\\":\\\"Mate 70\\\"}\"", "query"))
    }
}
