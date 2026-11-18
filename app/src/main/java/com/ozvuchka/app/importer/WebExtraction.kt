package com.ozvuchka.app.importer

import java.net.IDN
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.Locale
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** The least text a found container must hold to count as a chapter. */
internal const val MIN_CHAPTER_CHARS = 80

/** A chapter of one solid paragraph is still a chapter; a shorter single block is a caption. */
private const val MIN_SINGLE_BLOCK_CHARS = 300

/**
 * Text containers of the known reader sites. The selectors are candidates, not answers: every
 * candidate goes through the same quality gate below, so a site redesign only costs quality,
 * not the import.
 */
private val hostRules: List<Pair<String, List<String>>> = listOf(
    "author.today" to listOf("div#content", "div.chapter-text", "div.text"),
    "ranobehub.org" to listOf("div.marked-content", "#chapter-text", "div.text"),
    "litnet.com" to listOf("div.chapter-text", "div#chapterContent", "div.js-read-page-content"),
    "litnet.ru" to listOf("div.chapter-text", "div#chapterContent", "div.js-read-page-content"),
    "litmir.club" to listOf("div.BookText div.page_text", "div.page_text"),
    "litmir.me" to listOf("div.BookText div.page_text", "div.page_text"),
    "litmir.net" to listOf("div.BookText div.page_text", "div.page_text"),
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
 * Finds the chapter text on a parsed page by ranking every realistic candidate — the site's own
 * containers, schema.org article bodies, article/main wrappers, the density and Readability
 * winners, and the page body — through one quality gate: enough prose, not mostly links, and
 * blocks a reader recognizes as text. The largest narrative wins, then the tightest wrapper that
 * keeps at least [DESCENT_PERCENT] of it, so a page shell around the chapter is dropped without
 * cutting its short replies, verse or footnotes. null leaves the typed EMPTY_CONTENT failure.
 */
internal fun findContentElement(document: Document, host: String): Element? {
    val candidates = LinkedHashSet<Element>()
    for (selector in hostRuleSelectors(host)) candidates.addAll(document.select(selector))
    for (selector in articleSelectors) candidates.addAll(document.select(selector))
    densityCandidate(document)?.let { candidates.add(it) }
    readabilityCandidate(document)?.let { candidates.add(it) }
    document.body()?.let { candidates.add(it) }

    val scored = IdentityHashMap<Element, ChapterCandidate>()
    fun candidateOf(element: Element): ChapterCandidate? {
        scored[element]?.let { return it }
        val fresh = scoreChapter(element) ?: return null
        scored[element] = fresh
        return fresh
    }
    val best = candidates.mapNotNull(::candidateOf).maxWithOrNull(chapterOrder) ?: return null
    return descendToChapter(best, ::candidateOf).element
}

/** A candidate container with the numbers the quality gate and the ranking are built on. */
private class ChapterCandidate(
    val element: Element,
    /** Characters of the blocks a chapter is made of: paragraphs, verse lines, list items, rows. */
    val narrative: Int,
    /** Characters of the container that did not become a block: stray labels, a shell. */
    val leftover: Int,
)

/** More narrative wins; on a tie the container with less leftover shell. */
private val chapterOrder = compareBy<ChapterCandidate>({ it.narrative }, { -it.leftover })

/** How much of the parent's narrative a child must keep to be a tighter wrapper of the same chapter. */
private const val DESCENT_PERCENT = 90

/** Containers a chapter may be wrapped in; a paragraph is never a candidate on its own. */
private val containerTags = setOf(
    "body", "div", "section", "article", "main", "td", "th", "table", "tbody", "thead", "tr", "ul", "ol",
)

/** The schema.org and editorial wrappers that name the text of an article. */
private val articleSelectors = listOf(
    "#arrticle", "#article", "#article-content", "[itemprop=articleBody]", "article", "main",
)

/**
 * The quality gate of one container: it must hold enough prose after the furniture is cleaned,
 * keep most of its text out of links, and consist of blocks of real text rather than one label.
 */
private fun scoreChapter(element: Element): ChapterCandidate? {
    if (element.tagName().lowercase(Locale.ROOT) !in containerTags) return null
    val cleaned = cleanedContent(element)
    val text = cleaned.text()
    if (text.length < MIN_CHAPTER_CHARS) return null
    val linkText = cleaned.select("a[href]").text().length
    val net = text.length - linkText
    // Half the text in links means navigation or a teaser wall, not a chapter.
    if (net < MIN_CHAPTER_CHARS || net * 2 < text.length) return null
    val blocks = htmlBlocks(cleaned)
    val blockChars = blocks.sumOf { it.first.length }
    val narrative = blockChars - linkText
    if (narrative < MIN_CHAPTER_CHARS) return null
    if (blocks.size == 1 && narrative < MIN_SINGLE_BLOCK_CHARS) return null
    val leftover = text.length - blockChars
    return ChapterCandidate(element, narrative, leftover.coerceAtLeast(0))
}

/**
 * The tightest wrapper of the same narrative: while one child keeps at least [DESCENT_PERCENT]
 * of its parent's text, that child is the chapter and the parent is the page shell around it.
 */
private fun descendToChapter(
    start: ChapterCandidate,
    candidateOf: (Element) -> ChapterCandidate?,
): ChapterCandidate {
    var best = start
    while (true) {
        val child = best.element.children()
            .mapNotNull(candidateOf)
            .maxWithOrNull(chapterOrder) ?: break
        if (child.narrative * 100 < best.narrative * DESCENT_PERCENT) break
        best = child
    }
    return best
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

/** Footnotes a reader site keeps beside the chapter container rather than inside it. */
private const val NOTES_SELECTOR = "div.page_notes"

/**
 * The chapter as it goes into the book: the found container cleaned of the page furniture,
 * plus the footnotes the site keeps outside it, under their own «Примечания» line. The page
 * itself stays untouched for the title and next-link lookups.
 */
internal fun chapterContent(document: Document, article: Element): Element {
    val content = cleanedContent(article)
    // Notes already inside the container came with the chapter; only a copy beside it is added.
    if (content.selectFirst(NOTES_SELECTOR) != null) return content
    val notes = document.selectFirst(NOTES_SELECTOR)?.clone() ?: return content
    // The back link is the site's navigation, not part of the note.
    notes.select("a.fb2-note-back").remove()
    if (notes.text().isBlank()) return content
    content.appendElement("p").text("Примечания")
    content.appendChild(notes)
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

/** Redirects stay on the originating HTTPS site; Litmir's three declared domains are one site. */
internal fun allowsWebRedirect(origin: String, target: String): Boolean {
    val from = runCatching { java.net.URI(canonicalWebUrl(origin) ?: return false) }.getOrNull() ?: return false
    val to = runCatching { java.net.URI(canonicalWebUrl(target) ?: return false) }.getOrNull() ?: return false
    if (from.scheme != "https" || to.scheme != "https") return false
    fun family(host: String): String = when (host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")) {
        "litmir.club", "litmir.me", "litmir.net" -> "litmir"
        else -> siteOf(host) ?: host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
    }
    if (family(from.host ?: return false) != family(to.host ?: return false)) return false

    // A reader/book-page redirect must keep its explicit book id when both URLs are book pages.
    fun bookPage(uri: java.net.URI) = uri.path == "/br/" || uri.path == "/bd/"
    if (bookPage(from) && bookPage(to)) {
        fun bookId(uri: java.net.URI): String? = Regex("(?:^|&)b=([^&]+)")
            .find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
        val originalId = bookId(from)
        if (originalId != null && bookId(to) != originalId) return false
    }
    return true
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

    var explicitWrongBook = false
    fun accept(element: Element?): String? {
        val href = element?.absUrl("href").orEmpty()
        if (href.isEmpty()) return null
        val nextUrl = canonicalWebUrl(href) ?: return null
        val nextHost = runCatching { URI(nextUrl).host }.getOrNull() ?: return null
        if (siteOf(nextHost) != siteOf(host) || nextUrl == sourceUrl) return null
        if (!allowsWebRedirect(sourceUrl, nextUrl)) {
            explicitWrongBook = true
            return null
        }
        val label = element!!.text()
        if (notNextText.containsMatchIn(label) || notNextText.containsMatchIn(nextUrl)) return null
        // TOC pagination walks /page/N; a chapter link does not.
        if (isPageTurn(sourceUrl, nextUrl, labelledNext = nextText.containsMatchIn(label))) return null
        return nextUrl
    }

    val byClass = document.select("a#next[href], a[rel=next][href], a[class*=next][href]")
        .filterNot { it.className().lowercase(Locale.ROOT).contains("prev") }
        // A bare number or a pager glyph names a table-of-contents page, not a chapter.
        .filterNot { it.text().trim().matches(numericLabel) }
    for (element in byClass) accept(element)?.let { return it }
    document.selectFirst("link[rel=next][href]")?.let { link ->
        accept(link)?.let { return it }
    }
    for (element in document.select("a[href]")) {
        val label = element.text()
        if (label.length > 60 || notNextText.containsMatchIn(label)) continue
        if (nextText.containsMatchIn(label)) {
            accept(element)?.let { return it }
        }
    }
    return if (explicitWrongBook) null else runCatching { numericIncrementNext(sourceUrl) }.getOrNull()
}

/** «2», «1 / 5», «»» — a pager's label, never a chapter link's. */
private val numericLabel = Regex("[\\d\\s/·.»›-]+")

/** The `/page/N` step at the end of a path, as in `/book/1/page/2`. */
private val trailingPageStep = Regex("(?i)/p(?:age)?/(\\d+)$")

/** The value of a `page`/`p` query parameter, as in `?page=3`. */
private fun pageNumber(query: String?): String? =
    query?.let { Regex("(?:^|&)(?:page|p)=(\\d+)").find(it)?.groupValues?.get(1) }

/**
 * True when the link is a table-of-contents pager rather than a chapter: it extends the current
 * path with a `/page/N` step, walks the same `/page/N` list one page on, or (for links without
 * a real "next" label) merely moves a `?page=` value on the same path.
 */
internal fun isPageTurn(sourceUrl: String, nextUrl: String, labelledNext: Boolean = false): Boolean {
    val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
    val next = runCatching { URI(nextUrl) }.getOrNull() ?: return false
    val sourcePath = source.rawPath.orEmpty().trimEnd('/')
    val nextPath = next.rawPath.orEmpty().trimEnd('/')
    if (nextPath != sourcePath) {
        // /book/1 -> /book/1/page/2: one more page of the same list.
        if (nextPath.startsWith("$sourcePath/")) {
            return trailingPageStep.matches(nextPath.substring(sourcePath.length))
        }
        // /page/1 -> /page/2 of the same list.
        val sourceStep = trailingPageStep.find(sourcePath) ?: return false
        val nextStep = trailingPageStep.find(nextPath) ?: return false
        return sourcePath.substringBeforeLast('/') == nextPath.substringBeforeLast('/')
    }
    // Same path: only a ?page= move marks a pager, and a link that really says "next"
    // on the same URL is left to the other checks.
    if (labelledNext) return false
    val nextPage = pageNumber(next.rawQuery)?.toIntOrNull() ?: return false
    val sourcePage = pageNumber(source.rawQuery)?.toIntOrNull()
    return nextPage != sourcePage
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
            val value = parameter.groupValues[2].toLongOrNull() ?: return null
            val bumped = bumpedNumber(value) ?: return null
            return canonicalWebUrl(sourceUrl.substringBefore('?') + "?" + query.replaceRange(parameter.groups[2]!!.range, bumped))
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

/** The number one up, capped so the last representable value stops guessing instead of wrapping. */
private fun bumpedNumber(value: Long): String? = if (value == Long.MAX_VALUE) null else (value + 1).toString()

/** The segment of a URL path with its chapter number one up, null when it has none. */
private fun String.bumpedChapter(): String? {
    Regex("^\\d+$").matchEntire(this)?.let {
        // A >2^31 segment like /post/1712345678 must never throw NumberFormatException.
        return bumpedNumber(it.value.toLongOrNull() ?: return null)
    }
    Regex("(?i)^(chapter|glava|ch)[-_.]?(\\d+)$").matchEntire(this)?.let { match ->
        val digits = match.groups[2]!!.value.toLongOrNull() ?: return null
        return bumpedNumber(digits)?.let { replaceRange(match.groups[2]!!.range, it) }
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
internal fun isPaywall(document: Document): Boolean = isPaywallText(document.body()?.text().orEmpty())

/**
 * The same marker check on a piece of page text: a chapter's own words are longer than a teaser,
 * so a full chapter that mentions a subscription is never taken for a paywall.
 */
internal fun isPaywallText(text: String): Boolean {
    val lower = text.lowercase(Locale.ROOT)
    if (lower.length > 1_500) return false
    return lower.contains("платн") || lower.contains("подписк") || lower.contains("оформите") ||
        lower.contains("продолжение доступно после") || lower.contains("buy chapter") ||
        lower.contains("premium access") || lower.contains("покупк")
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
