package com.ozvuchka.app.importer

import java.net.IDN
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** The least text a found container must hold to count as a chapter. */
internal const val MIN_CHAPTER_CHARS = 80

/**
 * Text containers of the known reader sites, in order of trust. The selectors are candidates:
 * each is checked for enough text, and a miss falls through to the generic chain below, so a
 * site redesign only costs quality, not the import.
 */
private val hostRules: List<Pair<String, List<String>>> = listOf(
    "author.today" to listOf("div#content", "div.chapter-text", "div.text"),
    "ranobehub.org" to listOf("div.marked-content", "#chapter-text", "div.text"),
    "litnet.com" to listOf("div.chapter-text", "div#chapterContent", "div.js-read-page-content"),
    "litnet.ru" to listOf("div.chapter-text", "div#chapterContent", "div.js-read-page-content"),
    "ficbook.net" to listOf("div#chapter_container", "div.ch_text", "div.chapter-text"),
    "jaomix.ru" to listOf("div.entry-content", "div.chapter-content", "article"),
    "royalroad.com" to listOf("div.chapter-content", "div.chapter-inner"),
)

/** The junk every found container is cleaned of before the chapter is built. */
internal val junkSelector =
    "script, style, nav, aside, header, footer, form, iframe, button, noscript, " +
        "[hidden], [aria-hidden=true], [role=navigation], [role=banner], " +
        ".ad, .ads, .adsbygoogle, .advertisement, .banner, .share, .social, .socials, " +
        ".comments, #comments, [data-ad], .pagination, .navigation, .chapter-navigation, " +
        ".vote, .rating, .controls, .related"

/**
 * Finds the chapter text on a parsed page: the site's own container when the host is known,
 * then the usual article wrappers, then containers scored by text against link text, then
 * Readability-style scoring of the parents of long paragraphs, and finally the page body
 * without its furniture. null leaves the typed EMPTY_CONTENT failure to report.
 */
internal fun findContentElement(document: Document, host: String): Element? {
    fun big(element: Element?): Element? = element?.takeIf { it.text().length >= MIN_CHAPTER_CHARS }
    for (selector in hostRuleSelectors(host)) {
        big(document.selectFirst(selector))?.let { return it }
    }
    big(document.selectFirst("#arrticle"))?.let { return it }
    big(document.selectFirst("#article"))?.let { return it }
    big(document.selectFirst("#article-content"))?.let { return it }
    big(document.select("article").maxByOrNull { it.text().length })?.let { return it }
    big(document.select("main").maxByOrNull { it.text().length })?.let { return it }
    return densityCandidate(document)
        ?: readabilityCandidate(document)
        ?: big(document.body())
}

/** Container selectors of the site's rule table for this host, empty when unknown. */
internal fun hostRuleSelectors(host: String): List<String> {
    val site = siteOf(host) ?: return emptyList()
    return hostRules.firstOrNull { (rule, _) -> site == rule || host.endsWith(".$rule") }?.second.orEmpty()
}

/**
 * The container with the most real text: everything minus what sits inside links, so a page
 * that is mostly a table of contents or a wall of teasers is not picked.
 */
private fun densityCandidate(document: Document): Element? {
    var best: Element? = null
    var bestScore = 0.0
    for (element in document.select("div, section, article, td, main")) {
        val text = element.text()
        if (text.length < 200) continue
        val linkText = element.select("a[href]").text().length
        val net = text.length - linkText
        // Half the text in links means navigation or a teaser wall, not a chapter.
        if (net < text.length * 0.5 || net < MIN_CHAPTER_CHARS) continue
        if (element.select("p").size < 3) continue
        if (net > bestScore) {
            best = element
            bestScore = net.toDouble()
        }
    }
    return best
}

/**
 * The classic Readability scoring: long paragraphs earn their parents, with commas as a sign
 * of real prose and the weight halved for each level up, so the closest tight wrapper wins.
 */
private fun readabilityCandidate(document: Document): Element? {
    val scores = HashMap<Element, Int>()
    for (paragraph in document.select("p")) {
        val text = paragraph.text()
        if (text.length < 25) continue
        var weight = 1 + text.count { it == ',' }
        var parent: Element? = paragraph.parent()
        while (parent != null && !parent.tagName().equals("body", ignoreCase = true)) {
            scores.merge(parent, weight) { accumulated, earned -> accumulated + earned }
            weight /= 2
            parent = parent.parent()
        }
    }
    return scores.entries
        .filter { it.key.text().length >= MIN_CHAPTER_CHARS }
        .maxByOrNull { it.value }
        ?.key
}

/** Cleans a found container of the page furniture; the clone keeps the document intact. */
internal fun cleanedContent(article: Element): Element {
    val content = article.clone()
    content.select(junkSelector).remove()
    return content
}

// ---------- The next chapter link ----------

/** Suffixes that need three labels for a site: co.uk and friends. */
private val multiPartSuffixes = setOf(
    "co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "net.au", "org.au", "com.br", "com.tr",
    "co.jp", "co.nz", "co.za", "com.ua", "co.in", "com.mx", "com.ar", "com.cn", "com.tw",
)

/** The site of a host: its eTLD+1, so www. and m. mirrors count as the same site. */
internal fun siteOf(host: String): String? {
    val labels = host.lowercase(Locale.ROOT).trim('.').split('.').filter(String::isNotEmpty)
    if (labels.size < 2) return null
    if (labels.size == 2) return labels.joinToString(".")
    val lastTwo = labels.takeLast(2).joinToString(".")
    val lastThree = labels.takeLast(3).joinToString(".")
    return if (lastTwo in multiPartSuffixes) lastThree else lastTwo
}

/** Words that mark a table-of-contents or other non-chapter link. */
private val notNextText = Regex(
    "(?iu)(содержан|оглавлен|список глав|все главы|назад|на главную|table of contents|contents|toc)",
)
private val nextText = Regex("(?iu)(следующ|next|дальше|далее|вперёд|вперед)")

/**
 * The link to the next chapter: explicit relations (`a[rel=next]`, `link[rel=next]`), the
 * `a#next` id, classes like `next` / `nav-next`, then anchors whose text means "next", and
 * finally the current URL with its chapter number incremented. Only links on the same site
 * pass, and the current page itself and table-of-contents links are rejected.
 */
internal fun extractNextUrl(document: Document, sourceUrl: String): String? {
    val host = runCatching { canonicalWebUrl(sourceUrl)?.let { URI(it).host } }.getOrNull() ?: return null

    fun accept(element: Element?): String? {
        val href = element?.absUrl("href").orEmpty()
        if (href.isEmpty()) return null
        val nextUrl = canonicalWebUrl(href) ?: return null
        val nextHost = runCatching { URI(nextUrl).host }.getOrNull() ?: return null
        if (siteOf(nextHost) != siteOf(host) || nextUrl == sourceUrl) return null
        val label = element!!.text()
        if (notNextText.containsMatchIn(label) || notNextText.containsMatchIn(nextUrl)) return null
        return nextUrl
    }

    val byClass = document.select("a#next[href], a[rel=next][href], a[class*=next][href]")
        .filterNot { it.className().lowercase(Locale.ROOT).contains("prev") }
    for (element in byClass) accept(element)?.let { return it }
    accept(document.selectFirst("link[rel=next][href]"))?.let { return it }
    for (element in document.select("a[href]")) {
        val label = element.text()
        if (label.length > 60 || notNextText.containsMatchIn(label)) continue
        if (nextText.containsMatchIn(label)) accept(element)?.let { return it }
    }
    return numericIncrementNext(sourceUrl)
}

/**
 * The last resort for sites that name their chapters only with numbers (`/12`, `/chapter-5`,
 * `?page=3`): the current URL with that number one up. Only fires when nothing else matched,
 * so a wrong guess never overrides a real link.
 */
internal fun numericIncrementNext(sourceUrl: String): String? {
    val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
    val query = uri.rawQuery
    if (query != null) {
        val parameter = Regex("(?:^|&)(page|chapter|glava|p)=(\\d+)(?:&|$)").find(query)
        if (parameter != null) {
            val value = parameter.groupValues[2]
            val bumped = query.replaceRange(parameter.groups[2]!!.range, (value.toInt() + 1).toString())
            return canonicalWebUrl(sourceUrl.substringBefore('?') + "?" + bumped)
        }
    }
    val segments = (uri.rawPath ?: return null).split('/').toMutableList()
    for (index in segments.indices.reversed()) {
        val bumped = segments[index].bumpedChapter() ?: continue
        segments[index] = bumped
        val path = segments.joinToString("/")
        val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
        return canonicalWebUrl("${uri.scheme}://${uri.rawAuthority}$path$queryPart")
    }
    return null
}

/** The segment of a URL path with its chapter number one up, null when it has none. */
private fun String.bumpedChapter(): String? {
    Regex("^\\d+$").matchEntire(this)?.let { return (toInt() + 1).toString() }
    Regex("(?i)^(chapter|glava|ch)[-_.]?(\\d+)$").matchEntire(this)?.let { match ->
        val digits = match.groups[2]!!
        return replaceRange(digits.range, (digits.value.toInt() + 1).toString())
    }
    return null
}

// ---------- Charset ----------

/**
 * The charset of the response bytes: the header's word when present, else a `<meta charset>`
 * found in the first 8 KB, else a choice between UTF-8 and the two common legacy runet
 * encodings by counting replacement characters and the shape of the decoded Cyrillic.
 */
internal fun resolveCharset(headerCharset: String?, bytes: ByteArray): Charset {
    headerCharset?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
        runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
    }
    val head = String(bytes, 0, bytes.size.coerceAtMost(8_192), StandardCharsets.ISO_8859_1)
    Regex("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_.:-]+)").find(head)?.groupValues?.get(1)?.let { name ->
        runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
    }
    return guessLegacyCharset(bytes)
}

/** UTF-8 when the bytes decode cleanly, else windows-1251 vs koi8-r by letter case. */
internal fun guessLegacyCharset(bytes: ByteArray): Charset {
    val utf8 = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    if (runCatching { utf8.decode(ByteBuffer.wrap(bytes)) }.isSuccess) return StandardCharsets.UTF_8
    // The single-byte legacy tables always decode. Real Russian text is mostly lowercase; koi8-r
    // bytes read through windows-1251 come out almost all caps, which gives the table away.
    val asWindows = String(bytes, charset("windows-1251"))
    val upper = asWindows.count { it in 'А'..'Я' }
    val lower = asWindows.count { it in 'а'..'я' }
    return if (lower >= upper) charset("windows-1251") else charset("koi8-r")
}

// ---------- Page guards ----------

/** Markers of Cloudflare, DDoS-Guard and friends inside a challenge page. */
private val antibotMarkers = listOf(
    "just a moment", "checking your browser", "attention required", "cf-browser-verification",
    "cf_chl_", "challenge-platform", "cdn-cgi/challenge", "ddos-guard",
)

/** The page is a bot challenge, not a chapter: short, no real content, with a known marker. */
internal fun isAntibotPage(document: Document): Boolean {
    val title = document.title().lowercase(Locale.ROOT)
    val text = document.body()?.text().orEmpty()
    if (text.length > 1_000) return false
    val lower = title + "\n" + text.lowercase(Locale.ROOT)
    return antibotMarkers.any { lower.contains(it) }
}

/** Paths that mean the site bounced us to its login page. */
private val loginPath = Regex(
    "(?i)/(?:login|signin|sign-in|log-in|enter|vhod|auth|register|registration)(?:/|\\?.*)?$",
)

/** The page wants a login: either the URL says so or there is a password form around. */
internal fun isLoginRedirect(document: Document, finalUrl: String): Boolean {
    val path = runCatching { URI(finalUrl).rawPath }.getOrNull().orEmpty()
    if (loginPath.containsMatchIn("/$path")) return true
    val body = document.body()?.text().orEmpty()
    return document.select("input[type=password]").isNotEmpty() && body.length < 2_000
}

/** The chapter exists but sits behind a subscription or a one-off payment. */
internal fun isPaywall(document: Document): Boolean {
    val text = document.body()?.text()?.lowercase(Locale.ROOT).orEmpty()
    if (text.length > 1_500) return false
    return text.contains("платн") || text.contains("подписк") || text.contains("оформите") ||
        text.contains("продолжение доступно после") || text.contains("buy chapter") ||
        text.contains("premium access") || text.contains("покупк")
}

// ---------- Errors ----------

/** The one place a fetch exception becomes a typed failure. */
internal fun importErrorOf(error: Throwable): ImportError = when (error) {
    is HttpStatusException -> when (error.statusCode) {
        401 -> ImportError.of(ImportReason.HTTP_401)
        403 -> ImportError.of(ImportReason.HTTP_403)
        404 -> ImportError.of(ImportReason.HTTP_404)
        else -> ImportError(
            ImportReason.NETWORK,
            "Сайт ответил ошибкой ${error.statusCode}. Попробуйте ещё раз позже",
            retryable = error.statusCode >= 500,
        )
    }
    is UnsupportedMimeTypeException -> ImportError.of(ImportReason.NON_HTML)
    is SocketTimeoutException -> ImportError.of(ImportReason.TIMEOUT)
    is java.io.IOException -> ImportError.of(ImportReason.NETWORK)
    else -> ImportError.of(ImportReason.NETWORK)
}

// ---------- URLs ----------

/** A canonical http(s) URL: lowercase scheme, punycoded host, no fragment, no user info. */
internal fun canonicalWebUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.contains(' ')) return null
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val withoutFragment = trimmed.substring(schemeEnd + 3).substringBefore('#')
    val query = withoutFragment.takeIf { it.contains('?') }?.substringAfter('?')
    val authorityAndPath = withoutFragment.substringBefore('?')
    val slash = authorityAndPath.indexOf('/')
    val authority = if (slash < 0) authorityAndPath else authorityAndPath.substring(0, slash)
    val path = if (slash < 0) "" else authorityAndPath.substring(slash)
    if (authority.isEmpty() || authority.contains('@')) return null
    val host: String
    val port: Int
    if (authority.startsWith("[")) {
        val closing = authority.indexOf(']')
        if (closing < 0) return null
        host = authority.substring(1, closing)
        port = authority.substring(closing + 1).removePrefix(":").toIntOrNull() ?: -1
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon < 0) {
            host = authority
            port = -1
        } else {
            port = authority.substring(colon + 1).toIntOrNull() ?: return null
            host = authority.substring(0, colon)
        }
    }
    val ascii = asciiHost(host)
    if (ascii.isBlank()) return null
    if (port > 65535) return null
    val authorityPort = if (port == -1 || (scheme == "http" && port == 80) ||
        (scheme == "https" && port == 443)
    ) "" else ":$port"
    val hostWithBrackets = if (ascii.contains(':') && !ascii.startsWith('[')) "[$ascii]" else ascii
    val pathPart = path.ifEmpty { "/" }
    // Non-ASCII letters in the path and query go out as UTF-8 percent escapes.
    return scheme + "://" + hostWithBrackets + authorityPort + percentEncodeNonAscii(pathPart) +
        (query?.let { "?" + percentEncodeNonAscii(it) } ?: "")
}

/** A host in punycode, so `автор.today` connects as `xn--...` and every layer stays ASCII. */
internal fun asciiHost(host: String): String {
    val decoded = if (host.contains('%')) {
        runCatching { java.net.URLDecoder.decode(host, Charsets.UTF_8) }.getOrDefault(host)
    } else {
        host
    }
    val lowered = decoded.lowercase(Locale.ROOT)
    if (lowered.none { it.code > 127 }) return lowered
    return runCatching { IDN.toASCII(lowered, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) }
        .getOrDefault(lowered)
}

/** The site's host for showing in the interface: no `www.`, punycode back into letters. */
internal fun siteLabel(url: String): String? {
    val canonical = canonicalWebUrl(url) ?: return null
    val host = runCatching { URI(canonical).host }.getOrNull() ?: return null
    val label = host.removePrefix("www.")
    return if (label.startsWith("xn--")) {
        runCatching { IDN.toUnicode(label) }.getOrDefault(label)
    } else {
        label
    }
}

/** Every character above ASCII as its UTF-8 percent escapes; ASCII stays as it is. */
private fun percentEncodeNonAscii(value: String): String {
    if (value.none { it.code > 127 }) return value
    val builder = StringBuilder(value.length + 16)
    for (char in value) {
        if (char.code <= 127) {
            builder.append(char)
        } else {
            for (byte in char.toString().toByteArray(Charsets.UTF_8)) {
                builder.append('%').append(String.format(Locale.ROOT, "%02X", byte))
            }
        }
    }
    return builder.toString()
}
