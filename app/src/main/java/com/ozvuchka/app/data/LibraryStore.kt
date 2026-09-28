package com.ozvuchka.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Copies imported text into the app's private storage so reading survives source-file removal. */
class LibraryStore(context: Context) {
    private val directory = File(context.filesDir, "library").apply { mkdirs() }

    /**
     * Reading positions change on every page turn and every narrated sentence. They live in a
     * small preferences file, so a position update never rewrites a multi-megabyte book file.
     */
    private val positions: SharedPreferences =
        context.applicationContext.getSharedPreferences("reading_positions", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Book> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { withPosition(decode(JSONObject(file.readText(Charsets.UTF_8)))) }.getOrNull() }
        ?.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        ?: emptyList()

    @Synchronized
    fun get(id: String): Book? = fileFor(id).takeIf(File::exists)?.let {
        runCatching { withPosition(decode(JSONObject(it.readText(Charsets.UTF_8)))) }.getOrNull()
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
        writePosition(book.id, book.currentChapter, book.chapterProgress, book.lastOpenedAt)
    }

    /**
     * Adds a chapter at the end unless a chapter from the same page is already there. The saved
     * reading position is left alone. Returns the updated book, or null when nothing changed.
     */
    @Synchronized
    fun appendChapter(id: String, chapter: Chapter): Book? {
        val book = get(id) ?: return null
        if (chapter.sourceUrl != null && book.chapters.any { it.sourceUrl == chapter.sourceUrl }) return null
        val updated = book.copy(chapters = book.chapters + chapter)
        val target = AtomicFile(fileFor(id))
        val stream = target.startWrite()
        try {
            stream.write(encode(updated).toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
        return updated
    }

    /** The book opened most recently, from the small positions file only. */
    fun lastOpenedId(): String? = positions.all.entries
        .mapNotNull { (id, value) -> (value as? String)?.split('|')?.getOrNull(2)?.toLongOrNull()?.let { id to it } }
        .filter { (id, _) -> runCatching { fileFor(id).exists() }.getOrDefault(false) }
        .maxByOrNull { it.second }?.first

    /** Cheap and safe to call often, from any thread. */
    fun updatePosition(id: String, chapterIndex: Int, chapterProgress: Float, openedAt: Long? = null) {
        val previousOpened = positions.getString(id, null)?.split('|')?.getOrNull(2)?.toLongOrNull() ?: 0L
        writePosition(id, chapterIndex.coerceAtLeast(0), chapterProgress.coerceIn(0f, 1f), openedAt ?: previousOpened)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val file = fileFor(id)
        if (!file.exists()) return false
        AtomicFile(file).delete()
        positions.edit().remove(id).apply()
        return !file.exists()
    }

    private fun writePosition(id: String, chapter: Int, progress: Float, openedAt: Long) {
        positions.edit().putString(id, "$chapter|$progress|$openedAt").apply()
    }

    private fun withPosition(book: Book): Book {
        val stored = positions.getString(book.id, null)?.split('|') ?: return book
        val chapter = stored.getOrNull(0)?.toIntOrNull() ?: return book
        val progress = stored.getOrNull(1)?.toFloatOrNull() ?: return book
        val openedAt = stored.getOrNull(2)?.toLongOrNull() ?: book.lastOpenedAt
        return book.copy(
            currentChapter = chapter.coerceIn(book.chapters.indices),
            chapterProgress = progress.coerceIn(0f, 1f),
            lastOpenedAt = maxOf(openedAt, book.lastOpenedAt),
        )
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
        put("lastOpenedAt", book.lastOpenedAt)
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
            lastOpenedAt = json.optLong("lastOpenedAt"),
        )
    }
}
