package app.velin.feature.mail.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class RecipientUi(val name: String, val address: String)

@Immutable
data class AttachmentUi(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** Body state. [html] must already be sanitized by the data layer; the view never cleans it. */
sealed interface BodyUi {
    data object Loading : BodyUi
    /** [inlineImages] maps placeholder URLs in [html] to attachment ids. */
    data class Loaded(val html: String, val inlineImages: Map<String, String> = emptyMap()) : BodyUi
    data object Failed : BodyUi
}

/**
 * Warnings shown above a mail body. Mirrors upstream FailureBannerType, ContentBlockingStatus and
 * the scheduled-send banner; at most one failure banner applies to one mail.
 */
sealed interface MailBannerUi {
    data object Phishing : MailBannerUi
    data object AuthenticationFailed : MailBannerUi
    data object AuthenticationUncertain : MailBannerUi
    data object DeprecatedSenderKey : MailBannerUi
    data object SenderKeyMismatch : MailBannerUi
    data object ExternalContentBlocked : MailBannerUi
    data class ScheduledSend(val sendAt: Instant) : MailBannerUi
}

@Immutable
data class MessageUi(
    val id: String,
    val sender: RecipientUi,
    val to: ImmutableList<RecipientUi>,
    val cc: ImmutableList<RecipientUi> = persistentListOf(),
    val bcc: ImmutableList<RecipientUi> = persistentListOf(),
    val replyTo: ImmutableList<RecipientUi> = persistentListOf(),
    val sentAt: Instant,
    val confidential: Boolean,
    val unread: Boolean,
    val isDraft: Boolean = false,
    val body: BodyUi = BodyUi.Loading,
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val banners: ImmutableList<MailBannerUi> = persistentListOf(),
    /** Not a draft, not Tuta team mail, and no phishing status yet. */
    val phishingReportable: Boolean = false,
)

@Immutable
data class ConversationUi(
    val id: String,
    val subject: String,
    val labels: ImmutableList<LabelUi>,
    val folderKind: FolderKind?,
    val messages: ImmutableList<MessageUi>,
)
