package com.ozvuchka.app.importer

import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import java.io.File
import java.security.MessageDigest

/** Where an importer puts the pictures of a book; it answers with the picture's style, or null to leave it out. */
fun interface ImageSink {
    /** [bytes] is an encoded picture: PNG, JPEG, GIF or WebP. */
    fun store(bytes: ByteArray): ParagraphStyle?

    companion object {
        /** Keeps no pictures, as before pictures were supported. */
        val NONE = ImageSink { null }
    }
}

/**
 * Stores a book's pictures in [folder], named in the order they come. The same picture used again
 * (an ornament after every chapter) is stored once; specks and spacers are left out.
 */
class FolderImageSink(private val folder: File, private val budgetBytes: Long = 400L * 1024 * 1024) : ImageSink {
    private val stored = HashMap<String, ParagraphStyle>()
    private var used = 0L
    var count = 0
        private set

    override fun store(bytes: ByteArray): ParagraphStyle? {
        val (width, height) = imageSize(bytes) ?: return null
        if (width < 16 || height < 16) return null
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        stored[digest]?.let { return it }
        if (used + bytes.size > budgetBytes) return null
        if (!folder.exists() && !folder.mkdirs()) return null
        val name = "img${count + 1}.${imageExtension(bytes)}"
        File(folder, name).writeBytes(bytes)
        count++
        used += bytes.size
        return ParagraphStyle(ParagraphKind.IMAGE, name, width, height).also { stored[digest] = it }
    }
}

internal fun imageExtension(bytes: ByteArray): String = when {
    bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
    bytes.startsWith(0xFF, 0xD8) -> "jpg"
    bytes.startsWith('G'.code, 'I'.code, 'F'.code) -> "gif"
    bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> "bin"
}

private fun ByteArray.startsWith(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.u32be(at: Int): Int = (u16be(at) shl 16) or u16be(at + 2)

/** Width and height in pixels, read from the header of a PNG, JPEG, GIF or WebP picture. */
fun imageSize(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    when (imageExtension(bytes)) {
        "png" -> if (bytes.size >= 24) bytes.u32be(16) to bytes.u32be(20) else null
        "gif" -> if (bytes.size >= 10) bytes.u16le(6) to bytes.u16le(8) else null
        "webp" -> webpSize(bytes)
        "jpg" -> jpegSize(bytes)
        else -> null
    }
}.getOrNull()?.takeIf { (width, height) -> width in 1..30_000 && height in 1..30_000 }

private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    var at = 2
    while (at + 9 < bytes.size) {
        if (bytes.u8(at) != 0xFF) {
            at++
            continue
        }
        val marker = bytes.u8(at + 1)
        // Start-of-frame markers carry the size; the others are skipped by their length.
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            return bytes.u16be(at + 7) to bytes.u16be(at + 5)
        }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
            at += 2
            continue
        }
        at += 2 + bytes.u16be(at + 2)
    }
    return null
}

private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
        "VP8 " -> (bytes.u16le(26) and 0x3FFF) to (bytes.u16le(28) and 0x3FFF)
        "VP8L" -> {
            val bits = bytes.u8(21) or (bytes.u8(22) shl 8) or (bytes.u8(23) shl 16) or (bytes.u8(24) shl 24)
            ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
        }
        "VP8X" -> (1 + (bytes.u8(24) or (bytes.u8(25) shl 8) or (bytes.u8(26) shl 16))) to
            (1 + (bytes.u8(27) or (bytes.u8(28) shl 8) or (bytes.u8(29) shl 16)))
        else -> null
    }
}
