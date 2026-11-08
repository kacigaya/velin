package app.velin.core.html
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class MailHtmlSanitizerTest {
    private val sanitizer = MailHtmlSanitizer()
    @Test fun removesActiveContentAndCss() {
        val result = sanitizer.sanitize("""<script>alert(1)</script><form>secret<input></form><iframe src="https://track.test"></iframe><svg><a href="javascript:alert(1)">x</a></svg><p style="background:url(https://track.test)" onclick="evil()">safe</p>""")
        assertEquals("<p>safe</p>", result.html)
        assertTrue(result.externalContentBlocked)
    }
    @Test fun blocksRemoteSourcesAndPreservesLinks() {
        val result = sanitizer.sanitize("""<img src="https://track.test/pixel" srcset="https://track.test/2x 2x"><table background="https://track.test/bg"><tr><td>x</td></tr></table><a href="https://example.test">link</a>""")
        assertTrue(result.externalContentPresent)
        assertTrue(result.externalContentBlocked)
        val doc = Jsoup.parseBodyFragment(result.html)
        assertFalse(doc.selectFirst("img")!!.hasAttr("src"))
        assertEquals("https://example.test", doc.selectFirst("a")!!.attr("href"))
        assertFalse(result.html.contains("background"))
    }
    @Test fun rewritesOnlyCidImages() {
        val result = sanitizer.sanitize("""<img src="cid:part-1"><img src="cid:part-2">""")
        assertEquals(mapOf("https://velin.invalid/inline/0" to "part-1", "https://velin.invalid/inline/1" to "part-2"), result.inlineImages)
        assertFalse(result.externalContentPresent)
    }
    @Test fun rejectsUrlBypasses() {
        for (url in listOf("javascript:alert(1)", "jav&#x61;script:alert(1)", "java&#10;script:alert(1)", "data:text/html,evil", "file:///etc/passwd", "//track.test", "/relative", "blob:evil")) {
            val result = sanitizer.sanitize("<a href='$url'>x</a><img src='$url'>")
            val doc = Jsoup.parseBodyFragment(result.html)
            assertFalse(url, doc.selectFirst("a")!!.hasAttr("href"))
            assertFalse(url, doc.selectFirst("img")!!.hasAttr("src"))
        }
    }
    @Test fun externalPermissionAllowsOnlyImageSrc() {
        val result = sanitizer.sanitize("""<img src="https://example.test/a" onerror="evil()" style="background:url(https://track.test)">""", true)
        assertEquals("https://example.test/a", Jsoup.parseBodyFragment(result.html).selectFirst("img")!!.attr("src"))
        assertFalse(result.html.contains("onerror"))
        assertFalse(result.html.contains("style"))
        assertTrue(result.externalContentBlocked)
    }
    @Test fun malformedMarkupCannotRestoreActiveContent() {
        for (html in listOf("<math><mtext><table><mglyph><style><!--</style><img title='--><img src=x onerror=evil()>'>", """<noscript><p title="</noscript><img src=x onerror=evil()>">""", "<IMG SRC=javascript:evil()>", "<iframe srcdoc='<script>evil()</script>'>")) {
            val doc = Jsoup.parseBodyFragment(sanitizer.sanitize(html).html)
            assertTrue(doc.select("script,iframe,math,svg,style,[onerror],[onclick]").isEmpty())
        }
    }
    // Upstream test/tests/misc/HtmlSanitizerTest.ts:18-49,175-351. CSS is removed, not rewritten.
    @Test fun upstreamExternalCssCases() {
        for (css in listOf(
            "background-image: url(&quot;https://example.test/pixel&quot;)",
            "background: url(&#39;https://example.test/pixel&#39;)",
            "background-image: image-set(url(https://example.test/a) 1x, url(https://example.test/b) 2x)",
            "background: linear-gradient(blueviolet, black), url(relative)",
            "border-image-source: url(https://example.test/a)",
            "mask-image: url(https://example.test/a)",
            "shape-outside: url(https://example.test/a)"
        )) {
            val result = sanitizer.sanitize("<p style='$css'>x</p>")
            assertTrue(css, result.externalContentBlocked)
            assertEquals("<p>x</p>", result.html)
        }
        assertFalse(sanitizer.sanitize("<p style='color:red'>x</p>").externalContentPresent)
    }
    @Test fun emptyAndLeadingText() {
        assertEquals("", sanitizer.sanitize("").html)
        assertEquals("hello<blockquote>test</blockquote>", sanitizer.sanitize("hello<blockquote>test</blockquote>").html)
    }
}
