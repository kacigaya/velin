package app.velin.core.html

import org.junit.Assert.*
import org.junit.Test

class DraftHtmlTest {
    @Test fun plainTextMatchesReleaseVectors() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/draft-html.tsv")).bufferedReader().use { it.readLines() }
        val decoder = java.util.Base64.getDecoder()
        for (line in lines) {
            val (plain, html) = line.split('\t', limit = 2).map { decoder.decode(it.removePrefix("b64:")).toString(Charsets.UTF_8) }
            assertEquals(plain, html, DraftHtml.fromPlainText(plain))
        }
    }
    @Test fun deeplyNestedMarkupDoesNotUseTheCallStack() {
        assertEquals("body", DraftHtml.toPlainText("<span>".repeat(15000) + "body" + "</span>".repeat(15000)))
    }
    @Test fun plainEditorSeparatesBlocksAndRemovesActiveText() {
        assertEquals("One\nTwo\n> Quoted\nAfter", DraftHtml.toPlainText("<p>One</p><div>Two</div><blockquote>Quoted</blockquote>After<script>bad()</script>"))
        assertEquals("a\n\n", DraftHtml.toPlainText("a<br><br>"))
        assertEquals("a & b", DraftHtml.toPlainText("<b>a &amp; b</b>"))
    }
}
