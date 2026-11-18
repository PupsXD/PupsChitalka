package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

data class WebChapter(
    val title: String,
    val html: String,
    val sourceUrl: String,
    val nextUrl: String?,
)

/**
 * A web chapter as book text: its paragraphs, with the site's own title suffix dropped. The
 * paragraphs go through [htmlBlocks], so nested markup (a blockquote inside a blockquote, a
 * list inside a table) is visited once and never duplicated.
 */
fun WebChapter.toBookChapter(): Chapter {
    val body = Jsoup.parseBodyFragment(html).body()
    body.select(junkSelector).remove()
    val blocks = htmlBlocks(body)
    val paragraphs = if (blocks.isNotEmpty()) blocks else body.wholeText()
        .split(Regex("\\n+")).map(String::trim).filter(String::isNotBlank).map { it to null }
    val chapterTitle = title.substringBefore(" | ").trim().ifBlank { title }
    return chapterOf(chapterTitle, paragraphs).copy(sourceUrl = sourceUrl, nextUrl = nextUrl)
}

/**
 * Imports only the URL selected by the reader; [nextUrl] is offered for a separate action.
 * The import reports its [WebImportStage] through [onStage] as it goes and never throws:
 * the outcome is an [ImportResult], whose failures carry a typed [ImportError].
 */
object WebChapterImporter {
    /** Forgets the cookies; every import session starts and ends without borrowed ones. */
    fun clearWebSession() = WebFetch.clearSession()

    suspend fun importChapter(
        url: String,
        onStage: (WebImportStage) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        onStage(WebImportStage.CONNECTING)
        classifyUrl(url)?.let { return@withContext ImportResult.Failure(it) }
        val requestedUrl = canonicalWebUrl(url)!!
        // Cookies live for one import session only: each call starts clean, so a stale or
        // poisoned cookie from an earlier attempt cannot answer for this one.
        clearWebSession()
        onStage(WebImportStage.DOWNLOADING)
        val response = when (val fetched = WebFetch.fetch(requestedUrl, allowUrl = { allowsWebRedirect(requestedUrl, it) })) {
            is WebFetch.FetchResult.Ok -> fetched.response
            is WebFetch.FetchResult.Failed -> return@withContext ImportResult.Failure(fetched.error)
        }
        val sourceUrl = canonicalWebUrl(response.url().toString())
            ?: return@withContext ImportResult.Failure(ImportError.of(ImportReason.BAD_URL))

        val contentType = response.contentType().orEmpty().lowercase(Locale.ROOT)
        if (allowedTypes.none(contentType::startsWith)) {
            return@withContext ImportResult.Failure(ImportError.of(ImportReason.NON_HTML))
        }

        val bytes = response.bodyAsBytes()
        val charset = resolveCharset(response.charset(), bytes)
        val document = Jsoup.parse(String(bytes, charset), sourceUrl)
        if (isAntibotPage(document)) {
            return@withContext ImportResult.Failure(ImportError.of(ImportReason.ANTIBOT))
        }

        structuralEmptyNext(document, sourceUrl)?.let { next ->
            return@withContext ImportResult.StructuralEmpty(sourceUrl, next)
        }

        onStage(WebImportStage.EXTRACTING)
        val host = runCatching { java.net.URI(sourceUrl).host }.getOrNull().orEmpty()
        val article = findContentElement(document, host)
            ?: return@withContext ImportResult.Failure(pageFailure(document, sourceUrl))
        val content = chapterContent(document, article)
        if (content.text().length < MIN_CHAPTER_CHARS) {
            return@withContext ImportResult.Failure(pageFailure(document, sourceUrl))
        }
        // A page short enough to be a teaser that passed the length bar is not a chapter:
        // «продолжение доступно после подписки» must not become book text.
        if (isPaywallText(content.text())) {
            return@withContext ImportResult.Failure(ImportError.of(ImportReason.PAYWALL))
        }

        val safeHtml = Jsoup.clean(content.html(), sourceUrl, Safelist.relaxed())
        if (Jsoup.parseBodyFragment(safeHtml).body().text().length < MIN_CHAPTER_CHARS) {
            return@withContext ImportResult.Failure(ImportError.of(ImportReason.EMPTY_CONTENT))
        }

        ImportResult.Success(
            WebChapter(
                title = extractTitle(document, article),
                html = safeHtml,
                sourceUrl = sourceUrl,
                nextUrl = extractNextUrl(document, sourceUrl),
            ),
        )
    }

    /** Why a page did not give chapter text: the guarded pages have their own typed failure. */
    private fun pageFailure(document: Document, sourceUrl: String): ImportError = when {
        isLoginRedirect(document, sourceUrl) -> ImportError.of(ImportReason.LOGIN_REQUIRED)
        isPaywall(document) -> ImportError.of(ImportReason.PAYWALL)
        else -> ImportError.of(ImportReason.EMPTY_CONTENT)
    }

    /**
     * Standard Ebooks announces section dividers as /text/part-N, titles them "Part II: ...",
     * and links the following chapter with rel=next. These pages contain only a divider title,
     * so the normal chapter quality guard correctly rejects them; this narrow signal lets the
     * next-chapter flow skip only that declared structural page.
     */
    internal fun structuralEmptyNext(document: Document, sourceUrl: String): String? {
        val uri = runCatching { java.net.URI(sourceUrl) }.getOrNull() ?: return null
        if (!uri.host.equals("standardebooks.org", ignoreCase = true)) return null
        val sectionPrefix = Regex("^(/ebooks/[^/]+/[^/]+/text/)part-[0-9]+$")
            .matchEntire(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        if (!Regex("(?i)^.+ - Part [ivxlcdm0-9]+: .+$").matches(document.title().trim())) return null
        val text = document.body()?.let(::cleanedContent)?.text().orEmpty()
        if (text.length >= MIN_CHAPTER_CHARS) return null
        if (isLoginRedirect(document, sourceUrl) || isPaywall(document) || isPaywallText(text)) return null
        val next = extractNextUrl(document, sourceUrl) ?: return null
        val nextPath = runCatching { java.net.URI(next).path.orEmpty() }.getOrNull().orEmpty()
        if (!nextPath.startsWith(sectionPrefix) || nextPath == uri.path) return null
        return next
    }

    /** Fetch one chapter through at most three explicitly announced structural dividers. */
    suspend fun importNextChapter(
        url: String,
        importer: suspend (String) -> ImportResult = ::importChapter,
    ): ImportResult {
        var current = url
        val visited = linkedSetOf<String>()
        repeat(MAX_STRUCTURAL_EMPTY_HOPS + 1) { hop ->
            val canonical = canonicalWebUrl(current)
                ?: return ImportResult.Failure(ImportError.of(ImportReason.BAD_URL))
            if (!visited.add(canonical)) {
                return ImportResult.Failure(ImportError.of(ImportReason.EMPTY_CONTENT))
            }
            when (val result = importer(canonical)) {
                is ImportResult.StructuralEmpty -> {
                    if (hop == MAX_STRUCTURAL_EMPTY_HOPS) {
                        return ImportResult.Failure(ImportError.of(ImportReason.EMPTY_CONTENT))
                    }
                    current = result.nextUrl
                }
                else -> return result
            }
        }
        return ImportResult.Failure(ImportError.of(ImportReason.EMPTY_CONTENT))
    }

    private const val MAX_STRUCTURAL_EMPTY_HOPS = 3

    /** The content types a chapter page may come as. */
    private val allowedTypes = listOf(
        "text/html", "application/xhtml+xml", "application/xml", "text/xml",
    )

    /** The typed failure of an error response; the shared fetch does the work. */
    internal fun statusErrorOf(
        statusCode: Int,
        statusMessage: String?,
        url: String,
        charsetHeader: String?,
        body: ByteArray,
    ): ImportError = WebFetch.statusErrorOf(statusCode, statusMessage, url, charsetHeader, body)

    internal fun extractTitle(document: Document, article: org.jsoup.nodes.Element): String {
        val heading = article.closest(".story")?.selectFirst("h1")
            ?: article.selectFirst("h1")
            ?: document.selectFirst("h1[itemprop=name]")
            ?: document.selectFirst("h1")
        val headingText = heading?.clone()?.apply {
            select(".category, [hidden], [aria-hidden=true]").remove()
        }?.text()?.trim().orEmpty()
        val bookTitle = heading?.selectFirst(".category a[rel=up], .category a")
            ?.text()?.trim().orEmpty()
        if (headingText.isNotBlank() && bookTitle.isNotBlank() && headingText != bookTitle) {
            return "$headingText | $bookTitle"
        }
        return headingText.ifBlank {
            breadcrumbTitle(document)
        }.ifBlank {
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        }.ifBlank {
            document.title().trim()
        }.ifBlank {
            "Глава"
        }
    }

    /**
     * The title from the page's schema.org breadcrumbs: the last two crumbs name the chapter
     * and the book, while the first repeats the site's own name and is dropped. Reader sites
     * put the whole «Читать ... - Страница N - библиотека» line into og:title, so the crumbs
     * come before it. A page without crumbs gives an empty string and falls through.
     */
    private fun breadcrumbTitle(document: Document): String {
        val siteName = document.selectFirst("meta[property=og:site_name]")
            ?.attr("content")?.let(::normalizeText).orEmpty()
        val crumbs = document.select("[itemtype*=BreadcrumbList] [itemprop=name]")
            .map { normalizeText(it.text()) }
            .filter { it.isNotEmpty() && !it.equals(siteName, ignoreCase = true) }
        return when {
            crumbs.size >= 2 -> "${crumbs.last()} | ${crumbs[crumbs.size - 2]}"
            crumbs.size == 1 -> crumbs.single()
            else -> ""
        }
    }
}
