package app.velin.navigation

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.velin.core.domain.MAX_ATTACHMENTS_BYTES
import app.velin.core.domain.NewAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException

private val MIME_TYPE = Regex("[a-z0-9_]+/[a-z0-9_.+-]+")

sealed interface PickedFiles {
    data class Read(val files: List<NewAttachment>) : PickedFiles
    data object TooLarge : PickedFiles
    data object Unreadable : PickedFiles
}

internal fun readPickedFiles(resolver: ContentResolver, uris: List<Uri>): PickedFiles {
    var total = 0L
    val files = ArrayList<NewAttachment>(uris.size)
    for (uri in uris) {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ATTACHMENTS_BYTES) return PickedFiles.TooLarge
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: return PickedFiles.Unreadable
        } catch (_: IOException) {
            return PickedFiles.Unreadable
        } catch (_: SecurityException) {
            return PickedFiles.Unreadable
        }
        val type = resolver.getType(uri)?.trim()?.lowercase()?.takeIf { MIME_TYPE.matches(it) } ?: "application/octet-stream"
        files += NewAttachment(fileName(name), type, bytes)
    }
    return PickedFiles.Read(files)
}

internal fun fileName(name: String?): String {
    val clean = name.orEmpty().filterNot { it.isISOControl() }.trim()
    if (clean.isEmpty()) return "attachment"
    if (clean.codePointCount(0, clean.length) <= 255) return clean
    return clean.substring(0, clean.offsetByCodePoints(0, 255))
}
