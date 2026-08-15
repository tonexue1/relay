package relay.ondevice.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and verifies GGUF files under [rootDir].
 *
 * Incomplete transfers are kept as `*.partial` and resumed with `Range` when the
 * server answers 206. A 200 after a Range request means the server ignored it, so
 * the file is rewritten from byte 0. Progress is absolute (already + new) / total.
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
        if (temp.exists() && spec.expectedBytes > 0 && temp.length() > spec.expectedBytes) {
            temp.delete()
        }

        if (!isCompletePartial(temp, spec)) {
            download(spec, temp, onProgress)
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

    private fun isCompletePartial(temp: File, spec: ModelSpec): Boolean {
        if (!temp.isFile) return false
        if (spec.expectedBytes > 0 && temp.length() != spec.expectedBytes) return false
        if (spec.expectedBytes == 0L) return false
        return sha256(temp).equals(spec.sha256, ignoreCase = true)
    }

    private suspend fun download(
        spec: ModelSpec,
        temp: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val offset = if (temp.isFile) temp.length() else 0L
        val request = Request.Builder()
            .url(spec.downloadUrl)
            .get()
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    // Full body. If we asked for a range the server ignored it -- start over.
                    writeBody(response.body.byteStream(), temp, append = false, already = 0L, spec, onProgress)
                }
                206 -> {
                    writeBody(response.body.byteStream(), temp, append = true, already = offset, spec, onProgress)
                }
                416 -> {
                    if (!isCompletePartial(temp, spec)) {
                        temp.delete()
                        throw IOException("Download failed HTTP 416 for ${spec.id}")
                    }
                }
                else -> throw IOException("Download failed HTTP ${response.code} for ${spec.id}")
            }
        }
    }

    private suspend fun writeBody(
        input: InputStream,
        temp: File,
        append: Boolean,
        already: Long,
        spec: ModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val total = if (spec.expectedBytes > 0) spec.expectedBytes else already + 1
        input.use { stream ->
            FileOutputStream(temp, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = already
                onProgress(downloaded, total.coerceAtLeast(downloaded))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val reportedTotal = if (spec.expectedBytes > 0) spec.expectedBytes else downloaded
                    onProgress(downloaded, reportedTotal)
                }
            }
        }
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
