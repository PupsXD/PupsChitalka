package com.ozvuchka.app.importer

import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The scored parent is the chapter's own cell, not the teaser wall.
        assertEquals("td", found.tagName())
        assertTrue(found.text().contains("Лампа над столом"))
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

    // ---------- Litmir chapters ----------

    private val litmirUrl = "https://litmir.club/br/?b=149221&p=2"

    @Test
    fun litmirSitesReadTheirBookTextContainer() {
        for (site in listOf("litmir.club", "litmir.me", "litmir.net")) {
            for (host in listOf(site, "www.$site")) {
                assertEquals(
                    "правила для $host",
                    listOf("div.BookText div.page_text", "div.page_text"),
                    hostRuleSelectors(host),
                )
            }
        }
        val document = Jsoup.parse(fixture("litmir-chapter.html"), litmirUrl)
        val found = findContentElement(document, "litmir.club")!!
        // The chapter itself, not the outer div that carries the whole page.
        assertEquals("page_text", found.className())
    }

    @Test
    fun litmirChapterDropsTheSiteFurnitureAndKeepsItsFootnotes() {
        val document = Jsoup.parse(fixture("litmir-chapter.html"), litmirUrl)
        val article = findContentElement(document, "litmir.club")!!
        val content = chapterContent(document, article)
        val text = content.text()
        assertTrue(text.contains("Билли обратил внимание на одного из парней"))
        assertTrue(text.contains("Таксономист как по характеру"))
        // The footnote sits beside the chapter container but belongs to the chapter.
        assertTrue(text.contains("Примечания"))
        assertTrue(text.contains("Организация из вселенной «Стар Трека»"))
        for (junk in listOf("Войти на сайт", "ЖАНРЫ", "Стр.2", "Следующая страница", "Оглавление")) {
            assertFalse("в главу попало: $junk", text.contains(junk))
        }
        val chapter = WebChapter("x", content.html(), litmirUrl, null).toBookChapter()
        assertTrue(chapter.paragraphs.first().startsWith("Билли обратил внимание"))
        assertTrue(chapter.paragraphs.contains("Примечания"))
        assertTrue(chapter.paragraphs.any { it.contains("Стар Трека") })
    }

    @Test
    fun litmirTitleAndNextPageComeFromTheStructuredMetadata() {
        val document = Jsoup.parse(fixture("litmir-chapter.html"), litmirUrl)
        val article = findContentElement(document, "litmir.club")!!
        // document.title() and og:title are the site's «Читать ... - Страница 2» line.
        assertEquals("Стр.2 | Кракен", WebChapterImporter.extractTitle(document, article))
        assertEquals("https://litmir.club/br/?b=149221&p=3", extractNextUrl(document, litmirUrl))
    }

    @Test
    fun onlyAnAnnouncedStandardEbooksPartDividerIsStructuralEmpty() {
        val url = "https://standardebooks.org/ebooks/robert-louis-stevenson/treasure-island/text/part-2"
        val divider = Jsoup.parse(
            """<html><head><title>Treasure Island - Part II: The Sea-Cook</title></head><body>
                <h2><p epub:type="title">The Sea-Cook</p></h2>
                <a rel="prev" href="chapter-6">Previous</a>
                <a rel="next" href="chapter-7">Next</a>
            </body></html>""",
            url,
        )
        assertEquals(
            "https://standardebooks.org/ebooks/robert-louis-stevenson/treasure-island/text/chapter-7",
            WebChapterImporter.structuralEmptyNext(divider, url),
        )
        assertNull(WebChapterImporter.structuralEmptyNext(divider, "https://example.org/ebooks/a/b/text/part-2"))
        assertNull(WebChapterImporter.structuralEmptyNext(divider, url.replace("part-2", "chapter-6")))
        val wrongBook = Jsoup.parse(
            """<html><head><title>Treasure Island - Part II: The Sea-Cook</title></head><body>
                <p>The Sea-Cook</p><a rel="next" href="/ebooks/someone/other-book/text/chapter-7">Next</a>
            </body></html>""",
            url,
        )
        assertNull(WebChapterImporter.structuralEmptyNext(wrongBook, url))

        val login = Jsoup.parse(
            """<html><head><title>Treasure Island - Part II: The Sea-Cook</title></head><body>
                <form><input type="password"></form><a rel="next" href="chapter-7">Next</a>
            </body></html>""",
            url,
        )
        assertNull(WebChapterImporter.structuralEmptyNext(login, url))
    }

    @Test
    fun nextChapterSkipsOnlyBoundedDeclaredStructuralDividers() = runBlocking {
        val chapter = ImportResult.Success(
            WebChapter("Chapter 7", "<p>${"A full chapter paragraph. ".repeat(40)}</p>", "https://e.org/7", null),
        )
        val calls = mutableListOf<String>()
        val result = WebChapterImporter.importNextChapter("https://e.org/6") { url ->
            calls += url
            if (url.endsWith("/6") || url.endsWith("/part-2")) {
                ImportResult.StructuralEmpty(url, if (url.endsWith("/6")) "https://e.org/part-2" else "https://e.org/7")
            } else chapter
        }
        assertEquals(listOf("https://e.org/6", "https://e.org/part-2", "https://e.org/7"), calls)
        assertEquals(chapter, result)

        val ordinaryEmpty = ImportResult.Failure(ImportError.of(ImportReason.EMPTY_CONTENT))
        assertEquals(ordinaryEmpty, WebChapterImporter.importNextChapter("https://e.org/6") { ordinaryEmpty })
        assertEquals(
            ImportReason.EMPTY_CONTENT,
            (WebChapterImporter.importNextChapter("https://e.org/6") { ImportResult.StructuralEmpty(it, "https://e.org/6") }
                as ImportResult.Failure).error.reason,
        )
        assertEquals(
            ImportReason.EMPTY_CONTENT,
            (WebChapterImporter.importNextChapter("https://e.org/6") { ImportResult.StructuralEmpty(it, "https://e.org/7") }
                as ImportResult.Failure).error.reason,
        )
        assertEquals(
            ImportReason.LOGIN_REQUIRED,
            (WebChapterImporter.importNextChapter("https://e.org/6") { ImportResult.Failure(ImportError.of(ImportReason.LOGIN_REQUIRED)) }
                as ImportResult.Failure).error.reason,
        )
        assertEquals(
            ImportReason.ANTIBOT,
            (WebChapterImporter.importNextChapter("https://e.org/6") { ImportResult.Failure(ImportError.of(ImportReason.ANTIBOT)) }
                as ImportResult.Failure).error.reason,
        )
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
    fun oversizedNumericSegmentsIncrementWithoutOverflow() {
        // A 10-digit post id does not fit an Int: the guess goes one up, and never throws.
        assertEquals(
            "https://story.example/post/1712345679",
            nextOf("<p>Конец главы</p>", "https://story.example/post/1712345678"),
        )
        assertEquals(
            "https://story.example/read?page=100000000000",
            nextOf("<p>Конец главы</p>", "https://story.example/read?page=99999999999"),
        )
        // Beyond a Long there is no guess at all instead of a wrapped one.
        assertNull(nextOf("<p>Конец главы</p>", "https://story.example/post/99999999999999999999"))
        assertNull(nextOf("<p>Конец главы</p>", "https://story.example/read?page=99999999999999999999999"))
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
    fun tocPaginationIsNotTakenForTheNextChapter() {
        // The TOC's «下一页» and bare-number pager walk /page/N; the fallback still counts
        // the chapter segment itself.
        assertEquals(
            "https://book.example/posts/1712345679",
            nextOf(fixture("toc-pagination.html"), "https://book.example/posts/1712345678"),
        )
        // A pager that only moves the page query is not a chapter either.
        assertTrue(isPageTurn("https://site.example/book/1", "https://site.example/book/1/page/2"))
        assertTrue(isPageTurn("https://site.example/read?page=2", "https://site.example/read?page=3"))
        // ...unless the link itself really says "next" on the same path.
        assertFalse(isPageTurn("https://site.example/read?page=2", "https://site.example/read?page=3", labelledNext = true))
        assertFalse(isPageTurn("https://site.example/story/chapter-5", "https://site.example/story/chapter-6"))
    }

    @Test
    fun menuBodyFailsTheQualityGuard() {
        // A page that is all links has no chapter to give, even as the very last resort.
        assertNull(findContentElement(Jsoup.parse(fixture("menu-page.html")), "unknown.example"))
    }

    @Test
    fun aPageShellIsDroppedWithoutCuttingShortReplies() {
        val document = Jsoup.parse(fixture("chapter-shell.html"), "https://story.example/ch/1")
        val found = findContentElement(document, "unknown.example")!!
        assertEquals("chapter", found.className())
        val paragraphs = found.select("p").map { it.text() }
        assertEquals(4, paragraphs.size)
        assertTrue(paragraphs[2] == "— Ты слышишь?" && paragraphs[3] == "— Нет.")
        assertFalse(found.text().contains("Каталог"))
    }

    @Test
    fun verseWithoutParagraphsIsStillAChapter() {
        val document = Jsoup.parse(fixture("verse-br.html"), "https://poems.example/1")
        val found = findContentElement(document, "unknown.example")!!
        assertEquals("poem", found.className())
        val chapter = WebChapter("Стихи", cleanedContent(found).html(), "https://poems.example/1", null).toBookChapter()
        assertTrue(chapter.paragraphs.any { it.contains("Над тихой рекой") })
        assertTrue(chapter.paragraphs.any { it.contains("Я слушаю долго") })
        assertTrue(chapter.paragraphs.size >= 6)
    }

    @Test
    fun aPaywallTeaserIsNotTakenForAChapter() {
        val teaser = "Продолжение доступно после оформления подписки на сайте."
        assertTrue(isPaywallText(teaser))
        assertTrue(isPaywall(Jsoup.parse("<html><body><p>$teaser</p></body></html>")))
        // A real chapter long enough to carry the word «подписка» is not a paywall.
        val chapter = ("Герой вспоминал подписку на старую газету, " + "и это воспоминание тянулось долго. ".repeat(60))
        assertFalse(isPaywallText(chapter))
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

    @Test
    fun errorStatusWithChallengeBodyMapsToAntibot() {
        // Real protection answers 403/503 with a challenge page in the body.
        val challenge = "<html><head><title>Just a moment...</title></head>" +
            "<body>Checking your browser before accessing the site</body>"
        assertEquals(
            ImportReason.ANTIBOT,
            WebChapterImporter.statusErrorOf(403, "Forbidden", "https://site.example/1", "utf-8", challenge.toByteArray()).reason,
        )
        assertEquals(
            ImportReason.ANTIBOT,
            WebChapterImporter.statusErrorOf(
                503,
                "Service Unavailable",
                "https://site.example/1",
                "utf-8",
                "<html><body><center>ddos-guard</center></body></html>".toByteArray(),
            ).reason,
        )
        // A plain refusal keeps its own typed failure.
        assertEquals(
            ImportReason.HTTP_403,
            WebChapterImporter.statusErrorOf(
                403,
                "Forbidden",
                "https://site.example/1",
                "utf-8",
                "<html><body><p>Доступ запрещён администратором</p></body></html>".toByteArray(),
            ).reason,
        )
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
    fun retryableFollowsTheFailureType() {
        val retryable = setOf(ImportReason.NETWORK, ImportReason.TIMEOUT, ImportReason.ANTIBOT, ImportReason.HTTP_403)
        ImportReason.entries.forEach { reason ->
            assertEquals("retryable для $reason", reason in retryable, ImportError.of(reason).retryable)
        }
    }

    @Test
    fun finishedImportRetiresAfterTheSheetHadItsChance() = runBlocking {
        // A DONE seen through to the end is cleared, so the next opened sheet starts clean.
        var stale: WebImportStage? = WebImportStage.DONE
        var staleCleared = false
        retireFinishedWebImport({ stale }, { staleCleared = true }, settleMillis = 20)
        assertTrue(staleCleared)

        // The sheet closed itself meanwhile: the stage is already gone, nothing to clear.
        var raced: WebImportStage? = WebImportStage.DONE
        var racedCleared = false
        val job = launch { retireFinishedWebImport({ raced }, { racedCleared = true }, settleMillis = 20) }
        raced = null
        job.join()
        assertFalse(racedCleared)

        // A running import is never touched.
        var running: WebImportStage? = WebImportStage.CONNECTING
        var runningCleared = false
        retireFinishedWebImport({ running }, { runningCleared = true }, settleMillis = 20)
        assertFalse(runningCleared)
    }

    @Test
    fun aBadUrlImportReportsItsStageAndTypedFailure() = runBlocking {
        // The pipeline itself needs a network; here only the stage wiring is checked with a
        // BAD_URL, which fails before any fetch and still walks the reported stages.
        val stages = mutableListOf<WebImportStage>()
        val result = WebChapterImporter.importChapter("это не ссылка") { stages += it }
        assertTrue(result is ImportResult.Failure)
        assertEquals(ImportReason.BAD_URL, (result as ImportResult.Failure).error.reason)
        assertEquals(listOf(WebImportStage.CONNECTING), stages)
    }
}
