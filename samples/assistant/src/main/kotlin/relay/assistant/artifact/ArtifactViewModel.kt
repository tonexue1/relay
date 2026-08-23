package relay.assistant.artifact

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import relay.artifacts.ArtifactContent
import relay.artifacts.ArtifactFeedback
import relay.artifacts.ArtifactRef
import relay.artifacts.ArtifactVersion
import relay.artifacts.FileArtifactRepository
import relay.uikit.FileSpec

data class ArtifactPreviewState(
    val content: ArtifactContent,
    val versions: List<ArtifactVersion>,
    val tab: ArtifactTab = ArtifactTab.PREVIEW,
    val feedback: String = "",
)

enum class ArtifactTab { PREVIEW, SOURCE, VERSIONS }

class ArtifactViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FileArtifactRepository(File(application.filesDir, "ui-artifacts"))
    private val _preview = MutableStateFlow<ArtifactPreviewState?>(null)
    val preview: StateFlow<ArtifactPreviewState?> = _preview.asStateFlow()

    fun open(file: FileSpec) {
        if (file.status != "ready" || file.artifactId.isBlank() || file.mime != "text/markdown") return
        open(file.artifactId, file.artifactVersion)
    }

    fun close() {
        _preview.value = null
    }

    fun setTab(tab: ArtifactTab) {
        _preview.update { it?.copy(tab = tab) }
    }

    fun setFeedback(value: String) {
        _preview.update { it?.copy(feedback = value) }
    }

    fun selectVersion(version: Int) {
        val current = _preview.value ?: return
        open(current.content.metadata.artifactId, version, ArtifactTab.VERSIONS)
    }

    fun activate() {
        val current = _preview.value ?: return
        repository.activate(current.content.metadata.ref)
    }

    fun saveFeedback(): Boolean {
        val current = _preview.value ?: return false
        val comment = current.feedback.trim()
        if (comment.isBlank()) return false
        repository.addFeedback(
            current.content.metadata.ref,
            ArtifactFeedback(category = "general", comment = comment),
        )
        _preview.update { it?.copy(feedback = "") }
        return true
    }

    fun buildFixPrompt(): String? {
        val current = _preview.value ?: return null
        val feedback = current.feedback.trim()
        if (feedback.isBlank()) return null
        saveFeedback()
        val ref = current.content.metadata.ref
        return """
            修订 Markdown 产物 ${ref.artifactId} v${ref.version}。
            用户反馈：$feedback
            先调用 read_artifact 读取基线，再调用 revise_artifact 写入新版本。
            未提及的内容必须保持不变；不得改成 HTML。
        """.trimIndent()
    }

    private fun open(artifactId: String, version: Int, tab: ArtifactTab = ArtifactTab.PREVIEW) {
        val content = repository.read(ArtifactRef(artifactId, version)) ?: return
        if (content.metadata.mime != "text/markdown") return
        _preview.value = ArtifactPreviewState(
            content = content,
            versions = repository.versions(artifactId),
            tab = tab,
        )
    }
}
