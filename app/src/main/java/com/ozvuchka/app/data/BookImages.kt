package com.ozvuchka.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Pictures of imported books: one folder per book, loaded scaled to the screen and cached while shown. */
object BookImages {
    private val validName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun directory(context: Context, bookId: String): File {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(bookId)) { "Invalid book id" }
        return File(File(context.filesDir, "images"), bookId)
    }

    fun file(context: Context, bookId: String, name: String): File? =
        if (validName.matches(name) && !name.contains("..")) File(directory(context, bookId), name) else null

    /** The picture no wider than about [maxWidth] pixels, or null when it is missing. Call off the main thread. */
    fun load(context: Context, bookId: String, name: String, maxWidth: Int): Bitmap? {
        val key = "$bookId/$name@$maxWidth"
        cache.get(key)?.let { return it }
        val source = file(context, bookId, name)?.takeIf(File::exists) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth.coerceAtLeast(64)) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** The stored bytes of a picture, for export. */
    fun bytes(context: Context, bookId: String, name: String): ByteArray? =
        file(context, bookId, name)?.takeIf(File::exists)?.readBytes()

    fun delete(context: Context, bookId: String) {
        directory(context, bookId).deleteRecursively()
        cache.evictAll()
    }
}
