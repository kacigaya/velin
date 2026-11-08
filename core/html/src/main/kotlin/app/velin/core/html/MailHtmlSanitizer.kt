package app.velin.core.html

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

data class SanitizedMailHtml(val html: String, val externalContentPresent: Boolean,
    val externalContentBlocked: Boolean, val inlineImages: Map<String, String>)

/** Mail subset with local presentation styles. Active content and SVG are removed.
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
                    if (attribute.value.isNotBlank() && !attribute.value.startsWith("cid:", true) && !safeDataImage(attribute.value) &&
                        (key != "style" || Regex("url|image-set|\\\\", RegexOption.IGNORE_CASE).containsMatchIn(attribute.value))) {
                        external = true
                        if (!(allowExternalImages && element.normalName() == "img" && key == "src" && safeWebUrl(attribute.value))) blocked = true
                    }
                }
            }
        }
        document.select("script,style,form,iframe,frame,frameset,object,embed,svg,math,template,noscript,audio,video,link,meta,base,input,button,textarea,select").remove()
        for (element in document.body().allElements) {
            if (element.hasAttr("style")) {
                val style = safeStyle(element.attr("style"))
                if (style.isEmpty()) element.removeAttr("style") else element.attr("style", style)
            }
            if (element.normalName() == "img") {
                val src = element.attr("src").trim()
                when {
                    src.startsWith("cid:", true) && src.substring(4).isNotBlank() -> {
                        val url = "https://velin.invalid/inline/${inline.size}"
                        inline[url] = src.substring(4)
                        element.attr("src", url)
                    }
                    safeDataImage(src) -> element.attr("src", src)
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
            .addAttributes(":all", "title", "dir", "lang", "style")
            .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto", "tel")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer")
            .addAttributes("img", "src", "alt").addProtocols("img", "src", "https", "http", "data")
            .addAttributes("td", "colspan", "rowspan").addAttributes("th", "colspan", "rowspan")
        val clean = Cleaner(safelist).clean(document)
        clean.outputSettings().prettyPrint(false)
        return SanitizedMailHtml(clean.body().html(), external, blocked, inline.toMap())
    }
    // Upstream HtmlSanitizer.ts:254-267,314-316. Restrict grammar instead of
    // decoding CSS escapes. No allowed value can contain a URL or custom variable.
    private fun safeStyle(style: String): String = style.split(';').mapNotNull { declaration ->
        val parts = declaration.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val property = parts[0].trim().lowercase()
        val value = parts[1].trim().lowercase()
        if (property !in presentationProperties || value.isEmpty() || value.length > 256 ||
            !Regex("[a-z0-9#%.,() +/!-]+").matches(value) ||
            Regex("url|image|expression|import|var|src|element|attr|behavior").containsMatchIn(value) ||
            Regex("[a-z-]+\\s*\\(").findAll(value).any {
                it.value.substringBefore('(').trim() !in setOf("rgb", "rgba", "hsl", "hsla")
            }) null else "$property:$value"
    }.joinToString(";")
    private val presentationProperties = setOf(
        "color", "background-color", "font", "font-family", "font-size", "font-weight", "font-style",
        "line-height", "letter-spacing", "text-align", "text-decoration", "text-indent", "white-space",
        "word-break", "overflow-wrap", "vertical-align", "border", "border-color", "border-width",
        "border-style", "border-collapse", "border-spacing", "padding", "padding-top", "padding-bottom",
        "padding-left", "padding-right", "margin", "margin-top", "margin-bottom", "margin-left",
        "margin-right", "width", "max-width", "min-width", "height", "max-height", "list-style-type")
    // Upstream permits data images (HtmlSanitizer.ts:637-638). Restrict to raster base64.
    private fun safeDataImage(value: String): Boolean = value.length <= 2 * 1024 * 1024 &&
        Regex("data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/]+={0,2}", RegexOption.IGNORE_CASE).matches(value)
    private fun safeWebUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null && uri.userInfo == null
    } catch (_: Exception) { false }
    private fun safeLink(value: String): Boolean = safeWebUrl(value) || try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("mailto", "tel") && uri.rawSchemeSpecificPart.isNotBlank()
    } catch (_: Exception) { false }
}
