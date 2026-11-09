package app.velin.core.domain

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val state: Flow<AuthState>
    /** Takes ownership of password and clears it after use. Never log it. */
    suspend fun signIn(address: String, password: CharArray)
    suspend fun answerSecondFactor(answer: SecondFactorAnswer)
    suspend fun cancelSignIn()
    suspend fun resume(account: AccountId)
    suspend fun removeSavedAccount(account: AccountId): Outcome<Unit>
    suspend fun signOut(account: AccountId): Outcome<Unit>
}
interface MailboxRepository {
    fun folders(account: AccountId): Flow<List<Folder>>
    fun labels(account: AccountId): Flow<List<Label>>
    fun senders(account: AccountId): Flow<List<SenderAlias>>
    fun syncState(account: AccountId): Flow<SyncState>
    suspend fun sync(account: AccountId): Outcome<Unit>
}
interface MailRepository {
    fun messages(account: AccountId, folder: FolderId): Flow<MailPage>
    fun conversation(account: AccountId, id: ConversationId): Flow<Outcome<Conversation>>
    suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit>
    suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage>
    /** Requires an active conversation collector. Consent ends with its last collector. */
    suspend fun allowExternalContent(account: AccountId, mailId: MailId): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun inlineImage(account: AccountId, id: AttachmentId): Outcome<InlineImage> = Outcome.Failed(Failure.Unsupported)
    suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit>
    suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit>
    /** Replaces labels on each selected mail. An empty list removes all labels. At most five labels per mail. */
    suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit>
    /** Read before moving. Keep this map for undo, then move each group back to its folder. */
    suspend fun currentFolders(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, FolderId>> = Outcome.Failed(Failure.Unsupported)
    /** Permanent deletion is restricted to mails currently in Trash or Spam. */
    suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft> = Outcome.Failed(Failure.Unsupported)
    suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String> = Outcome.Failed(Failure.Unsupported)
    suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit> = Outcome.Failed(Failure.Unsupported)
    suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient> = Outcome.Failed(Failure.Unsupported)
    suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId>
    suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit>
    suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<java.io.File>
    fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>>
    fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>>
}
