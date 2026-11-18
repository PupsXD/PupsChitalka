package com.ozvuchka.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redirect policy tests use an in-memory transport stub and never contact a remote host. */
class WebFetchRedirectTest {
    private val allowLitmir: (String) -> Boolean = { allowsWebRedirect("https://litmir.club/br/?b=17", it) }

    @Test
    fun followsRelativeRedirectsAndExpectedLitmirAliases() {
        val requests = mutableListOf<String>()
        val result = WebFetch.requestWithRedirects("https://litmir.club/br/?b=17", allowLitmir, { url ->
            requests += url
            if (requests.size == 1) WebFetch.RedirectHop(302, "/br/?b=17&p=2", "redirect")
            else WebFetch.RedirectHop(200, null, "chapter")
        })
        assertEquals(listOf("https://litmir.club/br/?b=17", "https://litmir.club/br/?b=17&p=2"), requests)
        assertEquals("chapter", (result as WebFetch.RedirectResult.Complete).value)

        var aliasRequests = 0
        val alias = WebFetch.requestWithRedirects("https://litmir.club/br/?b=17", allowLitmir, { url ->
            aliasRequests++
            if (aliasRequests == 1) WebFetch.RedirectHop(301, "https://www.litmir.me/br/?b=17&p=2", url)
            else WebFetch.RedirectHop(200, null, "alias-page")
        })
        assertEquals("alias-page", (alias as WebFetch.RedirectResult.Complete).value)
    }

    @Test
    fun rejectsForeignTargetBeforeTransportReceivesIt() {
        val requests = mutableListOf<String>()
        val result = WebFetch.requestWithRedirects("https://litmir.club/br/?b=17", allowLitmir, { url ->
            requests += url
            WebFetch.RedirectHop(302, "https://example.invalid/", "redirect")
        })
        assertEquals(listOf("https://litmir.club/br/?b=17"), requests)
        assertEquals(ImportReason.BAD_URL, (result as WebFetch.RedirectResult.Rejected).error.reason)
    }

    @Test
    fun rejectsBookIdChangesMalformedLocationsLoopsAndRedirectLimit() {
        fun run(location: String, max: Int = 5) = WebFetch.requestWithRedirects(
            "https://litmir.club/br/?b=17", allowLitmir,
            { url -> WebFetch.RedirectHop(302, location, url) }, max,
        )

        assertTrue(run("/br/?b=18&p=2") is WebFetch.RedirectResult.Rejected)
        assertTrue(run("") is WebFetch.RedirectResult.Rejected)
        assertTrue(run("javascript:alert(1)") is WebFetch.RedirectResult.Rejected)
        assertTrue(run("https://litmir.club/br/?b=17") is WebFetch.RedirectResult.Rejected)
        val requests = mutableListOf<String>()
        val limited = WebFetch.requestWithRedirects("https://litmir.club/br/?b=17", allowLitmir, { url ->
            requests += url
            WebFetch.RedirectHop(302, "/br/?b=17&p=${requests.size + 1}", url)
        }, maxRedirects = 1)
        assertTrue(limited is WebFetch.RedirectResult.Rejected)
        assertEquals(2, requests.size)
    }
}
