package app.velin.core.data

import app.velin.core.domain.*

/** Decrypted source boundary. Implementations must authenticate before returning plaintext. */
interface MailReadSource {
    suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot>
    suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage>
    suspend fun conversation(account: AccountId, id: ConversationId): Outcome<List<MailSummary>>
    suspend fun message(account: AccountId, id: MailId): Outcome<MailMessage>
    suspend fun body(account: AccountId, id: MailId): Outcome<String>
    suspend fun attachment(account: AccountId, id: AttachmentId): Outcome<AttachmentContent>
}
data class MailboxSnapshot(val folders: List<Folder>, val labels: List<Label>, val senders: List<SenderAlias>)
data class AttachmentContent(val metadata: Attachment, val bytes: ByteArray)

/** Fail closed until a complete authenticated SDK blob/key-provider adapter is available. */
internal object UnavailableMailReadSource : MailReadSource {
    override suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot> = unavailable()
    override suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage> = unavailable()
    override suspend fun conversation(account: AccountId, id: ConversationId): Outcome<List<MailSummary>> = unavailable()
    override suspend fun message(account: AccountId, id: MailId): Outcome<MailMessage> = unavailable()
    override suspend fun body(account: AccountId, id: MailId): Outcome<String> = Outcome.Failed(Failure.Decryption)
    override suspend fun attachment(account: AccountId, id: AttachmentId): Outcome<AttachmentContent> = unavailable()
    private fun unavailable() = Outcome.Failed(Failure.Unsupported)
}
