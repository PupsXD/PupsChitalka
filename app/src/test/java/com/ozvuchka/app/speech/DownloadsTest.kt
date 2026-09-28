package com.ozvuchka.app.speech

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class DownloadsTest {
    private val data = Random(7).nextBytes(300_000)
    private val file = File.createTempFile("download-", ".part").apply { delete() }
    private var server: TinyServer? = null

    @After
    fun cleanUp() {
        server?.close()
        file.delete()
    }

    /** A one-file HTTP server that honours `Range` and can drop the first connection early. */
    private class TinyServer(
        private val data: ByteArray,
        private val honourRange: Boolean = true,
        private val dropFirstAfter: Int = -1,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}/ruvoice.apk"
        val ranges = CopyOnWriteArrayList<String>()
        private val requests = AtomicInteger()

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    client.use(::serve)
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            reader.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            ranges += range ?: "none"
            val first = requests.incrementAndGet() == 1
            val output = client.getOutputStream()
            val start = if (honourRange && range != null) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
            if (start >= data.size) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val body = data.copyOfRange(start, data.size)
            val status = if (start > 0) "206 Partial Content" else "200 OK"
            output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body, 0, if (first && dropFirstAfter >= 0) dropFirstAfter else body.size)
            output.flush()
        }

        override fun close() = socket.close()
    }

    private fun fetch(server: TinyServer, maxBytes: Long = 10_000_000): Long {
        this.server = server
        return downloadResumable(
            server.url, file, maxBytes, AtomicReference<HttpURLConnection?>(null), checkCancelled = {}, requireHttps = false,
        ) { _, _ -> }
    }

    @Test
    fun downloadsAWholeFile() {
        val server = TinyServer(data)
        assertEquals(data.size.toLong(), fetch(server))
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf("none"), server.ranges)
    }

    @Test
    fun resumesAPartialFile() {
        file.writeBytes(data.copyOfRange(0, 1_000))
        val server = TinyServer(data)
        assertEquals(data.size.toLong(), fetch(server))
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf("bytes=1000-"), server.ranges)
    }

    @Test
    fun retriesFromWhereADroppedConnectionStopped() {
        val server = TinyServer(data, dropFirstAfter = 50_000)
        assertEquals(data.size.toLong(), fetch(server))
        assertArrayEquals(data, file.readBytes())
        assertEquals("none", server.ranges.first())
        assertTrue(server.ranges.toString(), server.ranges[1].startsWith("bytes="))
    }

    @Test
    fun startsOverWhenTheServerIgnoresRanges() {
        file.writeBytes(ByteArray(1_000) { 1 })
        val server = TinyServer(data, honourRange = false)
        assertEquals(data.size.toLong(), fetch(server))
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun aCompleteFileIsKept() {
        file.writeBytes(data)
        assertEquals(data.size.toLong(), fetch(TinyServer(data)))
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun refusesAFileTooLarge() {
        try {
            fetch(TinyServer(data), maxBytes = 1_000)
            fail("a file over the limit was accepted")
        } catch (error: IllegalStateException) {
            assertEquals("файл слишком велик", error.message)
        }
    }

    @Test
    fun hashesAFile() {
        file.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
    }
}
