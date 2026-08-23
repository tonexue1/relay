package relay.artifacts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FileArtifactRepositoryTest {
    @Test
    fun `revisions are immutable and can be activated`() {
        val root = Files.createTempDirectory("relay-artifacts").toFile()
        val repository = FileArtifactRepository(root)

        val first = repository.create("notes.md", "text/markdown", "# One")
        val second = repository.revise(first.artifactId, first.version, "# Two")

        assertNotEquals(first.version, second.version)
        assertEquals("# One", repository.read(first)?.body)
        assertEquals("# Two", repository.read(second)?.body)
        repository.activate(first)
        assertEquals(first.version, repository.list().single().activeVersion)
    }

    @Test
    fun `feedback belongs to one version`() {
        val repository = FileArtifactRepository(Files.createTempDirectory("relay-feedback").toFile())
        val ref = repository.create("page.html", "text/html", "<h1>Relay</h1>")

        repository.addFeedback(ref, ArtifactFeedback("copy", "标题改短"))

        assertEquals("标题改短", repository.versions(ref.artifactId).single().feedback.single().comment)
    }

    @Test
    fun `unsafe names and mime are rejected`() {
        val repository = FileArtifactRepository(Files.createTempDirectory("relay-invalid").toFile())
        assertFailsWith<IllegalArgumentException> {
            repository.create("../secret", "text/html", "x")
        }
        assertFailsWith<IllegalArgumentException> {
            repository.create("data.json", "application/json", "{}")
        }
    }
}
