package relay.assistant.artifact

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import relay.artifacts.FileArtifactRepository
import relay.uikit.FileSpec

@RunWith(RobolectricTestRunner::class)
class ArtifactViewModelTest {
    @Test
    fun `markdown artifact opens but html stays outside product`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repository = FileArtifactRepository(File(application.filesDir, "ui-artifacts"))
        val markdown = repository.create("复盘.md", "text/markdown", "# 完整正文")
        val html = repository.create("网页.html", "text/html", "<h1>网页</h1>")
        val viewModel = ArtifactViewModel(application)

        viewModel.open(FileSpec(artifactId = markdown.artifactId, artifactVersion = 1, name = "复盘.md", mime = "text/markdown"))
        assertEquals("# 完整正文", viewModel.preview.value?.content?.body)

        viewModel.close()
        viewModel.open(FileSpec(artifactId = html.artifactId, artifactVersion = 1, name = "网页.html", mime = "text/html"))
        assertNull(viewModel.preview.value)
    }
}
