package relay.orchestra

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class ArtifactRef(
    val runId: String,
    val name: String,
) {
    val uri: String get() = "artifact://$runId/$name"
}

interface ArtifactStore {
    suspend fun put(runId: String, name: String, text: String): ArtifactRef

    suspend fun get(ref: ArtifactRef): String
}

/** In-memory store for tests and JVM use. Files-backed store lives in samples. */
class InMemoryArtifactStore : ArtifactStore {
    private val lock = Mutex()
    private val data = mutableMapOf<String, String>()

    override suspend fun put(runId: String, name: String, text: String): ArtifactRef {
        lock.withLock { data[key(runId, name)] = text }
        return ArtifactRef(runId, name)
    }

    override suspend fun get(ref: ArtifactRef): String =
        lock.withLock {
            data[key(ref.runId, ref.name)]
                ?: throw NoSuchElementException("missing artifact ${ref.uri}")
        }

    private fun key(runId: String, name: String): String = "$runId/$name"
}
