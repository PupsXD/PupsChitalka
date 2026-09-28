package com.ozvuchka.app.speech

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads [url] into [file], resuming a previous partial file when the server supports ranges and
 * retrying a dropped connection a few times. Returns the size of the file.
 *
 * [checkCancelled] throws to stop; it is also called after a network error, so a cancellation that
 * closed [activeConnection] is not retried. [onProgress] gets the bytes in the file and the expected
 * total (0 when the server does not say).
 */
internal fun downloadResumable(
    url: String,
    file: File,
    maxBytes: Long,
    activeConnection: AtomicReference<HttpURLConnection?>,
    checkCancelled: () -> Unit,
    requireHttps: Boolean = true,
    onProgress: (bytes: Long, total: Long) -> Unit,
): Long {
    var existing = if (file.isFile) file.length() else 0L
    var attempt = 0
    while (true) {
        checkCancelled()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 40_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Ozvuchka-Android/2.0")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        activeConnection.set(connection)
        try {
            connection.connect()
            val code = connection.responseCode
            if (requireHttps) check(connection.url.protocol.equals("https", ignoreCase = true)) { "Небезопасная переадресация" }
            if (code == 416 && existing > 0) return existing // already complete
            check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                "сервер ответил HTTP $code"
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append) existing = 0
            val remaining = connection.contentLengthLong
            val total = if (remaining > 0) existing + remaining else 0L
            check(total <= maxBytes) { "файл слишком велик" }
            var bytes = existing
            var lastReport = 0L
            connection.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(file, append)).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytes += count
                        check(bytes <= maxBytes) { "файл слишком велик" }
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(bytes, total)
                        }
                    }
                }
            }
            // A connection closed early is retried like any other dropped connection.
            if (remaining > 0 && bytes != existing + remaining) throw IOException("загрузка прервалась")
            check(bytes > 0) { "получен пустой файл" }
            return bytes
        } catch (error: IOException) {
            checkCancelled()
            // A dropped mobile connection is common for large files: retry from where we stopped.
            attempt++
            if (attempt >= 4) throw error
            existing = if (file.isFile) file.length() else 0L
            Thread.sleep(1_500L * attempt)
        } finally {
            connection.disconnect()
            activeConnection.set(null)
        }
    }
}

/** SHA-256 of a file as lowercase hex. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
