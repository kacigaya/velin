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

internal fun summary(id: String, unread: Boolean = false) = MailSummary(
    id = MailId(eid("mails", id)),
    conversationId = ConversationId(eid("conv", id)),
    heading = listOf(Address("lena@tuta.com", "Lena")),
    subject = "Subject $id",
    receivedAt = Instant.parse("2026-11-08T10:00:00Z"),
    unread = unread,
    hasAttachments = false,
    confidential = true,
    replyType = ReplyType.None,
    labels = emptyList(),
    conversationSize = 1,
)

internal class FakeMailbox(folders: List<Folder>) : MailboxRepository {
    val folders = MutableStateFlow(folders)
    val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    override fun folders(account: AccountId): Flow<List<Folder>> = folders
    override fun labels(account: AccountId): Flow<List<Label>> = flowOf(emptyList())
    override fun senders(account: AccountId): Flow<List<SenderAlias>> = flowOf(emptyList())
    override fun syncState(account: AccountId): Flow<SyncState> = sync
    override suspend fun sync(account: AccountId): Outcome<Unit> = Outcome.Success(Unit)
}

internal class FakeMailRepo : MailRepository {
    val pages = mutableMapOf<FolderId, MutableStateFlow<MailPage>>()
    val moves = mutableListOf<Pair<List<MailId>, FolderId>>()
    var moveResult: Outcome<Unit> = Outcome.Success(Unit)
    val conversation = MutableStateFlow<Outcome<Conversation>>(Outcome.Failed(app.velin.core.domain.Failure.Offline))

    override fun messages(account: AccountId, folder: FolderId): Flow<MailPage> =
        pages.getOrPut(folder) { MutableStateFlow(MailPage(emptyList(), null)) }
    override fun conversation(account: AccountId, id: ConversationId): Flow<Outcome<Conversation>> = conversation
    override suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> {
        moves += ids to target
        return moveResult
    }
    override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<File> = Outcome.Failed(app.velin.core.domain.Failure.Unsupported)
    override fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>> = flowOf(Outcome.Success(emptyList()))
    override fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>> = flowOf(emptyList())
}
