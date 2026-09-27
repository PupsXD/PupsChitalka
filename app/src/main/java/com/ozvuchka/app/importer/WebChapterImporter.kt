package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

data class WebChapter(
    val title: String,
    val html: String,
    val sourceUrl: String,
    val nextUrl: String?,
)

/** A web chapter as book text: its paragraphs, with the site's own title suffix dropped. */
fun WebChapter.toBookChapter(): Chapter {
    val body = Jsoup.parseBodyFragment(html).body()
    val selected = body.select("p, h2, h3, blockquote, li")
        .map { it.text().trim() }.filter(String::isNotBlank)
    val paragraphs = if (selected.isNotEmpty()) selected else body.wholeText().split(Regex("\\n+"))
        .map(String::trim).filter(String::isNotBlank)
    val chapterTitle = title.substringBefore(" | ").trim().ifBlank { title }
    return Chapter(chapterTitle, paragraphs, sourceUrl, nextUrl)
}

/** Imports only the URL selected by the reader. [nextUrl] is offered for a separate action. */
object WebChapterImporter {
    suspend fun importChapter(url: String): WebChapter = withContext(Dispatchers.IO) {
        val requestedUrl = canonicalHttpUrl(url)
            ?: throw IllegalArgumentException("Нужна ссылка на страницу с протоколом http или https")

        val response = Jsoup.connect(requestedUrl)
            .userAgent("Mozilla/5.0 (Linux; Android) Ozvuchka/1.0")
            .timeout(15_000)
            .maxBodySize(5_000_000)
            .followRedirects(true)
            .execute()

        val sourceUrl = canonicalHttpUrl(response.url().toString())
            ?: throw IllegalArgumentException("Страница перенаправила на неподдерживаемый адрес")
        val contentType = response.contentType().orEmpty().lowercase(Locale.ROOT)
        if (!contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml+xml")) {
            throw IllegalArgumentException("По ссылке нет HTML-страницы")
        }

        val document = response.parse()
        val article = findArticle(document)
            ?: throw IllegalArgumentException("Не удалось найти текст главы на странице")
        val content = article.clone()
        content.select(
            "script, style, nav, aside, header, footer, form, iframe, button, noscript, " +
                "[hidden], [aria-hidden=true], [role=navigation], " +
                ".ad, .ads, .adsbygoogle, .advertisement, .banner, .share, .social, " +
                ".comments, #comments, [data-ad]",
        ).remove()

        if (content.text().length < 80) {
            throw IllegalArgumentException("На странице слишком мало текста для главы")
        }

        val safeHtml = Jsoup.clean(content.html(), sourceUrl, Safelist.relaxed())
        if (Jsoup.parseBodyFragment(safeHtml).body().text().length < 80) {
            throw IllegalArgumentException("Не удалось извлечь текст главы")
        }

        WebChapter(
            title = extractTitle(document, article),
            html = safeHtml,
            sourceUrl = sourceUrl,
            nextUrl = extractNextUrl(document, sourceUrl),
        )
    }

    private fun findArticle(document: Document): Element? =
        document.selectFirst("#arrticle")
            ?: document.selectFirst("#article")
            ?: document.selectFirst("#article-content")
            ?: document.select("article").maxByOrNull { it.text().length }
                ?.takeIf { it.text().length >= 80 }
            ?: document.select("main").maxByOrNull { it.text().length }

    private fun extractTitle(document: Document, article: Element): String {
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

    private fun extractNextUrl(document: Document, sourceUrl: String): String? {
        val href = document.selectFirst("a#next[href]")?.absUrl("href")
            ?: document.selectFirst("a[rel=next][href]")?.absUrl("href")
            ?: return null
        val nextUrl = canonicalHttpUrl(href) ?: return null
        val sourceHost = URI(sourceUrl).host
        val nextHost = URI(nextUrl).host
        return nextUrl.takeIf { nextHost.equals(sourceHost, ignoreCase = true) && it != sourceUrl }
    }

    private fun canonicalHttpUrl(value: String): String? {
        val uri = try {
            URI(value.trim()).normalize()
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if ((scheme != "http" && scheme != "https") || host.isBlank() || uri.rawUserInfo != null) {
            return null
        }
        val port = uri.port
        if (port > 65535) return null
        val authorityHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val authorityPort = if (port == -1 || (scheme == "http" && port == 80) ||
            (scheme == "https" && port == 443)
        ) "" else ":$port"
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authorityHost$authorityPort$path$query"
    }
}
