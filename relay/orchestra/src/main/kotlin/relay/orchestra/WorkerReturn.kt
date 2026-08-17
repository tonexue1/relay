package relay.orchestra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class WorkerStatus { ok, partial, failed }

@Serializable
data class WorkerReturn(
    val status: WorkerStatus,
    val findings: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val artifactRefs: List<ArtifactRef> = emptyList(),
) {
    companion object
}

internal val OrchestraJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun WorkerReturn.toJson(): String = OrchestraJson.encodeToString(WorkerReturn.serializer(), this)

internal fun WorkerReturn.Companion.fromJson(raw: String): WorkerReturn =
    OrchestraJson.decodeFromString(WorkerReturn.serializer(), raw)
