package com.ozvuchka.app.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AnnotationKind { BOOKMARK, HIGHLIGHT }

/** A bookmark or a highlighted sentence (with an optional note) at a place in a book. */
data class Annotation(
    val id: String = UUID.randomUUID().toString(),
    val kind: AnnotationKind,
    val chapter: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    /** The words at the place: the quote of a highlight, the opening words of a bookmark. */
    val text: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Bookmarks and highlights live next to the library, one small file per book. */
class AnnotationStore(context: Context) {
    private val directory = File(context.filesDir, "annotations").apply { mkdirs() }

    @Synchronized
    fun list(bookId: String): List<Annotation> {
        val file = fileFor(bookId).takeIf(File::exists) ?: return emptyList()
        return runCatching { decode(JSONArray(file.readText(Charsets.UTF_8))) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(bookId: String, annotations: List<Annotation>) {
        val file = fileFor(bookId)
        if (annotations.isEmpty()) {
            AtomicFile(file).delete()
            return
        }
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(encode(annotations).toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun delete(bookId: String) {
        AtomicFile(fileFor(bookId)).delete()
    }

    private fun fileFor(id: String): File {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(id)) { "Invalid book id" }
        return File(directory, "$id.json")
    }

    companion object {
        internal fun encode(annotations: List<Annotation>) = JSONArray().apply {
            annotations.forEach { annotation ->
                put(
                    JSONObject()
                        .put("id", annotation.id)
                        .put("kind", annotation.kind.name)
                        .put("chapter", annotation.chapter)
                        .put("paragraph", annotation.paragraph)
                        .put("start", annotation.start)
                        .put("end", annotation.end)
                        .put("text", annotation.text)
                        .put("note", annotation.note)
                        .put("createdAt", annotation.createdAt),
                )
            }
        }

        internal fun decode(json: JSONArray): List<Annotation> = (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val kind = runCatching { AnnotationKind.valueOf(item.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            Annotation(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                kind = kind,
                chapter = item.optInt("chapter"),
                paragraph = item.optInt("paragraph"),
                start = item.optInt("start"),
                end = item.optInt("end"),
                text = item.optString("text"),
                note = item.optString("note"),
                createdAt = item.optLong("createdAt"),
            )
        }
    }
}

/** A place in a chapter found by full-text search. */
data class SearchHit(val chapter: Int, val paragraph: Int, val start: Int, val end: Int, val snippet: String, val snippetMatch: IntRange)

/**
 * Finds [query] in the book, ignoring case and «ё». Stops after [limit] hits so a common word in a
 * long book stays quick.
 */
fun searchBook(chapters: List<Chapter>, query: String, limit: Int = 300): List<SearchHit> {
    val needle = foldForSearch(query.trim())
    if (needle.length < 2) return emptyList()
    val hits = mutableListOf<SearchHit>()
    chapters.forEachIndexed { chapterIndex, chapter ->
        chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            val haystack = foldForSearch(paragraph)
            var from = 0
            while (true) {
                val found = haystack.indexOf(needle, from)
                if (found < 0) break
                val snippetStart = (found - 48).coerceAtLeast(0).let { start ->
                    if (start == 0) 0 else paragraph.indexOf(' ', start).takeIf { it in start until found }?.plus(1) ?: start
                }
                val snippetEnd = (found + needle.length + 64).coerceAtMost(paragraph.length).let { end ->
                    if (end == paragraph.length) end else paragraph.lastIndexOf(' ', end).takeIf { it > found + needle.length } ?: end
                }
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < paragraph.length) "…" else ""
                val snippet = prefix + paragraph.substring(snippetStart, snippetEnd) + suffix
                val matchStart = prefix.length + found - snippetStart
                hits += SearchHit(
                    chapter = chapterIndex,
                    paragraph = paragraphIndex,
                    start = found,
                    end = found + needle.length,
                    snippet = snippet,
                    snippetMatch = matchStart until matchStart + needle.length,
                )
                if (hits.size >= limit) return hits
                from = found + needle.length
            }
        }
    }
    return hits
}

/** Same length as the input, so indices found in the folded text point into the original. */
private fun foldForSearch(text: String): String = buildString(text.length) {
    for (c in text) append(if (c == 'ё' || c == 'Ё') 'е' else c.lowercaseChar())
}
