package app.velin.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

private val MIME_TYPE = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
// The sender sets the type. A mail must not start an app install.
private val BLOCKED_TYPES = setOf("application/vnd.android.package-archive")

/**
 * Opens a downloaded attachment in another app, read-only, through [FileProvider].
 * Returns false when no app on the device opens the file.
 */
internal fun openAttachment(context: Context, file: File, mimeType: String): Boolean {
    val type = mimeType.trim().lowercase().takeIf { MIME_TYPE.matches(it) && it !in BLOCKED_TYPES }
        ?: "application/octet-stream"
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
