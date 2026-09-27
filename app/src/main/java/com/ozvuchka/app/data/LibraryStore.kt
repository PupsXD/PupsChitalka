package com.ozvuchka.app.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Copies imported text into the app's private storage so reading survives source-file removal. */
class LibraryStore(context: Context) {
    private val directory = File(context.filesDir, "library").apply { mkdirs() }

    @Synchronized
    fun all(): List<Book> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
        ?.sortedByDescending(Book::addedAt)
        ?: emptyList()

    @Synchronized
    fun get(id: String): Book? = fileFor(id).takeIf(File::exists)?.let {
        runCatching { decode(JSONObject(it.readText(Charsets.UTF_8))) }.getOrNull()
    }

    @Synchronized
    fun save(book: Book) {
        require(book.chapters.isNotEmpty()) { "A book needs at least one chapter" }
        val target = AtomicFile(fileFor(book.id))
        val stream = target.startWrite()
        try {
            stream.write(encode(book).toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun updatePosition(id: String, chapterIndex: Int, chapterProgress: Float): Book? {
        val existing = get(id) ?: return null
        val updated = existing.copy(
            currentChapter = chapterIndex.coerceIn(existing.chapters.indices),
            chapterProgress = chapterProgress.coerceIn(0f, 1f),
        )
        save(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id)
        if (!file.exists()) return false
        AtomicFile(file).delete()
        return !file.exists()
    }

    private fun fileFor(id: String): File {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(id)) { "Invalid book id" }
        return File(directory, "$id.json")
    }

    private fun encode(book: Book) = JSONObject().apply {
        put("id", book.id)
        put("title", book.title)
        put("author", book.author)
        put("format", book.format)
        put("source", book.source)
        put("currentChapter", book.currentChapter)
        put("chapterProgress", book.chapterProgress.toDouble())
        put("addedAt", book.addedAt)
        put("warnings", JSONArray(book.warnings))
        put("chapters", JSONArray().apply {
            book.chapters.forEach { chapter ->
                put(JSONObject().apply {
                    put("title", chapter.title)
                    put("paragraphs", JSONArray(chapter.paragraphs))
                    put("sourceUrl", chapter.sourceUrl)
                    put("nextUrl", chapter.nextUrl)
                })
            }
        })
    }

    private fun decode(json: JSONObject): Book {
        val chapters = json.getJSONArray("chapters").let { array ->
            (0 until array.length()).map { index ->
                val chapter = array.getJSONObject(index)
                val paragraphs = chapter.getJSONArray("paragraphs")
                Chapter(
                    title = chapter.optString("title"),
                    paragraphs = (0 until paragraphs.length()).map(paragraphs::getString),
                    sourceUrl = chapter.optString("sourceUrl").takeUnless { it.isBlank() || it == "null" },
                    nextUrl = chapter.optString("nextUrl").takeUnless { it.isBlank() || it == "null" },
                )
            }
        }
        val warnings = json.optJSONArray("warnings")
        return Book(
            id = json.getString("id"),
            title = json.getString("title"),
            author = json.optString("author"),
            format = json.getString("format"),
            chapters = chapters,
            source = json.optString("source").takeUnless { it.isBlank() || it == "null" },
            currentChapter = json.optInt("currentChapter"),
            chapterProgress = json.optDouble("chapterProgress", 0.0).toFloat(),
            addedAt = json.optLong("addedAt"),
            warnings = if (warnings == null) emptyList() else (0 until warnings.length()).map(warnings::getString),
        )
    }
}
