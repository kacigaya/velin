package app.velin.core.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

object DraftHtml {
    private val LINK = Regex("(?i)(https?|mailto):[^\\s()<>]+")
    // src/applications/common/api/common/utils/imapImportUtils/PlainTextToHtmlConverter.ts:1
    fun fromPlainText(text: String): String = buildString {
        var previousLevel = 0
        text.split(Regex("\r?\n")).forEachIndexed { index, line ->
            var level = 0
            for (character in line) {
                if (character == '>') level++
                else if (character == ' ') break
                else { level = 0; break }
            }
            if (index > 0 && previousLevel == level) append("<br>")
            repeat((previousLevel - level).coerceAtLeast(0)) { append("</blockquote>") }
            repeat((level - previousLevel).coerceAtLeast(0)) { append("<blockquote>") }
            val content = if (level > 0) line.substring((level + 1).coerceAtMost(line.length)) else line
            append(content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            previousLevel = level
        }
        repeat(previousLevel) { append("</blockquote>") }
    }

    /** Plain editor view. Callers retain the source HTML until the text changes. */
    fun toPlainText(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script,style,template,noscript").remove()
        val text = StringBuilder()
        var quoteLevel = 0
        var finalBlockBreak = false
        fun blockBreak() {
            if (text.isNotEmpty() && text.last() != '\n') {
                text.append('\n')
                finalBlockBreak = true
            }
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> for (character in node.wholeText) {
                        if (quoteLevel > 0 && (text.isEmpty() || text.last() == '\n')) text.append(">".repeat(quoteLevel)).append(' ')
                        text.append(character)
                        finalBlockBreak = false
                    }
                    is Element -> when {
                        node.normalName() == "br" -> { text.append('\n'); finalBlockBreak = false }
                        node.normalName() == "blockquote" -> { blockBreak(); quoteLevel++ }
                        node.isBlock -> blockBreak()
                    }
                }
            }
            override fun tail(node: Node, depth: Int) {
                // Plain text has no links. Keep a safe target after its label, so a quote does not lose it.
                if (node is Element && node.normalName() == "a") {
                    val href = node.attr("href").trim()
                    if (LINK.matches(href) && node.text().trim() != href.substringAfter(':').trimStart('/')
                        && node.text().trim() != href) {
                        text.append(" (").append(href).append(')')
                        finalBlockBreak = false
                    }
                }
                if (node is Element && node.isBlock) {
                    blockBreak()
                    if (node.normalName() == "blockquote") quoteLevel--
                }
            }
        }, body)
        if (finalBlockBreak) text.setLength(text.length - 1)
        return text.toString()
    }
}
