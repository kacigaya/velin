package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.html.DraftHtml
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Recipients, sender, subject and quote of a reply or forward, as in MailViewerViewModel.ts:1115 and :1163.
 * The composer edits plain text, so the quote uses `>` lines that DraftHtml turns into blockquotes.
 * The header words are English, like the fixed send language.
 */
internal fun responseDraft(previous: MailMessage, kind: ResponseKind, senders: List<SenderAlias>,
    zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): PreparedResponse? {
    val enabled = senders.filter { it.enabled }
    if (enabled.isEmpty()) return null
    val own = enabled.mapTo(HashSet()) { it.address.address.lowercase(Locale.ROOT) }
    fun Address.isOwn() = address.lowercase(Locale.ROOT) in own
    val received = previous.received
    // Answer from the address that received the mail, else from the default sender.
    val sender = (if (received) previous.to + previous.cc + previous.bcc else listOf(previous.from))
        .firstNotNullOfOrNull { address -> enabled.firstOrNull { it.address.address.equals(address.address, ignoreCase = true) } }
        ?: enabled.first()
    var to = emptyList<Address>()
    var cc = emptyList<Address>()
    var bcc = emptyList<Address>()
    if (kind != ResponseKind.Forward) {
        if (received) {
            to = previous.replyTo.ifEmpty { listOf(previous.from) }
            if (kind == ResponseKind.ReplyAll) cc = (previous.to + previous.cc).filterNot { it.isOwn() }
        } else {
            // A reply to sent mail goes to its recipients again.
            to = previous.to
            if (kind == ResponseKind.ReplyAll) { cc = previous.cc; bcc = previous.bcc }
        }
    }
    val seen = HashSet<String>()
    fun List<Address>.unique() = filter { seen.add(it.address.lowercase(Locale.ROOT)) }.map { Recipient(it, RecipientType.Unknown) }
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
        .format(previous.sentAt)
    val header = if (kind == ResponseKind.Forward) buildList {
        add("Date: $date")
        add("From: ${previous.from.address}")
        if (previous.to.isNotEmpty()) add("To: ${previous.to.joinToString(", ") { it.address }}")
        if (previous.cc.isNotEmpty()) add("Cc: ${previous.cc.joinToString(", ") { it.address }}")
        add("Subject: ${previous.subject}")
        add("")
    } else listOf("$date by ${previous.from.address}:")
    val quoted = (previous.body as? BodyState.Loaded)?.let { quote(DraftHtml.toPlainText(it.html)) }.orEmpty()
    val subject = when {
        kind == ResponseKind.Forward -> "FWD: ${previous.subject}"
        previous.subject.isEmpty() || previous.subject.startsWith("Re: ", ignoreCase = true) -> previous.subject
        else -> "Re: ${previous.subject}"
    }
    val draft = Draft(id = null, sender = sender, to = to.unique(), cc = cc.unique(), bcc = bcc.unique(), subject = subject,
        plainText = "\n\n" + (header + quoted).joinToString("\n"), confidential = true, attachments = emptyList(),
        scheduledSendAt = null, response = DraftResponse(previous.id, kind))
    // Forwarding files needs attachment upload. Velin does not copy them yet.
    return PreparedResponse(draft, if (kind == ResponseKind.Forward) previous.attachments.size else 0)
}

/** One more quote level per line, in the `>> text` form that DraftHtml.fromPlainText reads. */
private fun quote(text: String): List<String> = text.lines().map { line ->
    when {
        line.isEmpty() -> ">"
        line.startsWith('>') -> ">$line"
        else -> "> $line"
    }
}
