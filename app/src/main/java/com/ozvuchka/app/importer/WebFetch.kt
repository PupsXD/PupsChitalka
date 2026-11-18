package com.ozvuchka.app.importer

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException

/**
 * The network part shared by the chapter and the whole-book import: one browser-like identity,
 * one cookie jar per import session, retries for temporary failures and a typed outcome for
 * every answer. Both imports run one after another, so a cookie set while reading a chapter
 * page carries to the book page of the same session, as a browser would.
 */
internal object WebFetch {
    /** A realistic phone browser: many reader sites grade guests down to a challenge page. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** jsoup applies its timeout to both connecting and reading; retries read with more slack. */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    const val MAX_BODY_BYTES = 8_000_000
    private const val ATTEMPTS = 3

    /** Cookies of one import session: age gates and consent answers carry across its retries. */
    private val sessionCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** Forgets the cookies; every import session starts and ends without borrowed ones. */
    fun clearSession() = sessionCookies.clear()

    /** The end of the fetch loop: the response, or why none arrived. */
    sealed interface FetchResult {
        data class Ok(val response: org.jsoup.Connection.Response) : FetchResult
        data class Failed(val error: ImportError) : FetchResult
    }

    /**
     * One GET with its retries; each attempt reconnects, so the session cookies carry over.
     * [maxBodyBytes] bounds what the response may hold; a bigger body fails instead of being
     * read into memory.
     */
    suspend fun fetch(url: String, maxBodyBytes: Int = MAX_BODY_BYTES): FetchResult {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val site = siteOf(host) ?: host
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
                    .maxBodySize(maxBodyBytes)
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
            } catch (error: Exception) {
                // jsoup refuses an oversized body with its own runtime exception.
                lastError = if (isTooLarge(error)) {
                    ImportError.of(ImportReason.TOO_LARGE)
                } else {
                    importErrorOf(error)
                }
                if (lastError.reason == ImportReason.TOO_LARGE) return FetchResult.Failed(lastError)
            }
            attempt += 1
        }
        return FetchResult.Failed(lastError)
    }

    /** Whether the exception is jsoup's refusal to read a body larger than the configured limit. */
    fun isTooLarge(error: Throwable): Boolean =
        error.message?.contains("max content size", ignoreCase = true) == true ||
            error.cause?.message?.contains("max content size", ignoreCase = true) == true ||
            error.message?.contains("слишком велик", ignoreCase = true) == true

    /**
     * The typed failure of an error response. A 403 or 503 that carries a Cloudflare/DDoS-Guard
     * challenge in its body is an ANTIBOT answer, not a plain refusal — real protection answers
     * with an error status, so the body must be read to see it.
     */
    fun statusErrorOf(
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
    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        return url.substring(0, schemeEnd + 3) + url.substring(schemeEnd + 3).substringBefore('/')
    }

    private const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    private const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en;q=0.8"
}
