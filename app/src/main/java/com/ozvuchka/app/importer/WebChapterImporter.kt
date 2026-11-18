package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
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
    /** A realistic phone browser: many reader sites grade guests down to a challenge page. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    private const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en;q=0.8"

    /** jsoup applies its timeout to both connecting and reading; retries read with more slack. */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BODY_BYTES = 8_000_000
    private const val ATTEMPTS = 3

    /** Cookies of one import session: age gates and consent answers carry across its retries. */
    private val sessionCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** Forgets the cookies; every import session starts and ends without borrowed ones. */
    fun clearWebSession() = sessionCookies.clear()

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
        val response = when (val fetched = fetch(requestedUrl)) {
            is FetchResult.Ok -> fetched.response
            is FetchResult.Failed -> return@withContext ImportResult.Failure(fetched.error)
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

        onStage(WebImportStage.EXTRACTING)
        val host = runCatching { java.net.URI(sourceUrl).host }.getOrNull().orEmpty()
        val article = findContentElement(document, host)
            ?: return@withContext ImportResult.Failure(
                when {
                    isLoginRedirect(document, sourceUrl) -> ImportError.of(ImportReason.LOGIN_REQUIRED)
                    isPaywall(document) -> ImportError.of(ImportReason.PAYWALL)
                    else -> ImportError.of(ImportReason.EMPTY_CONTENT)
                },
            )
        val content = cleanedContent(article)
        if (content.text().length < MIN_CHAPTER_CHARS) {
            return@withContext ImportResult.Failure(
                when {
                    isLoginRedirect(document, sourceUrl) -> ImportError.of(ImportReason.LOGIN_REQUIRED)
                    isPaywall(document) -> ImportError.of(ImportReason.PAYWALL)
                    else -> ImportError.of(ImportReason.EMPTY_CONTENT)
                },
            )
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

    /** The content types a chapter page may come as. */
    private val allowedTypes = listOf(
        "text/html", "application/xhtml+xml", "application/xml", "text/xml",
    )

    /** The end of the fetch loop: the response, or why none arrived. */
    private sealed interface FetchResult {
        data class Ok(val response: org.jsoup.Connection.Response) : FetchResult
        data class Failed(val error: ImportError) : FetchResult
    }

    /** One fetch with its retries; each attempt reconnects, so the session cookies carry over. */
    private suspend fun fetch(url: String): FetchResult {
        val site = siteOf(runCatching { java.net.URI(url).host }.getOrNull().orEmpty())
            ?: runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        var attempt = 0
        var lastError = ImportError.of(ImportReason.NETWORK)
        while (attempt < ATTEMPTS) {
            if (attempt > 0) delay(750L * attempt)
            try {
                val timeout = if (attempt == 0) CONNECT_TIMEOUT_MS else READ_TIMEOUT_MS
                val response = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", ACCEPT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .referrer(originOf(url))
                    .cookies(sessionCookies[site] ?: emptyMap())
                    .timeout(timeout)
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    // Error answers arrive as responses, so a challenge page can be read.
                    .ignoreHttpErrors(true)
                    .execute()
                response.cookies().forEach { (name, value) ->
                    sessionCookies.getOrPut(site) { ConcurrentHashMap() }[name] = value
                }
                if (response.statusCode() >= 400) {
                    val error = statusErrorOf(
                        response.statusCode(),
                        response.statusMessage(),
                        response.url().toString(),
                        response.charset(),
                        response.bodyAsBytes(),
                    )
                    // A refusal or a bot challenge answers the same on every try; only a bare
                    // 5xx (a temporary overload) deserves another attempt.
                    if (response.statusCode() < 500 || error.reason == ImportReason.ANTIBOT) {
                        return FetchResult.Failed(error)
                    }
                    lastError = error
                } else {
                    return FetchResult.Ok(response)
                }
            } catch (error: HttpStatusException) {
                lastError = importErrorOf(error)
                // 401, 403, 404 and friends answer the same on every try; only 5xx deserve one.
                if (error.statusCode < 500) return FetchResult.Failed(lastError)
            } catch (error: UnsupportedMimeTypeException) {
                return FetchResult.Failed(importErrorOf(error))
            } catch (error: SocketTimeoutException) {
                lastError = importErrorOf(error)
            } catch (error: IOException) {
                lastError = importErrorOf(error)
            }
            attempt += 1
        }
        return FetchResult.Failed(lastError)
    }

    /**
     * The typed failure of an error response. A 403 or 503 that carries a Cloudflare/DDoS-Guard
     * challenge in its body is an ANTIBOT answer, not a plain refusal — real protection answers
     * with an error status, so the body must be read to see it.
     */
    internal fun statusErrorOf(
        statusCode: Int,
        statusMessage: String?,
        url: String,
        charsetHeader: String?,
        body: ByteArray,
    ): ImportError {
        if (statusCode == 403 || statusCode == 503) {
            val document = Jsoup.parse(String(body, resolveCharset(charsetHeader, body)), url)
            if (isAntibotPage(document)) return ImportError.of(ImportReason.ANTIBOT)
        }
        return importErrorOf(HttpStatusException(statusMessage ?: "HTTP $statusCode", statusCode, url))
    }

    /** The page's origin as a Referer: scheme and authority, no path. */
    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        return url.substring(0, schemeEnd + 3) + url.substring(schemeEnd + 3).substringBefore('/')
    }

    private fun extractTitle(document: Document, article: org.jsoup.nodes.Element): String {
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
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        }.ifBlank {
            document.title().trim()
        }.ifBlank {
            "Глава"
        }
    }
}
