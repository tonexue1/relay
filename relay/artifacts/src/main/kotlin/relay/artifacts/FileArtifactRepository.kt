package relay.artifacts

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileArtifactRepository(
    private val root: File,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) : ArtifactRepository {
    private val lock = Any()
    private val bodies = File(root, "bodies")
    private val manifest = File(root, "manifest.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        require(maxBodyBytes > 0)
        bodies.mkdirs()
    }

    override fun create(
        name: String,
        mime: String,
        body: String,
        summary: String,
        validation: ArtifactValidationReport,
    ): ArtifactRef = synchronized(lock) {
        validateInput(name, mime, body)
        val id = UUID.randomUUID().toString()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val sha = writeBody(bytes)
        val version = ArtifactVersion(
            artifactId = id,
            version = 1,
            name = name,
            mime = mime,
            bodySha256 = sha,
            sizeBytes = bytes.size.toLong(),
            summary = normalizedSummary(summary, body),
            createdAtMillis = System.currentTimeMillis(),
            validation = validation,
        )
        val records = loadRecords() + ArtifactRecord(id, 1, listOf(version))
        writeRecords(records)
        version.ref
    }

    override fun revise(
        artifactId: String,
        baseVersion: Int,
        body: String,
        summary: String,
        validation: ArtifactValidationReport,
    ): ArtifactRef = synchronized(lock) {
        val records = loadRecords().toMutableList()
        val index = records.indexOfFirst { it.artifactId == artifactId }
        require(index >= 0) { "Unknown artifact: $artifactId" }
        val record = records[index]
        val base = record.versions.firstOrNull { it.version == baseVersion }
            ?: error("Unknown base version: $baseVersion")
        validateInput(base.name, base.mime, body)
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val nextNumber = (record.versions.maxOfOrNull { it.version } ?: 0) + 1
        val next = ArtifactVersion(
            artifactId = artifactId,
            version = nextNumber,
            baseVersion = baseVersion,
            name = base.name,
            mime = base.mime,
            bodySha256 = writeBody(bytes),
            sizeBytes = bytes.size.toLong(),
            summary = normalizedSummary(summary, body),
            createdAtMillis = System.currentTimeMillis(),
            validation = validation,
        )
        records[index] = record.copy(activeVersion = nextNumber, versions = record.versions + next)
        writeRecords(records)
        next.ref
    }

    override fun read(ref: ArtifactRef): ArtifactContent? = synchronized(lock) {
        val version = loadRecords()
            .firstOrNull { it.artifactId == ref.artifactId }
            ?.versions
            ?.firstOrNull { it.version == ref.version }
            ?: return@synchronized null
        val file = File(bodies, version.bodySha256)
        if (!file.isFile) return@synchronized null
        ArtifactContent(version, file.readText(StandardCharsets.UTF_8))
    }

    override fun list(): List<ArtifactRecord> = synchronized(lock) { loadRecords() }

    override fun versions(artifactId: String): List<ArtifactVersion> = synchronized(lock) {
        loadRecords().firstOrNull { it.artifactId == artifactId }?.versions.orEmpty()
    }

    override fun activate(ref: ArtifactRef) = synchronized(lock) {
        val records = loadRecords().toMutableList()
        val index = records.indexOfFirst { it.artifactId == ref.artifactId }
        require(index >= 0 && records[index].versions.any { it.version == ref.version }) {
            "Unknown artifact version: $ref"
        }
        records[index] = records[index].copy(activeVersion = ref.version)
        writeRecords(records)
    }

    override fun addFeedback(ref: ArtifactRef, feedback: ArtifactFeedback) = synchronized(lock) {
        val records = loadRecords().toMutableList()
        val recordIndex = records.indexOfFirst { it.artifactId == ref.artifactId }
        require(recordIndex >= 0) { "Unknown artifact: ${ref.artifactId}" }
        val record = records[recordIndex]
        val versionIndex = record.versions.indexOfFirst { it.version == ref.version }
        require(versionIndex >= 0) { "Unknown version: ${ref.version}" }
        val versions = record.versions.toMutableList()
        versions[versionIndex] = versions[versionIndex].copy(
            feedback = versions[versionIndex].feedback + feedback,
        )
        records[recordIndex] = record.copy(versions = versions)
        writeRecords(records)
    }

    private fun validateInput(name: String, mime: String, body: String) {
        require(SAFE_NAME.matches(name)) { "Invalid artifact name" }
        require(mime in SUPPORTED_MIME) { "Unsupported MIME: $mime" }
        require(
            (mime == "text/html" && name.endsWith(".html", ignoreCase = true)) ||
                (mime == "text/markdown" && name.endsWith(".md", ignoreCase = true)),
        ) { "Filename extension does not match MIME" }
        val size = body.toByteArray(StandardCharsets.UTF_8).size
        require(size <= maxBodyBytes) { "Artifact exceeds $maxBodyBytes bytes" }
    }

    private fun loadRecords(): List<ArtifactRecord> {
        if (!manifest.isFile) return emptyList()
        return json.decodeFromString(manifest.readText(StandardCharsets.UTF_8))
    }

    private fun writeRecords(records: List<ArtifactRecord>) {
        root.mkdirs()
        val temp = File(root, "manifest-${UUID.randomUUID()}.tmp")
        temp.writeText(json.encodeToString(records), StandardCharsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                manifest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeBody(bytes: ByteArray): String {
        val sha = bytes.sha256()
        val target = File(bodies, sha)
        if (!target.exists()) {
            val temp = File(bodies, "$sha-${UUID.randomUUID()}.tmp")
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (!target.exists()) Files.move(temp.toPath(), target.toPath())
                else temp.delete()
            }
        }
        return sha
    }

    private fun normalizedSummary(summary: String, body: String): String =
        summary.trim().ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
            .replace(Regex("\\s+"), " ")
            .take(240)

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024
        val SAFE_NAME = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._ -]{0,119}")
        val SUPPORTED_MIME = setOf("text/markdown", "text/html")
    }
}
