package relay.artifacts

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactRef(
    val artifactId: String,
    val version: Int,
)

@Serializable
data class ArtifactDiagnostic(
    val kind: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class ArtifactAnnotation(
    val selector: String = "",
    val path: String = "",
    val textSnippet: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

@Serializable
data class ArtifactFeedback(
    val category: String,
    val comment: String,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val keepUnchanged: String = "",
    val annotation: ArtifactAnnotation? = null,
    val diagnostics: List<ArtifactDiagnostic> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class ArtifactValidationReport(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ArtifactVersion(
    val artifactId: String,
    val version: Int,
    val baseVersion: Int? = null,
    val name: String,
    val mime: String,
    val bodySha256: String,
    val sizeBytes: Long,
    val summary: String,
    val createdAtMillis: Long,
    val validation: ArtifactValidationReport = ArtifactValidationReport(valid = true),
    val feedback: List<ArtifactFeedback> = emptyList(),
) {
    val ref: ArtifactRef get() = ArtifactRef(artifactId, version)
}

@Serializable
data class ArtifactRecord(
    val artifactId: String,
    val activeVersion: Int,
    val versions: List<ArtifactVersion>,
)

data class ArtifactContent(
    val metadata: ArtifactVersion,
    val body: String,
)

interface ArtifactRepository {
    fun create(
        name: String,
        mime: String,
        body: String,
        summary: String = "",
        validation: ArtifactValidationReport = ArtifactValidationReport(valid = true),
    ): ArtifactRef

    fun revise(
        artifactId: String,
        baseVersion: Int,
        body: String,
        summary: String = "",
        validation: ArtifactValidationReport = ArtifactValidationReport(valid = true),
    ): ArtifactRef

    fun read(ref: ArtifactRef): ArtifactContent?
    fun list(): List<ArtifactRecord>
    fun versions(artifactId: String): List<ArtifactVersion>
    fun activate(ref: ArtifactRef)
    fun addFeedback(ref: ArtifactRef, feedback: ArtifactFeedback)
}
