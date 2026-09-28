package com.ozvuchka.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import java.io.File

/** Book covers, stored once at import as a modest JPEG and cached in memory while shown. */
object Covers {
    private const val STORED_HEIGHT = 900
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun file(context: Context, bookId: String): File {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(bookId)) { "Invalid book id" }
        return File(File(context.filesDir, "covers").apply { mkdirs() }, "$bookId.jpg")
    }

    fun exists(context: Context, bookId: String): Boolean = file(context, bookId).exists()

    /** Decodes [image] (JPEG, PNG, WebP…), scales it down and stores it; false if it is not an image. */
    fun save(context: Context, bookId: String, image: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= STORED_HEIGHT) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scaled = if (bitmap.height > STORED_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * STORED_HEIGHT / bitmap.height, STORED_HEIGHT, true)
        } else bitmap
        val target = AtomicFile(file(context, bookId))
        val stream = target.startWrite()
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, 86, stream)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            return false
        }
        cache.remove(bookId)
        return true
    }

    /** The cover no larger than [maxHeight] pixels, or null when the book has none. Call off the main thread. */
    fun load(context: Context, bookId: String, maxHeight: Int = STORED_HEIGHT): Bitmap? {
        val key = "$bookId@$maxHeight"
        cache.get(key)?.let { return it }
        val source = file(context, bookId).takeIf(File::exists) ?: return null
        val bitmap = BitmapFactory.decodeFile(source.path) ?: return null
        val sized = if (bitmap.height > maxHeight) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * maxHeight / bitmap.height, maxHeight, true)
        } else bitmap
        cache.put(key, sized)
        return sized
    }

    fun delete(context: Context, bookId: String) {
        AtomicFile(file(context, bookId)).delete()
        cache.evictAll()
    }
}
