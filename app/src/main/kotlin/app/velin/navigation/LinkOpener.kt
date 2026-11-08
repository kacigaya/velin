package app.velin.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

private val ALLOWED_SCHEMES = setOf("https", "http", "mailto", "tel")

/**
 * Opens a link from a mail body in another app. Only web, mail and phone links leave the app;
 * intent:, file:, content: and other schemes from untrusted mail are dropped.
 */
internal fun openLink(context: Context, uri: Uri) {
    if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app handles the link; nothing safe to fall back to.
    }
}
