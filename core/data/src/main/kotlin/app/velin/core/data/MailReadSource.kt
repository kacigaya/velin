package app.velin.core.data

import app.velin.core.domain.*

/** Decrypted source boundary. Implementations must authenticate before returning plaintext. */
interface MailReadSource {
    suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String> = Outcome.Failed(Failure.Unsupported)
    suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient> = Outcome.Failed(Failure.Unsupported)
    suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = Outcome.Failed(Failure.Unsupported)
    suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft> = Outcome.Failed(Failure.Unsupported)
    suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun currentFolders(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, FolderId>> = Outcome.Failed(Failure.Unsupported)
    suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    /** A null value means the server no longer has that mail. */
    suspend fun locate(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, LocatedMail?>> = Outcome.Failed(Failure.Unsupported)
    suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot>
    suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage>
    suspend fun conversation(account: AccountId, id: ConversationId): Outcome<List<MailSummary>>
    suspend fun message(account: AccountId, id: MailId): Outcome<MailMessage>
    suspend fun body(account: AccountId, id: MailId): Outcome<String>
    suspend fun attachment(account: AccountId, id: AttachmentId, mail: MailId? = null): Outcome<AttachmentContent>
}
/** [entryLists] maps the MailSetEntry list of each folder and label to that mail set. */
data class MailboxSnapshot(val folders: List<Folder>, val labels: List<Label>, val senders: List<SenderAlias>,
    val entryLists: Map<String, EntityId> = emptyMap())
data class LocatedMail(val summary: MailSummary, val folder: FolderId?)
data class AttachmentContent(val metadata: Attachment, val bytes: ByteArray)
