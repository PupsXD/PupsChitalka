package com.ozvuchka.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsTest {
    @Test
    fun annotationsSurviveStorage() {
        val saved = listOf(
            Annotation(id = "a", kind = AnnotationKind.BOOKMARK, chapter = 2, paragraph = 5, start = 10, end = 40, text = "Начало страницы"),
            Annotation(id = "b", kind = AnnotationKind.HIGHLIGHT, chapter = 0, paragraph = 1, start = 0, end = 12, text = "Цитата", note = "Мысль", createdAt = 42),
        )
        assertEquals(saved.map { it.copy(createdAt = it.createdAt) }, AnnotationStore.decode(AnnotationStore.encode(saved)))
    }

    @Test
    fun searchIgnoresCaseAndYoAndPointsIntoTheText() {
        val chapters = listOf(
            Chapter("Первая", listOf("Ёжик шёл в тумане.", "Туман густел, а ёжик шёл дальше.")),
            Chapter("Вторая", listOf("Здесь ежика нет.")),
        )
        val hits = searchBook(chapters, "ЕЖИК")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 0, 1), hits.map { it.chapter })
        val second = hits[1]
        assertEquals("ёжик", chapters[0].paragraphs[second.paragraph].substring(second.start, second.end))
        assertEquals("ёжик", second.snippet.substring(second.snippetMatch.first, second.snippetMatch.last + 1))
        assertTrue(searchBook(chapters, "е").isEmpty())
        assertEquals(2, searchBook(chapters, "ежик", limit = 2).size)
    }
}
