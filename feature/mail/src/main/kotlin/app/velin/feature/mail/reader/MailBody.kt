package app.velin.feature.mail.reader

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Renders an already sanitized HTML mail body.
 *
 * Arbitrary HTML and CSS need a WebView. It is locked down as a passive document viewer:
 * - JavaScript, file and content access are off.
 * - A Content-Security-Policy blocks every remote load unless [allowRemoteContent] is true.
 *   Request interception enforces the same rule as a second layer.
 * - Inline (cid:) images use placeholder URLs on [INLINE_IMAGE_HOST]. They are always answered
 *   locally through [loadInlineImage] and never reach the network.
 * - Navigation never happens in place; links go to [onLinkClick].
 * Sanitizing the HTML stays the data layer's job. This view only limits what clean HTML can do.
 */
@Composable
fun MailBody(
    html: String,
    allowRemoteContent: Boolean,
    onLinkClick: (Uri) -> Unit,
    loadInlineImage: InlineImageLoader,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val currentOnLinkClick = rememberUpdatedState(onLinkClick)
    val currentAllowRemote = rememberUpdatedState(allowRemoteContent)
    val currentLoader = rememberUpdatedState(loadInlineImage)
    val document = mailDocument(html, textColor, linkColor, allowRemoteContent)

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                with(settings) {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    loadWithOverviewMode = true
                    useWideViewPort = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        isAlgorithmicDarkeningAllowed = dark
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame && request.hasGesture()) currentOnLinkClick.value(request.url)
                        return true
                    }

                    // Runs on a WebView background thread, so the loader may block.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        val scheme = url.scheme?.lowercase()
                        if (scheme == "https" && url.host == INLINE_IMAGE_HOST) {
                            val image = currentLoader.value.load(url.toString()) ?: return emptyResponse()
                            return WebResourceResponse(image.mimeType, null, ByteArrayInputStream(image.bytes))
                        }
                        val remote = scheme == "http" || scheme == "https"
                        return if (remote && currentAllowRemote.value) null else emptyResponse()
                    }
                }
            }
        },
        // No blockNetworkLoads: it can stop the inline placeholder requests before interception.
        // The CSP and shouldInterceptRequest above keep remote loads blocked.
        update = { webView ->
            if (webView.tag != document) {
                webView.tag = document
                // Null base URL: relative URLs resolve to about:blank, never to a real origin.
                webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

/** Data layer placeholder host for inline images (docs/backend/M3-READ-PATH.md). */
internal const val INLINE_IMAGE_HOST = "velin.invalid"

data class InlineImageData(val bytes: ByteArray, val mimeType: String)

/** Resolves an inline placeholder URL. Called off the main thread; may block; null hides the image. */
fun interface InlineImageLoader {
    fun load(url: String): InlineImageData?
}

private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

internal fun mailDocument(html: String, text: Color, link: Color, allowRemoteContent: Boolean): String {
    val inline = "https://$INLINE_IMAGE_HOST"
    val imageSources = if (allowRemoteContent) "data: $inline https:" else "data: $inline"
    val csp = "default-src 'none'; img-src $imageSources; style-src 'unsafe-inline'; font-src data:; media-src 'none'; frame-src 'none'; form-action 'none'"
    return buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(csp).append("\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>")
        append("html,body{margin:0;padding:0;background:transparent}")
        append("body{color:").append(text.toCss()).append(";font-family:sans-serif;font-size:16px;line-height:1.5;")
        append("overflow-wrap:anywhere;word-break:break-word}")
        append("a{color:").append(link.toCss()).append("}")
        append("img,video,table{max-width:100%;height:auto}")
        append("pre{white-space:pre-wrap}")
        append("blockquote{margin:0 0 0 8px;padding-left:12px;border-left:2px solid ")
            .append(text.copy(alpha = 0.3f).toCss()).append("}")
        append("</style></head><body>").append(html).append("</body></html>")
    }
}

private fun Color.toCss(): String =
    "rgba(${(red * 255).toInt()}, ${(green * 255).toInt()}, ${(blue * 255).toInt()}, $alpha)"
