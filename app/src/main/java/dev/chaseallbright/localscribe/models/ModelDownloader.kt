package dev.chaseallbright.localscribe.models

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ModelDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

typealias DownloadProgress = (bytesDone: Long, bytesTotal: Long) -> Unit

/**
 * Byte-range-resumable, retrying file downloader. Mirrors the desktop project's
 * models.py::_download_model_file so both apps recover from interrupted downloads and
 * detect/repair a corrupt cache the same way -- verify-by-final-size, then move the
 * completed ".part" file into place.
 */
object ModelDownloader {
    private const val ATTEMPTS = 3
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun download(
        url: String,
        destination: File,
        expectedSizeHint: Long? = null,
        onProgress: DownloadProgress? = null
    ): Unit = withContext(Dispatchers.IO) {
        val expectedSize = expectedSizeHint ?: headContentLength(url)

        if (destination.isFile && (expectedSize == null || destination.length() == expectedSize)) {
            onProgress?.invoke(destination.length(), expectedSize ?: destination.length())
            return@withContext
        }

        val partial = File(destination.parentFile, destination.name + ".part")
        if (partial.isFile && expectedSize != null && partial.length() > expectedSize) {
            partial.delete()
        }

        var lastError: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                attemptDownload(url, destination, partial, expectedSize, onProgress)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                if (attempt == ATTEMPTS) {
                    throw ModelDownloadException(
                        "Download stalled or failed after $attempt attempts for ${destination.name}: ${e.message}",
                        e
                    )
                }
                delay(attempt * 1000L)
            }
        }

        if (!destination.isFile) {
            throw ModelDownloadException("Download did not produce ${destination.name}", lastError)
        }
    }

    private fun attemptDownload(
        url: String,
        destination: File,
        partial: File,
        expectedSize: Long?,
        onProgress: DownloadProgress?
    ) {
        var offset = if (partial.isFile) partial.length() else 0L
        if (expectedSize != null && offset == expectedSize) {
            partial.renameTo(destination)
            return
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalScribe-Model-Downloader")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
            }
        }

        connection.connect()
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ModelDownloadException("HTTP $status for $url")
            }
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (offset > 0 && !append) {
                offset = 0
            }

            val total = expectedSize ?: (connection.contentLengthLong.takeIf { it > 0 }?.let { it + offset })

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        offset += read
                        onProgress?.invoke(offset, total ?: offset)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (expectedSize != null && partial.length() != expectedSize) {
            throw ModelDownloadException(
                "${destination.name} ended at ${partial.length()} of $expectedSize bytes"
            )
        }
        if (!partial.renameTo(destination)) {
            throw ModelDownloadException("Could not move completed download into place: ${destination.name}")
        }
    }

    private fun headContentLength(url: String): Long? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalScribe-Model-Downloader")
        }
        return try {
            connection.connect()
            connection.contentLengthLong.takeIf { it > 0 }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
