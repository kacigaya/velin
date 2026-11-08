package app.velin.feature.mail.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

// Presentation models. Data-layer types map into these so screens never depend on
// Tuta entity shapes. Keep them flat and display-ready.

enum class FolderKind { Inbox, Drafts, Sent, Scheduled, Archive, Spam, Trash, Custom }

@Immutable
data class FolderUi(
    val id: String,
    val name: String,
    val kind: FolderKind,
    val unreadCount: Int,
    /** Nesting level for custom folders; 0 for top level. */
    val depth: Int = 0,
)

@Immutable
data class LabelUi(
    val id: String,
    val name: String,
    /** Tuta stores label colors as "#RRGGBB"; null means the default label color. */
    val colorHex: String?,
)

enum class ReplyState { None, Replied, Forwarded, RepliedAndForwarded }

@Immutable
data class MailSummaryUi(
    val id: String,
    val conversationId: String,
    /** Sender for received mail, recipients for sent mail and drafts. */
    val heading: String,
    val headingAddress: String,
    val subject: String,
    val receivedAt: Instant,
    val unread: Boolean,
    val hasAttachments: Boolean,
    /** Tuta's "confidential" flag: end-to-end encrypted. */
    val confidential: Boolean,
    val replyState: ReplyState = ReplyState.None,
    val labels: ImmutableList<LabelUi> = persistentListOf(),
    /** Number of mails in the conversation; 1 when conversation view is off. */
    val conversationSize: Int = 1,
)

@Immutable
data class AccountUi(
    val id: String,
    val displayName: String,
    val address: String,
)
