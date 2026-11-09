package app.velin.core.domain

import java.time.Instant

@JvmInline value class ElementId(val value: String) {
    init { require(value.isNotEmpty()) }
}
@JvmInline value class ListId(val value: String) {
    init { require(value.isNotEmpty()) }
}
data class EntityId(val list: ListId, val element: ElementId)
@JvmInline value class AccountId(val value: ElementId)
@JvmInline value class MailId(val value: EntityId)
@JvmInline value class FolderId(val value: EntityId)
@JvmInline value class LabelId(val value: EntityId)
@JvmInline value class AttachmentId(val value: EntityId)
@JvmInline value class ConversationId(val value: EntityId)
@JvmInline value class DraftId(val value: MailId)
@JvmInline value class PageCursor(val value: ElementId)

enum class FolderKind { Inbox, Drafts, Sent, Scheduled, Archive, Spam, Trash, Custom }
data class Folder(val id: FolderId, val name: String, val kind: FolderKind,
    val unreadCount: Int, val parent: FolderId? = null, val depth: Int = 0) {
    init { require(unreadCount >= 0 && depth >= 0) }
}
data class Label(val id: LabelId, val name: String, val colorHex: String?)
data class Address(val address: String, val name: String = "")
enum class RecipientType { Internal, External, Unknown }
enum class RecipientKeyState { Unresolved, NoEntry, VerifiedTofu, VerifiedManual, TrustStoreUnsupported, Mismatch }
data class Recipient(val address: Address, val type: RecipientType, val invalid: Boolean = false,
    val keyState: RecipientKeyState = RecipientKeyState.Unresolved)
data class SenderAlias(val address: Address, val enabled: Boolean)
data class Account(val id: AccountId, val address: Address)
enum class ReplyType { None, Replied, Forwarded, RepliedAndForwarded }
data class MailSummary(val id: MailId, val conversationId: ConversationId,
    val heading: List<Address>, val subject: String, val receivedAt: Instant,
    val unread: Boolean, val hasAttachments: Boolean, val confidential: Boolean,
    val replyType: ReplyType, val labels: List<Label>, val conversationSize: Int) {
    init { require(conversationSize >= 1) }
}
data class Attachment(val id: AttachmentId, val name: String, val mimeType: String,
    val sizeBytes: Long, val contentId: String? = null) {
    init { require(sizeBytes >= 0) }
}
/** Raster attachment bytes. Maximum 10 MiB. SVG and MIME guesses are rejected. */
data class InlineImage(val bytes: ByteArray, val mimeType: String)
sealed interface BodyState {
    data object Loading : BodyState
    data class Loaded(val html: String, val externalContentPresent: Boolean,
        val externalContentBlocked: Boolean, val inlineImages: Map<String, AttachmentId>) : BodyState
    data class Failed(val failure: Failure) : BodyState
}
// Upstream src/platform-kit/app-env/TutanotaConstants.ts:375 defines the wire status.
enum class MailAuthentication { Authenticated, HardFail, SoftFail, InvalidMailFrom, MissingMailFrom, Unknown }
enum class MailFinding { Phishing, AuthenticationFailed, AuthenticationUncertain, DeprecatedSenderKey }
data class MailMessage(val id: MailId, val from: Address, val to: List<Address>,
    val cc: List<Address>, val bcc: List<Address>, val replyTo: List<Address>,
    val subject: String, val sentAt: Instant, val confidential: Boolean, val unread: Boolean,
    val isDraft: Boolean, val body: BodyState, val attachments: List<Attachment>,
    val authentication: MailAuthentication, val finding: MailFinding?, val scheduledSendAt: Instant?)
data class Conversation(val id: ConversationId, val subject: String, val labels: List<Label>,
    val folderKind: FolderKind?, val messages: List<MailMessage>)
data class Draft(val id: DraftId?, val sender: SenderAlias, val to: List<Recipient>,
    val cc: List<Recipient>, val bcc: List<Recipient>, val subject: String, val plainText: String,
    val confidential: Boolean, val attachments: List<Attachment>, val scheduledSendAt: Instant?,
    val htmlBody: String? = null)
data class MailPage(val mails: List<MailSummary>, val next: PageCursor?)
enum class RecipientResolutionReason { InvalidAddress, KeyMismatch, RateLimited, InvalidKeyData, TrustStorageUnavailable }
enum class UnsupportedReason { Unspecified, ScheduledSend, NonConfidentialSend, Attachments, ReplyOrForward, IdentityTrustStore, ExternalRecipient, CalendarMail, CustomReplyTo }
enum class SendBlockReason { RecipientNotFound, KeyMismatch }
sealed interface Failure {
    data object Offline : Failure
    data object Unauthorized : Failure
    data object Decryption : Failure
    data object InvalidSoftwareVersion : Failure
    sealed class Unsupported(val reason: UnsupportedReason) : Failure {
        companion object : Unsupported(UnsupportedReason.Unspecified)
        data class Operation(val cause: UnsupportedReason) : Unsupported(cause)
    }
    data class SendBlocked(val reason: SendBlockReason, val recipientIndices: List<Int>) : Failure
    data class RecipientResolution(val reason: RecipientResolutionReason) : Failure
    data class Protocol(val status: Int, val diagnostic: String? = null) : Failure
}
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failed(val failure: Failure) : Outcome<Nothing>
}
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Offline(val lastSuccessfulSync: Instant?) : SyncState
    data class Failed(val failure: Failure) : SyncState
}
enum class AuthError { InvalidCredentials, TooManyAttempts, Offline, InvalidCode,
    SecurityKeyUnsupported, InvalidSoftwareVersion, Unknown }
sealed interface AuthState {
    data class Credentials(val accounts: List<Account>, val busy: Boolean = false,
        val error: AuthError? = null) : AuthState
    data class SecondFactor(val address: String, val totp: Boolean, val securityKey: Boolean,
        val otherSession: Boolean, val busy: Boolean = false, val error: AuthError? = null) : AuthState
    data class Active(val account: Account) : AuthState
    data class Locked(val accounts: List<Account>) : AuthState
    data class Expired(val account: AccountId) : AuthState
}
sealed interface SecondFactorAnswer {
    data class Totp(val code: String) : SecondFactorAnswer
    data object SecurityKey : SecondFactorAnswer
}
