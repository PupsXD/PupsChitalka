package com.ozvuchka.app.conversion

import com.ozvuchka.app.importer.epubCoverHref
import com.ozvuchka.app.importer.fb2Cover
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverParsingTest {
    private fun xml(text: String) = Jsoup.parse(text, "", Parser.xmlParser())

    @Test
    fun epubCoverFromEachConvention() {
        val epub3 = xml(
            """<package><manifest><item id="c" href="images/front.jpg" media-type="image/jpeg" properties="cover-image"/>
               <item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest></package>""",
        )
        assertEquals("images/front.jpg", epubCoverHref(epub3))
        val epub2 = xml(
            """<package><metadata><meta name="cover" content="img1"/></metadata>
               <manifest><item id="img1" href="art.png" media-type="image/png"/></manifest></package>""",
        )
        assertEquals("art.png", epubCoverHref(epub2))
        val named = xml("""<package><manifest><item id="x" href="Cover.jpeg" media-type="image/jpeg"/></manifest></package>""")
        assertEquals("Cover.jpeg", epubCoverHref(named))
        assertNull(epubCoverHref(xml("""<package><manifest><item id="t" href="t.xhtml" media-type="application/xhtml+xml"/></manifest></package>""")))
    }

    @Test
    fun fb2CoverDecodesTheLinkedBinary() {
        val payload = ByteArray(100) { it.toByte() }
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(payload)
        val document = xml(
            """<FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
               <coverpage><image l:href="#cover.jpg"/></coverpage></title-info></description>
               <binary id="cover.jpg" content-type="image/jpeg">$encoded</binary></FictionBook>""",
        )
        assertArrayEquals(payload, fb2Cover(document))
    }
}
