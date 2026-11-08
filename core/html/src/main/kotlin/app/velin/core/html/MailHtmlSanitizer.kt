package app.velin.core.html

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

data class SanitizedMailHtml(val html: String, val externalContentPresent: Boolean,
    val externalContentBlocked: Boolean, val inlineImages: Map<String, String>)

/** Conservative mail subset. CSS and SVG are removed entirely.
 * Upstream src/applications/common/misc/HtmlSanitizer.ts:26-99 blocks external loads and style tags.
 * The caller must intercept inline URLs and serve only validated image attachment bytes. */
class MailHtmlSanitizer {
    fun sanitize(html: String, allowExternalImages: Boolean = false): SanitizedMailHtml {
        val document = Jsoup.parseBodyFragment(html)
        var external = false
        var blocked = false
        val inline = linkedMapOf<String, String>()
        for (element in document.body().allElements) {
            for (attribute in element.attributes().asList()) {
                val key = attribute.key.lowercase()
                if (key in setOf("src", "srcset", "poster", "background", "xlink:href", "draft-src", "draft-srcset", "draft-href", "draft-xlink:href") || key == "style") {
                    if (attribute.value.isNotBlank() && !attribute.value.startsWith("cid:", true) &&
                        (key != "style" || Regex("url|image-set|\\\\", RegexOption.IGNORE_CASE).containsMatchIn(attribute.value))) {
                        external = true
                        if (!(allowExternalImages && element.normalName() == "img" && key == "src" && safeWebUrl(attribute.value))) blocked = true
                    }
                }
            }
        }
        document.select("script,style,form,iframe,frame,frameset,object,embed,svg,math,template,noscript,audio,video,link,meta,base,input,button,textarea,select").remove()
        for (element in document.body().allElements) {
            if (element.normalName() == "img") {
                val src = element.attr("src").trim()
                when {
                    src.startsWith("cid:", true) && src.substring(4).isNotBlank() -> {
                        val url = "https://velin.invalid/inline/${inline.size}"
                        inline[url] = src.substring(4)
                        element.attr("src", url)
                    }
                    allowExternalImages && safeWebUrl(src) -> element.attr("src", src)
                    else -> element.removeAttr("src")
                }
            }
            if (element.hasAttr("href")) {
                val href = element.attr("href").trim()
                if (element.normalName() != "a" || !safeLink(href)) element.removeAttr("href")
                else element.attr("href", href)
            }
        }
        val safelist = Safelist.none()
            .addTags("a", "abbr", "b", "blockquote", "br", "caption", "code", "dd", "del", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tfoot", "tr", "u", "ul")
            .addAttributes(":all", "title", "dir", "lang")
            .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto", "tel")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .addAttributes("img", "src", "alt").addProtocols("img", "src", "https", "http")
            .addAttributes("td", "colspan", "rowspan").addAttributes("th", "colspan", "rowspan")
        val clean = Cleaner(safelist).clean(document)
        clean.outputSettings().prettyPrint(false)
        return SanitizedMailHtml(clean.body().html(), external, blocked, inline.toMap())
    }
    private fun safeWebUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null && uri.userInfo == null
    } catch (_: Exception) { false }
    private fun safeLink(value: String): Boolean = safeWebUrl(value) || try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("mailto", "tel") && uri.rawSchemeSpecificPart.isNotBlank()
    } catch (_: Exception) { false }
}
