package app.velin.feature.mail

import app.velin.core.domain.Account
import app.velin.core.domain.AccountId
import app.velin.core.domain.Address
import app.velin.core.domain.AttachmentId
import app.velin.core.domain.Conversation
import app.velin.core.domain.ConversationId
import app.velin.core.domain.Draft
import app.velin.core.domain.DraftId
import app.velin.core.domain.ElementId
import app.velin.core.domain.EntityId
import app.velin.core.domain.Folder
import app.velin.core.domain.FolderId
import app.velin.core.domain.FolderKind
import app.velin.core.domain.Label
import app.velin.core.domain.LabelId
import app.velin.core.domain.ListId
import app.velin.core.domain.MailId
import app.velin.core.domain.MailMessage
import app.velin.core.domain.MailPage
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailSummary
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.Outcome
import app.velin.core.domain.PageCursor
import app.velin.core.domain.Recipient
import app.velin.core.domain.ReplyType
import app.velin.core.domain.SenderAlias
import app.velin.core.domain.SyncState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal fun eid(list: String, element: String) = EntityId(ListId(list), ElementId(element))

internal val testAccount = Account(AccountId(ElementId("u1")), Address("alex@tuta.com", "Alex"))

internal fun folder(id: String, kind: FolderKind, unread: Int = 0) = Folder(FolderId(eid("folders", id)), id, kind, unread)

internal fun label(id: String) = Label(LabelId(eid("labels", id)), id, null)

internal fun summary(id: String, unread: Boolean = false, labels: List<Label> = emptyList()) = MailSummary(
    id = MailId(eid("mails", id)),
    conversationId = ConversationId(eid("conv", id)),
    heading = listOf(Address("lena@tuta.com", "Lena")),
    subject = "Subject $id",
    receivedAt = Instant.parse("2026-11-08T10:00:00Z"),
    unread = unread,
    hasAttachments = false,
    confidential = true,
    replyType = ReplyType.None,
    labels = labels,
    conversationSize = 1,
)

internal class FakeMailbox(folders: List<Folder>) : MailboxRepository {
    val folders = MutableStateFlow(folders)
    val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    override fun folders(account: AccountId): Flow<List<Folder>> = folders
    override fun labels(account: AccountId): Flow<List<Label>> = flowOf(emptyList())
    val senderList = MutableStateFlow(listOf(SenderAlias(Address("alex@tuta.com", "Alex"), enabled = true)))
    override fun senders(account: AccountId): Flow<List<SenderAlias>> = senderList
    override fun syncState(account: AccountId): Flow<SyncState> = sync
    var syncs = 0
    override suspend fun sync(account: AccountId): Outcome<Unit> { syncs++; return Outcome.Success(Unit) }
}

internal class FakeMailRepo : MailRepository {
    val pages = mutableMapOf<FolderId, MutableStateFlow<MailPage>>()
    val moves = mutableListOf<Pair<List<MailId>, FolderId>>()
    var moveResult: Outcome<Unit> = Outcome.Success(Unit)
    val consents = mutableListOf<MailId>()
    var inline: Outcome<app.velin.core.domain.InlineImage> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    override suspend fun allowExternalContent(account: AccountId, mailId: MailId): Outcome<Unit> {
        consents += mailId
        return Outcome.Success(Unit)
    }
    override suspend fun inlineImage(account: AccountId, id: AttachmentId): Outcome<app.velin.core.domain.InlineImage> = inline
    val conversation = MutableStateFlow<Outcome<Conversation>>(Outcome.Failed(app.velin.core.domain.Failure.Offline))

    override fun messages(account: AccountId, folder: FolderId): Flow<MailPage> =
        pages.getOrPut(folder) { MutableStateFlow(MailPage(emptyList(), null)) }
    override fun conversation(account: AccountId, id: ConversationId): Flow<Outcome<Conversation>> = conversation
    override suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    val unreadCalls = mutableListOf<Pair<List<MailId>, Boolean>>()
    override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> {
        unreadCalls += ids to unread
        return Outcome.Success(Unit)
    }
    override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> {
        moves += ids to target
        return moveResult
    }
    val reports = mutableListOf<Pair<List<MailId>, app.velin.core.domain.MailReportType>>()
    val phishingMarks = mutableListOf<MailId>()
    override suspend fun report(account: AccountId, ids: List<MailId>, type: app.velin.core.domain.MailReportType): Outcome<Unit> {
        reports += ids to type
        return Outcome.Success(Unit)
    }
    override suspend fun markPhishing(account: AccountId, id: MailId): Outcome<Unit> {
        phishingMarks += id
        return moveResult
    }
    val labelCalls = mutableListOf<Pair<Set<MailId>, Set<LabelId>>>()
    val deletes = mutableListOf<List<MailId>>()
    override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> {
        labelCalls += ids.toSet() to labels.toSet()
        return Outcome.Success(Unit)
    }
    override suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> {
        deletes += ids
        return Outcome.Success(Unit)
    }
    var storedDraft: Draft? = null
    override suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft> =
        storedDraft?.let { Outcome.Success(it) } ?: Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    var response: Outcome<app.velin.core.domain.PreparedResponse> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    val responseCalls = mutableListOf<Pair<MailId, app.velin.core.domain.ResponseKind>>()
    override suspend fun prepareResponse(account: AccountId, previous: MailId, kind: app.velin.core.domain.ResponseKind) =
        response.also { responseCalls += previous to kind }
    var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var saveFailure: app.velin.core.domain.Failure? = null
    val savedDrafts = mutableListOf<Draft>()
    val sentDrafts = mutableListOf<DraftId>()
    private val responseDrafts = mutableSetOf<MailId>()
    override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> {
        savedDrafts += draft
        saveGate?.await()
        saveFailure?.let { return Outcome.Failed(it) }
        val id = draft.id ?: DraftId(MailId(eid("drafts", "d${savedDrafts.size}")))
        if (draft.response != null) responseDrafts += id.value
        return Outcome.Success(id)
    }
    var sendOutcome: Outcome<Unit> = Outcome.Success(Unit)
    val responded = mutableListOf<MailId>()
    var respondGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun markResponded(account: AccountId, sent: MailId): Outcome<Unit> {
        respondGate?.await()
        if (sent in responseDrafts) responded += sent
        return Outcome.Success(Unit)
    }
    override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> {
        sentDrafts += id
        return sendOutcome
    }
    var fingerprint: Outcome<String> = Outcome.Success("ab".repeat(32))
    val verifications = mutableListOf<Pair<String, String>>()
    var verifyOutcome: Outcome<Unit> = Outcome.Success(Unit)
    val untrusted = mutableListOf<String>()
    override suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String> = fingerprint
    override suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit> =
        verifyOutcome.also { verifications += address to fingerprint }
    override suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit> =
        Outcome.Success(Unit).also { untrusted += address }
    /** Lookup results by lowercase address; others fail as unsupported, like the interface default. */
    val resolved = mutableMapOf<String, Recipient>()
    override suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient> =
        resolved[address.lowercase()]?.let { Outcome.Success(it) } ?: Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    var download: Outcome<File> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    val downloads = mutableListOf<AttachmentId>()
    override suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<File> = download.also { downloads += id }
    override fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>> = flowOf(Outcome.Success(emptyList()))
    override fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>> = flowOf(emptyList())
}
