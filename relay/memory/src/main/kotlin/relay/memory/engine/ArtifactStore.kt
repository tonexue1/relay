package relay.memory.engine

import java.io.File
import java.security.MessageDigest

interface ArtifactStore {
    fun put(text: String): String
    fun get(ref: String): String?
    fun size(): Int
}

class MemoryArtifactStore : ArtifactStore {
    private val blobs = linkedMapOf<String, String>()

    override fun put(text: String): String {
        val ref = sha256Hex(text)
        blobs.putIfAbsent(ref, text)
        return ref
    }

    override fun get(ref: String): String? = blobs[ref]

    override fun size(): Int = blobs.size
}

class FileArtifactStore(private val dir: File) : ArtifactStore {
    init {
        dir.mkdirs()
    }

    override fun put(text: String): String {
        val ref = sha256Hex(text)
        val file = File(dir, ref)
        if (!file.exists()) file.writeText(text)
        return ref
    }

    override fun get(ref: String): String? {
        val file = File(dir, ref)
        return if (file.isFile) file.readText() else null
    }

    override fun size(): Int = dir.listFiles()?.count { it.isFile } ?: 0
}

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
