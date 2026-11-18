package com.ozvuchka.app.importer

import java.net.URI
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every labeled offline fixture through the production extractor and refusal contracts. */
class WebQualityCorpusTest {
    private data class Case(
        val id: String,
        val file: String,
        val family: String,
        val split: String,
        val expected: String,
        val nextUrl: String,
    )

    private fun cases(): List<Case> {
        val lines = javaClass.getResource("/web-quality-corpus/manifest.tsv")!!.readText().lines().drop(1)
        return lines.filter(String::isNotBlank).map { line ->
            val fields = line.split('\t')
            Case(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
        }
    }

    @Test
    fun allLabeledPagesMeetTheirExtractionOrRefusalContract() {
        val cases = cases()
        assertEquals("the corpus must stay within the requested 60-100 page range", 72, cases.size)
        assertTrue(cases.all { it.family.startsWith("synthetic:") })
        assertEquals(48, cases.count { it.expected == "success" })
        assertEquals(16, cases.count { it.expected in setOf("login", "paywall", "antibot", "empty") })
        assertEquals(8, cases.count { it.expected == "next-valid" || it.expected == "next-wrong" })
        assertEquals(40, cases.count { it.split == "fit" })
        assertEquals(32, cases.count { it.split == "holdout" })

        for (case in cases) {
            val html = javaClass.getResource("/web-quality-corpus/${case.file}")!!.readText()
            val sourceUrl = when {
                case.expected.startsWith("next-") -> {
                    val bookId = 700 + case.id.substringAfter('-').toInt() - 1
                    "https://litmir.club/br/?b=$bookId&p=2"
                }
                case.expected == "login" -> "https://fixture.example/login"
                else -> "https://fixture.example/${case.id.lowercase()}/chapter"
            }
            val document = Jsoup.parse(html, sourceUrl)
            when (case.expected) {
                "success", "next-valid", "next-wrong" -> {
                    val host = URI(sourceUrl).host
                    val article = findContentElement(document, host)
                    assertNotNull("${case.id}: expected chapter candidate", article)
                    val content = chapterContent(document, article!!)
                    val text = content.text()
                    assertTrue("${case.id}: minimum narrative length", text.length >= MIN_CHAPTER_CHARS)
                    assertTrue("${case.id}: page marker retained", text.contains("MARKER-${case.id}"))
                    assertFalse("${case.id}: navigation leaked", text.contains("Каталог"))
                    val actualNext = extractNextUrl(document, sourceUrl)
                    when (case.expected) {
                        "next-valid" -> assertEquals("${case.id}: same-book next link", case.nextUrl, actualNext)
                        "next-wrong" -> assertNull("${case.id}: wrong-book link and URL fallback", actualNext)
                        else -> assertNull("${case.id}: no next link expected", actualNext)
                    }
                }
                "login" -> assertTrue("${case.id}: login refusal", isLoginRedirect(document, sourceUrl))
                "paywall" -> assertTrue("${case.id}: paywall refusal", isPaywall(document))
                "antibot" -> assertTrue("${case.id}: challenge refusal", isAntibotPage(document))
                "empty" -> assertNull("${case.id}: no chapter candidate", findContentElement(document, "fixture.example"))
                else -> error("Unknown corpus expectation: ${case.expected}")
            }
        }
    }
}
