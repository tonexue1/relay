package relay.ondevice.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and verifies GGUF files under [rootDir].
 *
 * First slice: whole-file overwrite (no resume). Progress is reported as bytes downloaded.
 */
class ModelStore(
    private val rootDir: File,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    init {
        rootDir.mkdirs()
    }

    fun localFile(spec: ModelSpec): File = File(rootDir, spec.fileName)

    fun isReady(spec: ModelSpec): Boolean {
        val file = localFile(spec)
        if (!file.isFile || file.length() != spec.expectedBytes) return false
        return sha256(file).equals(spec.sha256, ignoreCase = true)
    }

    suspend fun ensurePresent(
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (isReady(spec)) return@withContext localFile(spec)

        val target = localFile(spec)
        val temp = File(rootDir, "${spec.fileName}.partial")
        if (temp.exists()) temp.delete()

        val request = Request.Builder().url(spec.downloadUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed HTTP ${response.code} for ${spec.id}")
            }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: spec.expectedBytes
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }

        val digest = sha256(temp)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            temp.delete()
            throw IOException("SHA-256 mismatch for ${spec.id}: expected ${spec.sha256}, got $digest")
        }
        if (spec.expectedBytes > 0 && temp.length() != spec.expectedBytes) {
            temp.delete()
            throw IOException(
                "Size mismatch for ${spec.id}: expected ${spec.expectedBytes}, got ${temp.length()}",
            )
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }

    companion object {
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
