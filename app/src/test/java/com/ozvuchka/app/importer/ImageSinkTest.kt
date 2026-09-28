package com.ozvuchka.app.importer

import com.ozvuchka.app.data.ParagraphKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageSinkTest {
    @Test
    fun sizesAreReadFromPictureHeaders() {
        assertEquals(640 to 480, imageSize(png(640, 480)))
        assertEquals(300 to 200, imageSize(gif(300, 200)))
        assertEquals(1024 to 768, imageSize(jpeg(1024, 768)))
        assertEquals(800 to 600, imageSize(webpLossless(800, 600)))
        assertNull(imageSize(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun aFolderKeepsEachPictureOnceAndSkipsSpecks() {
        val folder = Files.createTempDirectory("pictures").toFile()
        try {
            val sink = FolderImageSink(folder)
            val first = sink.store(png(640, 480))!!
            assertEquals(ParagraphKind.IMAGE, first.kind)
            assertEquals("img1.png", first.image)
            assertEquals(640, first.width)
            assertSame(first, sink.store(png(640, 480)))
            assertNull(sink.store(png(4, 4)))
            assertEquals("img2.gif", sink.store(gif(300, 200))!!.image)
            assertEquals(setOf("img1.png", "img2.gif"), folder.list()!!.toSet())
        } finally {
            folder.deleteRecursively()
        }
    }

    companion object {
        fun png(width: Int, height: Int): ByteArray = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        ) + int32(width) + int32(height) + byteArrayOf(8, 6, 0, 0, 0)

        fun gif(width: Int, height: Int): ByteArray =
            "GIF89a".toByteArray() + byteArrayOf((width and 0xFF).toByte(), (width shr 8).toByte(), (height and 0xFF).toByte(), (height shr 8).toByte(), 0, 0, 0)

        fun jpeg(width: Int, height: Int): ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            // An APP0 segment first, then the frame header with the size.
            0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
            (height shr 8).toByte(), (height and 0xFF).toByte(), (width shr 8).toByte(), (width and 0xFF).toByte(), 3, 0, 0, 0,
        )

        fun webpLossless(width: Int, height: Int): ByteArray {
            val bits = (width - 1) or ((height - 1) shl 14)
            return "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8L".toByteArray() + byteArrayOf(0, 0, 0, 0, 0x2F) +
                byteArrayOf((bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte(), ((bits shr 16) and 0xFF).toByte(), ((bits shr 24) and 0xFF).toByte()) +
                ByteArray(8)
        }

        private fun int32(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }
}
