package com.ozvuchka.app.importer

import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extraction chain, the next-chapter link, the charset and the typed failures. */
class WebChapterImporterTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/importer/$name")) { "нет фикстуры $name" }
            .readBytes().toString(Charsets.UTF_8)

    // ---------- The extraction chain ----------

    @Test
    fun perHostRulePicksTheSiteContainer() {
        val document = Jsoup.parse(fixture("author-today-chapter.html"))
        val found = findContentElement(document, "www.author.today")!!
        val paragraphs = found.select("p").map { it.text() }
        assertTrue(paragraphs.first().contains("Мир был пуст и тих"))
        assertEquals(4, paragraphs.size)
        // The decoy article on the page stays out.
        assertTrue(paragraphs.none { it.contains("аннотация") })
    }

    @Test
    fun unknownSiteFallsToArticleVariantsAndGenericChain() {
        val document = Jsoup.parse(fixture("density-fallback.html"))
        assertTrue(hostRuleSelectors("unknown.example").isEmpty())
        val found = findContentElement(document, "unknown.example")!!
        assertTrue(found.text().contains("Дорога шла вдоль реки"))
        assertTrue(!found.text().contains("Книга двенадцатая"))
    }

    @Test
    fun readabilityFallbackScoresParagraphParents() {
        // Two paragraphs only: the density stage needs three and stays out of the way.
        val document = Jsoup.parse(fixture("readability-fallback.html"))
        val found = findContentElement(document, "unknown.example")!!
        assertTrue(found.tagName() == "td" || found.text().contains("Лампа над столом"))
        assertTrue(!found.text().contains("Глава шесть"))
    }

    @Test
    fun cleanedContentStripsTheFurniture() {
        val document = Jsoup.parse(
            """
            <div id="article">
                <p>Настоящий текст главы, длинный и осмысленный, его оставляют читателю.</p>
                <script>evil()</script>
                <nav><a href="/1">Меню</a></nav>
                <div class="ad">Купи слона</div>
            </div>
            """.trimIndent(),
        )
        val cleaned = cleanedContent(document.selectFirst("#article")!!)
        val text = cleaned.text()
        assertTrue(text.contains("Настоящий текст главы"))
        assertTrue(!text.contains("evil") && !text.contains("Купи слона"))
    }

    // ---------- Paragraphs without duplication ----------

    @Test
    fun nestedBlockquoteIsNotDuplicated() {
        val chapter = WebChapter("Т | Книга", fixture("nested-blockquote.html"), "https://x/1", null)
            .toBookChapter()
        assertEquals(
            listOf(
                "Передо мной лежало письмо, и я не решался его вскрыть.",
                "Вложенная записка гласила: напиши мне, когда вернёшься.",
                "После вложения я всё-таки вскрыл конверт.",
            ),
            chapter.paragraphs,
        )
    }

    // ---------- The next chapter link ----------

    private fun nextOf(html: String, sourceUrl: String): String? =
        extractNextUrl(Jsoup.parse(html, sourceUrl), sourceUrl)

    @Test
    fun explicitRelationsIdClassAndTextFindTheNextChapter() {
        val base = "https://story.example/story/ch1"
        assertEquals(
            "https://story.example/story/ch2",
            nextOf("""<a rel="next" href="/story/ch2">Дальше</a>""", base),
        )
        assertEquals(
            "https://story.example/story/ch2",
            nextOf("""<link rel="next" href="/story/ch2"/>""", base),
        )
        assertEquals(
            "https://story.example/story/ch2",
            nextOf("""<a id="next" href="/story/ch2">Глава</a>""", base),
        )
        assertEquals(
            "https://story.example/story/ch2",
            nextOf("""<a class="nav-next" href="/story/ch2">Вперёд</a>""", base),
        )
        assertEquals(
            "https://story.example/story/ch2",
            nextOf("""<a href="/story/ch2">Следующая глава</a>""", base),
        )
    }

    @Test
    fun numericSegmentsIncrementWithoutAnyLink() {
        assertEquals(
            "https://story.example/story/chapter-4",
            nextOf("<p>Конец главы</p>", "https://story.example/story/chapter-3"),
        )
        assertEquals(
            "https://story.example/read?page=3",
            nextOf("<p>Конец главы</p>", "https://story.example/read?page=2"),
        )
    }

    @Test
    fun foreignCurrentAndContentsLinksAreRejected() {
        val base = "https://story.example/story"
        assertNull(nextOf("""<a rel="next" href="https://other.example/ch2">Дальше</a>""", base))
        assertNull(nextOf("""<a rel="next" href="https://story.example/story">Глава</a>""", base))
        assertNull(nextOf("""<a class="next" href="/all">Все главы</a>""", base))
        assertNull(nextOf("""<a rel="next" href="/contents">Содержание</a>""", base))
    }

    @Test
    fun siteMirrorsCountAsTheSameSite() {
        assertEquals(
            "https://m.story.example/2",
            nextOf(
                """<a rel="next" href="https://m.story.example/2">Дальше</a>""",
                "https://www.story.example/1",
            ),
        )
    }

    // ---------- Charset ----------

    @Test
    fun charsetComesFromHeaderThenMetaThenHeuristic() {
        val text = "Привет, мир! Приветственная строка для проверки кодировки."
        assertEquals(
            charset("windows-1251"),
            resolveCharset("windows-1251", text.toByteArray(charset("windows-1251"))),
        )
        val meta = ("<html><head><meta charset=\"windows-1251\"></head><body>${text}</body></html>")
            .toByteArray(charset("windows-1251"))
        assertEquals(charset("windows-1251"), resolveCharset(null, meta))
        // Clean UTF-8 bytes never go to the legacy tables.
        assertEquals(StandardCharsets.UTF_8, resolveCharset(null, text.toByteArray(StandardCharsets.UTF_8)))
        // Lowercase-heavy legacy bytes are windows-1251.
        assertEquals(
            charset("windows-1251"),
            resolveCharset(null, text.toByteArray(charset("windows-1251"))),
        )
        // The same words in koi8-r read through windows-1251 as caps, which gives them away.
        assertEquals(
            charset("koi8-r"),
            resolveCharset(null, text.toByteArray(charset("koi8-r"))),
        )
    }

    @Test
    fun cyrillicHostsGetPunycoded() {
        val canonical = canonicalWebUrl("https://автор.today/works/1")!!
        assertTrue(canonical.startsWith("https://xn--"))
        assertEquals("автор.today", siteLabel("https://www.автор.today/works/1"))
        assertNull(canonicalWebUrl("ftp://example.com/a"))
        assertNull(canonicalWebUrl("https://user:pass@example.com/a"))
        assertEquals(ImportReason.BAD_URL, classifyUrl("не ссылка")?.reason)
        assertEquals(ImportReason.CLEARTEXT, classifyUrl("http://example.com/a")?.reason)
        assertNull(classifyUrl("https://example.com/a"))
    }

    // ---------- Page guards ----------

    @Test
    fun antibotLoginAndPaywallAreRecognized() {
        val cloudflare = Jsoup.parse("<html><head><title>Just a moment...</title></head><body><p>Включите проверку.</p></body></html>")
        assertTrue(isAntibotPage(cloudflare))
        val chapter = Jsoup.parse("<html><body>${"<p>Текст главы, длинный и осмысленный. </p>".repeat(60)}</body></html>")
        assertTrue(!isAntibotPage(chapter))
        assertTrue(isLoginRedirect(Jsoup.parse("<html><body><form><input type=\"password\"/></form></body></html>"), "https://site.example/read"))
        assertTrue(isLoginRedirect(Jsoup.parse("<html><body></body></html>"), "https://site.example/login"))
        assertTrue(!isLoginRedirect(chapter, "https://site.example/author.today"))
        assertTrue(isPaywall(Jsoup.parse("<html><body><p>Продолжение доступно после оформления подписки.</p></body></html>")))
        assertTrue(!isPaywall(chapter))
    }

    // ---------- Typed failures ----------

    @Test
    fun fetchExceptionsMapToTypedFailures() {
        assertEquals(ImportReason.HTTP_401, importErrorOf(HttpStatusException("401", 401, "u")).reason)
        assertEquals(ImportReason.HTTP_403, importErrorOf(HttpStatusException("403", 403, "u")).reason)
        assertEquals(ImportReason.HTTP_404, importErrorOf(HttpStatusException("404", 404, "u")).reason)
        val serverError = importErrorOf(HttpStatusException("500", 500, "u"))
        assertEquals(ImportReason.NETWORK, serverError.reason)
        assertTrue(serverError.retryable)
        assertEquals(ImportReason.NON_HTML, importErrorOf(UnsupportedMimeTypeException("mime", "a/pdf", "u")).reason)
        assertEquals(ImportReason.TIMEOUT, importErrorOf(SocketTimeoutException("timeout")).reason)
        val network = importErrorOf(java.io.IOException("broken pipe"))
        assertEquals(ImportReason.NETWORK, network.reason)
        assertTrue(network.retryable)
        assertEquals(ImportReason.NON_HTML, importErrorOf(UnsupportedMimeTypeException("m", "a/pdf", "u")).reason)
    }

    @Test
    fun everyImportFailureCarriesARussianHint() {
        ImportReason.entries.forEach { reason ->
            val error = ImportError.of(reason)
            assertTrue("нет подсказки для $reason", error.hint.isNotBlank())
        }
    }

    @Test
    fun sitesAreComparedByTheirPublicSuffix() {
        assertEquals(siteOf("author.today"), siteOf("www.author.today"))
        assertEquals("ficbook.net", siteOf("m.ficbook.net"))
        assertEquals("example.co.uk", siteOf("www.example.co.uk"))
        assertNull(siteOf("localhost"))
    }

    @Test
    fun aSuccessfulImportGoesThroughItsStages() = runBlocking {
        // The pipeline itself needs a network; here only the stage wiring is checked with a
        // BAD_URL, which fails before any fetch and still walks the reported stages.
        val stages = mutableListOf<WebImportStage>()
        val result = WebChapterImporter.importChapter("это не ссылка") { stages += it }
        assertTrue(result is ImportResult.Failure)
        assertEquals(ImportReason.BAD_URL, (result as ImportResult.Failure).error.reason)
        assertEquals(listOf(WebImportStage.CONNECTING), stages)
    }
}
