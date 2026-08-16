package org.example.test

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadProgress {
    data class InProgress(val bytesDone: Long, val bytesTotal: Long) : DownloadProgress()
    data class Done(val file: File) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
}

/**
 * "Bring your own model": the user either
 *   (a) picks a .gguf file already on their device via the system file
 *       picker (Storage Access Framework), or
 *   (b) pastes a direct Hugging Face file URL — e.g. the "download" link
 *       for a quant of Qwen2.5-Coder-GGUF — and we stream it to disk.
 * Either way the result lands in the app's private files dir, where
 * LlamaSession can mmap it directly.
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun listLocalModels(): List<File> =
        modelsDir.listFiles { f -> f.extension == "gguf" }?.toList().orEmpty()

    /** Copies a SAF-picked content:// Uri into app storage. */
    suspend fun importFromUri(uri: Uri, displayName: String): File =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val safeName = if (displayName.endsWith(".gguf")) displayName else "$displayName.gguf"
            val dest = File(modelsDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            } ?: error("Could not open the selected file")
            dest
        }

    /**
     * Downloads a GGUF file directly from a URL — e.g. a Hugging Face
     * "resolve/main/…gguf" link, such as one of the quants under
     * https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF
     *
     * Emits progress as it goes; the caller should collect this on a
     * lifecycle-aware scope and update a progress bar.
     */
    fun downloadFromUrl(url: String, fileName: String): Flow<DownloadProgress> = flow {
        val safeName = if (fileName.endsWith(".gguf")) fileName else "$fileName.gguf"
        val dest = File(modelsDir, safeName)
        val tmp = File(modelsDir, "$safeName.part")

        var connection: HttpURLConnection? = null
        try {
            var currentUrl = URL(url)
            // Hugging Face "resolve" links 302-redirect to a CDN; follow
            // manually so we don't silently drop query params some
            // HttpURLConnection versions strip on cross-host redirects.
            repeat(5) {
                val conn = currentUrl.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null) {
                        currentUrl = URL(location)
                        return@repeat
                    }
                }
                connection = conn
                return@repeat
            }

            val conn = connection ?: (currentUrl.openConnection() as HttpURLConnection).also {
                it.connect()
            }

            if (conn.responseCode !in 200..299) {
                emit(DownloadProgress.Failed("Server returned HTTP ${conn.responseCode}"))
                return@flow
            }

            val total = conn.contentLengthLong
            var done = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        emit(DownloadProgress.InProgress(done, total))
                    }
                }
            }

            tmp.renameTo(dest)
            emit(DownloadProgress.Done(dest))
        } catch (e: Exception) {
            tmp.delete()
            emit(DownloadProgress.Failed(e.message ?: "Download failed"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun deleteModel(file: File) = file.delete()
}
