package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Book
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** The end of a whole-book import: the book itself, or the typed reason it did not arrive. */
sealed interface WebBookResult {
    data class Success(val book: Book, val cover: ByteArray? = null) : WebBookResult
    data class Failure(val error: ImportError) : WebBookResult
}

/**
 * Imports a whole book from a reader site that publishes a file of it. The page must name the
 * file itself — the download link is read from the book page, never guessed from an id — and the
 * answer is checked by its bytes, not by what the server says about them. Only the declared FB2
 * (a plain file or a ZIP around one) is read; EPUB is left to the file picker until its web path
 * has the same tests. Nothing here bypasses a login, a payment or a bot check: such an answer is
 * a typed failure, and the ordinary chapter import stays available for the same page.
 */
object WebBookImporter {
    /** The sites whose file list this importer knows; mirrors share the markup. */
    private val BOOK_SITES = setOf("litmir.club", "litmir.me", "litmir.net")

    /** A book file is bigger than a chapter page but still has to fit in memory. */
    private const val MAX_BOOK_BYTES = 32 * 1024 * 1024

    /** The smallest thing that can be an FB2 book. */
    private const val MIN_BOOK_BYTES = 1_024

    /** Whether the import sheet may offer a whole book for [url]. */
    fun supports(url: String): Boolean {
        val canonical = canonicalWebUrl(url) ?: return false
        // The manifest forbids cleartext traffic: the sheet never offers an address it cannot fetch.
        if (canonical.startsWith("http://")) return false
        val host = runCatching { URI(canonical).host }.getOrNull() ?: return false
        return siteOf(host) in BOOK_SITES
    }

    /**
     * Downloads and parses the book [url] points at: a chapter page or the book's own page.
     * [images] stores the pictures the file carries; the returned book has no id of its own to
     * keep, so the caller picks one before saving it.
     */
    suspend fun importBook(
        url: String,
        onStage: (WebImportStage) -> Unit = {},
        images: ImageSink = ImageSink.NONE,
    ): WebBookResult = withContext(Dispatchers.IO) {
        onStage(WebImportStage.CONNECTING)
        classifyUrl(url)?.let { return@withContext WebBookResult.Failure(it) }
        val requestedUrl = canonicalWebUrl(url)!!
        WebFetch.clearSession()

        onStage(WebImportStage.DOWNLOADING)
        val first = when (val page = loadPage(requestedUrl)) {
            is PageResult.Ok -> page
            is PageResult.Failed -> return@withContext WebBookResult.Failure(page.error)
        }
        val bookPage: PageResult.Ok
        if (isBookPageUrl(first.url)) {
            bookPage = first
        } else {
            onStage(WebImportStage.EXTRACTING)
            val bookPageUrl = findBookPageUrl(first.document, first.url)
                ?: return@withContext WebBookResult.Failure(
                    ImportError.of(ImportReason.BOOK_FILE_MISSING, "ссылка на страницу книги не найдена"),
                )
            bookPage = if (bookPageUrl == first.url) {
                first
            } else {
                onStage(WebImportStage.DOWNLOADING)
                when (val page = loadPage(bookPageUrl)) {
                    is PageResult.Ok -> page
                    is PageResult.Failed -> return@withContext WebBookResult.Failure(page.error)
                }
            }
        }
        if (isAntibotPage(bookPage.document)) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.ANTIBOT))
        }
        if (isLoginRedirect(bookPage.document, bookPage.url)) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.LOGIN_REQUIRED))
        }
        if (isPaywall(bookPage.document)) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.PAYWALL))
        }

        onStage(WebImportStage.EXTRACTING)
        val fileUrl = findFb2DownloadUrl(bookPage.document, bookPage.url)
            ?: return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FILE_MISSING))

        onStage(WebImportStage.DOWNLOADING)
        val file = when (val fetched = WebFetch.fetch(
            fileUrl,
            allowUrl = { allowsWebRedirect(bookPage.url, it) },
            maxBodyBytes = MAX_BOOK_BYTES,
        )) {
            is WebFetch.FetchResult.Ok -> fetched.response
            is WebFetch.FetchResult.Failed -> return@withContext WebBookResult.Failure(fetched.error)
        }
        val finalUrl = canonicalWebUrl(file.url().toString())
            ?: return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FILE_MISSING))
        if (!allowsWebRedirect(bookPage.url, finalUrl)) {
            return@withContext WebBookResult.Failure(
                ImportError.of(ImportReason.BOOK_FILE_MISSING, "файл отдаёт другой сайт"),
            )
        }
        val contentType = file.contentType().orEmpty().lowercase(Locale.ROOT)
        if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FORMAT, "сайт ответил страницей"))
        }
        val bytes = try {
            file.bodyAsBytes()
        } catch (error: Exception) {
            return@withContext WebBookResult.Failure(
                if (WebFetch.isTooLarge(error)) ImportError.of(ImportReason.TOO_LARGE) else ImportError.of(ImportReason.NETWORK),
            )
        }
        if (bytes.size > MAX_BOOK_BYTES) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.TOO_LARGE))
        }
        if (bytes.size < MIN_BOOK_BYTES || !looksLikeFb2(bytes)) {
            return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FORMAT))
        }

        onStage(WebImportStage.PARSING)
        val page = bookPageMetadata(bookPage.document)
        val fallbackTitle = page.title.ifBlank { "Книга с сайта" }
        val temporary = File.createTempFile("web-book-", if (isZip(bytes)) ".fb2.zip" else ".fb2")
        try {
            temporary.writeBytes(bytes)
            val parsed = try {
                readFb2(temporary, fallbackTitle, images)
            } catch (error: IOException) {
                return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FORMAT, error.message))
            } catch (error: RuntimeException) {
                return@withContext WebBookResult.Failure(ImportError.of(ImportReason.BOOK_FORMAT, error.message))
            }
            val cover = runCatching { readFb2Cover(temporary) }.getOrNull()
            val warnings = parsed.warnings + identityWarnings(page.title, page.author, parsed)
            WebBookResult.Success(parsed.copy(source = bookPage.url, warnings = warnings), cover)
        } finally {
            temporary.delete()
        }
    }

    /** A fetched page: its canonical URL and parsed document, or the typed reason it is not one. */
    private sealed interface PageResult {
        data class Ok(val url: String, val document: Document) : PageResult
        data class Failed(val error: ImportError) : PageResult
    }

    /** Fetches [url] as an HTML page; a book file or a challenge answer becomes a typed failure. */
    private suspend fun loadPage(url: String): PageResult {
        val response = when (val fetched = WebFetch.fetch(url, allowUrl = { allowsWebRedirect(url, it) })) {
            is WebFetch.FetchResult.Ok -> fetched.response
            is WebFetch.FetchResult.Failed -> return PageResult.Failed(fetched.error)
        }
        val sourceUrl = canonicalWebUrl(response.url().toString())
            ?: return PageResult.Failed(ImportError.of(ImportReason.BAD_URL))
        val contentType = response.contentType().orEmpty().lowercase(Locale.ROOT)
        if (contentType.isNotEmpty() && !contentType.startsWith("text/html") &&
            !contentType.startsWith("application/xhtml+xml")
        ) {
            return PageResult.Failed(ImportError.of(ImportReason.NON_HTML))
        }
        val bytes = response.bodyAsBytes()
        val charset = resolveCharset(response.charset(), bytes)
        val document = Jsoup.parse(String(bytes, charset), sourceUrl)
        if (isAntibotPage(document)) return PageResult.Failed(ImportError.of(ImportReason.ANTIBOT))
        return PageResult.Ok(sourceUrl, document)
    }

    /** The page that describes the book, as the current page names it. */
    internal fun findBookPageUrl(document: Document, sourceUrl: String): String? {
        val bookPage = document.select("a[href]").mapNotNull { element ->
            val href = canonicalWebUrl(element.absUrl("href")) ?: return@mapNotNull null
            href.takeIf { sameSite(sourceUrl, it) && isBookPageUrl(it) }
        }.firstOrNull()
        if (bookPage != null) return bookPage
        // Reader pages keep their book id in `b`; the description page is the same id on /bd/.
        val current = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        val bookId = Regex("(?:^|&)b=(\\d+)").find(current.rawQuery.orEmpty())?.groupValues?.get(1) ?: return null
        val host = current.host ?: return null
        return canonicalWebUrl("https://$host/bd/?b=$bookId")
    }

    /** Whether [url] is a book description page rather than a chapter page. */
    internal fun isBookPageUrl(url: String): Boolean =
        runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("").startsWith("/bd/")

    /**
     * The download link of the FB2 the book page declares. The format is named by the file row's
     * own label; another site's link is not followed.
     */
    internal fun findFb2DownloadUrl(document: Document, bookPageUrl: String): String? {
        for (input in document.select("input[downloadurl]")) {
            val id = input.id()
            val label = (if (id.isNotEmpty()) document.selectFirst("label[for='$id']")?.text() else null)
                ?: input.closest("tr")?.text()
                ?: continue
            if (!labelSaysFb2(label)) continue
            val url = canonicalWebUrl(input.absUrl("downloadurl")) ?: continue
            if (sameSite(bookPageUrl, url)) return url
        }
        for (anchor in document.select("a[href]")) {
            if (!labelSaysFb2(anchor.text())) continue
            val url = canonicalWebUrl(anchor.absUrl("href")) ?: continue
            if (sameSite(bookPageUrl, url)) return url
        }
        return null
    }

    /** «fb2», «FB2.ZIP», «Скачать fb2» name the format; «epub», «rtf» and «pdf» do not. */
    private fun labelSaysFb2(label: String): Boolean {
        val lower = label.lowercase(Locale.ROOT)
        if (lower.contains("epub") || lower.contains("rtf") || lower.contains("pdf") ||
            lower.contains("txt") || lower.contains("doc")
        ) {
            return false
        }
        return Regex("(?<![a-zа-яё0-9])fb2(?:\\.zip)?(?![a-zа-яё0-9])").containsMatchIn(lower)
    }

    /** The title and author the book page shows, when the markup carries them. */
    internal data class PageBook(val title: String, val author: String)

    internal fun bookPageMetadata(document: Document): PageBook {
        val book = document.selectFirst("[itemtype*=Book]") ?: document
        val title = book.select("[itemprop=name]")
            .map { normalizeText(it.text()) }
            .firstOrNull { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.let(::normalizeText).orEmpty()
        val author = (
            book.selectFirst("[itemprop=author] [itemprop=name]")?.attr("content")
                ?: book.selectFirst("[itemprop=author]")?.text()
            )?.let(::normalizeText).orEmpty()
        return PageBook(title, author)
    }

    /** Whether the bytes are an FB2 file or a ZIP that may hold one, judged by their content. */
    internal fun looksLikeFb2(bytes: ByteArray): Boolean {
        if (isZip(bytes)) return true
        val head = bytes.copyOf(minOf(bytes.size, 2_048)).toString(Charsets.UTF_8)
        return head.contains("<FictionBook", ignoreCase = true)
    }

    /** ZIP local-file header: an `.fb2.zip`, never an HTML answer with a book's name. */
    internal fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** A file whose title or author contradicts the page is still imported, but the reader is told. */
    internal fun identityWarnings(pageTitle: String, pageAuthor: String, book: Book): List<String> = buildList {
        if (!sameWords(pageTitle, book.title)) {
            add("В файле другое название: «${book.title}», а на странице книги — «$pageTitle»")
        }
        if (!sameWords(pageAuthor, book.author)) {
            add("В файле другой автор: «${book.author}», а на странице книги — «$pageAuthor»")
        }
    }

    /** One name read in another order or with initials still names the same book or person. */
    private fun sameWords(first: String, second: String): Boolean {
        val a = wordsOf(first)
        val b = wordsOf(second)
        if (a.isEmpty() || b.isEmpty()) return true
        return a == b || a.containsAll(b) || b.containsAll(a)
    }

    private fun wordsOf(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotEmpty)
        .toSet()

    private fun sameSite(first: String, second: String): Boolean = allowsWebRedirect(first, second)
}
