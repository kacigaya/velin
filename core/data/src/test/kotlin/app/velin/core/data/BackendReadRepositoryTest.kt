package app.velin.core.data

import app.velin.core.domain.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class BackendReadRepositoryTest {
    private fun entity(value: String) = EntityId(ListId("list"), ElementId(value))
    private val account = AccountId(ElementId("account"))
    private val folder = FolderId(entity("inbox"))
    private val thread = ConversationId(entity("thread"))
    private val attachment = Attachment(AttachmentId(entity("file")), "../../outside", "image/png", 8, "logo")
    private fun summary(value: String, time: Long = 1, conversation: ConversationId = thread) = MailSummary(
        MailId(entity(value)), conversation, listOf(Address("sender@example.invalid")), value,
        Instant.ofEpochSecond(time), true, true, true, ReplyType.None, emptyList(), 1)
    private fun message(id: MailId) = MailMessage(id, Address("sender@example.invalid"), emptyList(), emptyList(),
        emptyList(), emptyList(), id.value.element.value, Instant.ofEpochSecond(if (id.value.element.value == "old") 1 else 2),
        true, true, false, BodyState.Loading, listOf(attachment), MailAuthentication.Unknown, null, null)
    private inner class Source : MailReadSource {
        var actionFailure = false
        var deleted = false
        var currentFolder = folder
        val label = Label(LabelId(entity("label")), "Work", null)
        var sendCalls = 0
        override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> { sendCalls++; return actionResult() }
        override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean) = actionResult()
        override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> {
            if (!actionFailure) currentFolder = target
            return actionResult()
        }
        override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>) = actionResult()
        override suspend fun currentFolders(account: AccountId, ids: List<MailId>) = Outcome.Success(ids.associateWith { currentFolder })
        override suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> { deleted = true; return actionResult() }
        private fun actionResult(): Outcome<Unit> = if (actionFailure) Outcome.Failed(Failure.Offline) else Outcome.Success(Unit)
        var failure = false
        var requested: PageCursor? = null
        var bodyCalls = 0
        override suspend fun mailbox(account: AccountId) = Outcome.Success(MailboxSnapshot(listOf(
            Folder(folder, "Inbox", FolderKind.Inbox, 3),
            Folder(FolderId(entity("child")), "Child", FolderKind.Custom, 1, folder),
            Folder(FolderId(entity("trash")), "Trash", FolderKind.Trash, 0)), listOf(label), emptyList()))
        override suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage> {
            requested = cursor
            return Outcome.Success(if (cursor == null) MailPage(listOf(summary("new", 2)), PageCursor(ElementId("entry")))
                else MailPage(listOf(summary("new", 2), summary("old")), null))
        }
        override suspend fun conversation(account: AccountId, id: ConversationId) = Outcome.Success(listOf(
            summary("new", 2), summary("old"), summary("new", 2), summary("foreign", 4, ConversationId(entity("other")))))
        override suspend fun message(account: AccountId, id: MailId) = Outcome.Success(message(id))
        override suspend fun body(account: AccountId, id: MailId): Outcome<String> {
            bodyCalls++
            return if (failure) Outcome.Failed(Failure.Decryption)
                else Outcome.Success("<p style='color:red'>body</p><img src='https://example.invalid/pixel'><img src='cid:logo'>")
        }
        override suspend fun attachment(account: AccountId, id: AttachmentId) = Outcome.Success(AttachmentContent(
            attachment, byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
    }
    @Test fun failedSendKeepsDraftAndSuccessfulSendInvalidatesPage() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-send").toFile()
        try {
            val repo = BackendReadRepository(source, cache)
            repo.sync(account)
            val pages = mutableListOf<MailPage>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, folder).toList(pages) }
            withTimeout(5000) { while (pages.isEmpty()) yield() }
            val initial = pages.last()
            val id = DraftId(initial.mails.single().id)
            source.actionFailure = true
            assertEquals(Outcome.Failed(Failure.Offline), repo.sendDraft(account, id))
            assertEquals(initial, pages.last())
            source.actionFailure = false
            assertEquals(Outcome.Success(Unit), repo.sendDraft(account, id))
            withTimeout(5000) { while (pages.last().mails.isNotEmpty()) yield() }
            assertEquals(2, source.sendCalls)
            collector.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun folderTreeAndPagingUseEntryCursor() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-read").toFile()
        try {
            val repo = BackendReadRepository(source, cache)
            assertEquals(Outcome.Success(Unit), repo.sync(account))
            assertEquals(1, repo.folders(account).first().single { it.id == FolderId(entity("child")) }.depth)
            val pages = mutableListOf<MailPage>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, folder).take(2).toList(pages) }
            withTimeout(5000) { while (pages.isEmpty()) yield() }
            assertEquals(Outcome.Failed(Failure.Protocol(409)), repo.loadMore(account, folder, PageCursor(ElementId("stale"))))
            assertEquals(Outcome.Success(Unit), repo.loadMore(account, folder, pages.first().next!!))
            job.join()
            assertEquals(PageCursor(ElementId("entry")), source.requested)
            assertEquals(listOf("new", "old"), pages.last().mails.map { it.id.value.element.value })
            assertNull(pages.last().next)
        } finally { cache.deleteRecursively() }
    }
    @Test fun conversationOrdersGroupsAndReemitsConsent() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-read").toFile()
        try {
            val repo = BackendReadRepository(source, cache)
            val updates = mutableListOf<Outcome<Conversation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.conversation(account, thread).take(2).toList(updates) }
            withTimeout(5000) { while (updates.isEmpty()) yield() }
            val first = (updates.first() as Outcome.Success).value
            assertEquals(listOf("old", "new"), first.messages.map { it.subject })
            val body = first.messages.first().body as BodyState.Loaded
            assertTrue(body.externalContentBlocked)
            assertEquals(attachment.id, body.inlineImages["https://velin.invalid/inline/0"])
            repo.allowExternalContent(account, first.messages.first().id)
            job.join()
            val last = (updates.last() as Outcome.Success).value
            assertFalse((last.messages.first().body as BodyState.Loaded).externalContentBlocked)
            assertTrue((last.messages.last().body as BodyState.Loaded).externalContentBlocked)
            assertEquals(2, source.bodyCalls)
            val reopened = repo.load(account, first.messages.first().id) as Outcome.Success
            assertTrue((reopened.value.body as BodyState.Loaded).externalContentBlocked)
            assertEquals(Outcome.Failed(Failure.Protocol(409)), repo.allowExternalContent(account, reopened.value.id))
            repo.clear()
            assertTrue(repo.folders(account).first().isEmpty())
        } finally { cache.deleteRecursively() }
    }
    @Test fun decryptionFailureIsNeverEmptyLoadedBody() = runBlocking {
        val source = Source().apply { failure = true }
        val cache = Files.createTempDirectory("mail-read").toFile()
        try {
            val repo = BackendReadRepository(source, cache)
            val loaded = repo.load(account, MailId(entity("old"))) as Outcome.Success
            assertEquals(BodyState.Failed(Failure.Decryption), loaded.value.body)
            assertEquals(Outcome.Failed(Failure.Decryption), repo.allowExternalContent(account, loaded.value.id))
        } finally { cache.deleteRecursively() }
    }
    @Test fun downloadNeverUsesAttachmentNameAsPath() = runBlocking {
        val cache = Files.createTempDirectory("mail-read").toFile()
        try {
            val repo = BackendReadRepository(Source(), cache)
            val result = repo.downloadAttachment(account, attachment.id) as Outcome.Success
            assertEquals(cache.canonicalFile, result.value.canonicalFile.parentFile)
            assertTrue(result.value.name.startsWith("attachment-"))
            assertEquals(8L, result.value.length())
            assertTrue(repo.inlineImage(account, attachment.id) is Outcome.Success)
        } finally { cache.deleteRecursively() }
    }
    @Test fun inlineValidationRejectsSpoofedMimeSvgAndOversize() {
        for ((mime, bytes) in listOf("image/png" to "<svg/>".toByteArray(), "image/svg+xml" to "<svg/>".toByteArray(),
            "image/png" to ByteArray(10 * 1024 * 1024 + 1))) {
            assertTrue(validateInlineImage(AttachmentContent(attachment.copy(mimeType = mime), bytes)) is Outcome.Failed)
        }
        assertTrue(validateInlineImage(AttachmentContent(attachment.copy(mimeType = "image/gif", sizeBytes = 6), "GIF89a".toByteArray())) is Outcome.Success)
    }
    @Test fun mutationsPublishOnlyAfterSuccessAndUndoRestoresPage() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-actions").toFile()
        val repo = BackendReadRepository(source, cache)
        val pages = mutableListOf<MailPage>()
        val conversations = mutableListOf<Outcome<Conversation>>()
        val pageJob = launch { repo.messages(account, folder).toList(pages) }
        val conversationJob = launch { repo.conversation(account, thread).toList(conversations) }
        try {
            withTimeout(5000) { while (pages.isEmpty() || conversations.isEmpty()) yield() }
            val id = pages.last().mails.single().id
            assertEquals(Outcome.Success(Unit), repo.setUnread(account, listOf(id), false))
            withTimeout(5000) { while (pages.last().mails.single().unread) yield() }
            assertFalse((repo.load(account, id) as Outcome.Success).value.unread)
            assertEquals(Outcome.Success(Unit), repo.setLabels(account, listOf(id), listOf(source.label.id)))
            withTimeout(5000) { while (pages.last().mails.single().labels.isEmpty()) yield() }
            assertEquals(listOf(source.label), pages.last().mails.single().labels)
            withTimeout(5000) { while ((conversations.last() as Outcome.Success).value.labels.isEmpty()) yield() }
            source.actionFailure = true
            assertEquals(Outcome.Failed(Failure.Offline), repo.setUnread(account, listOf(id), true))
            assertEquals(Outcome.Failed(Failure.Offline), repo.setLabels(account, listOf(id), emptyList()))
            assertEquals(Outcome.Failed(Failure.Offline), repo.move(account, listOf(id), FolderId(entity("trash"))))
            assertFalse((repo.load(account, id) as Outcome.Success).value.unread)
            assertEquals(listOf(source.label), pages.last().mails.single().labels)
            source.actionFailure = false
            val before = (repo.currentFolders(account, listOf(id)) as Outcome.Success).value
            assertEquals(Outcome.Success(Unit), repo.move(account, listOf(id), FolderId(entity("trash"))))
            withTimeout(5000) { while (pages.last().mails.isNotEmpty()) yield() }
            assertEquals(Outcome.Success(Unit), repo.move(account, listOf(id), before.getValue(id)))
            withTimeout(5000) { while (pages.last().mails.isEmpty()) yield() }
            assertEquals(id, pages.last().mails.single().id)
            assertEquals(Outcome.Success(Unit), repo.setLabels(account, listOf(id), emptyList()))
            withTimeout(5000) { while (pages.last().mails.single().labels.isNotEmpty()) yield() }
            withTimeout(5000) { while ((conversations.last() as Outcome.Success).value.labels.isNotEmpty()) yield() }
            assertEquals(Outcome.Failed(Failure.Protocol(409)), repo.delete(account, listOf(id)))
            assertFalse(source.deleted)
            repo.move(account, listOf(id), FolderId(entity("trash")))
            source.actionFailure = true
            assertEquals(Outcome.Failed(Failure.Offline), repo.delete(account, listOf(id)))
            assertFalse((repo.load(account, id) as Outcome.Success).value.unread)
            source.actionFailure = false
            assertEquals(Outcome.Success(Unit), repo.delete(account, listOf(id)))
            withTimeout(5000) { while ((conversations.last() as Outcome.Success).value.messages.any { it.id == id }) yield() }
        } finally { pageJob.cancelAndJoin(); conversationJob.cancelAndJoin(); cache.deleteRecursively() }
    }
    @Test fun syncReplacesLocalMutationsWithServerTruth() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-sync").toFile()
        val repo = BackendReadRepository(source, cache)
        val pages = mutableListOf<MailPage>()
        val conversations = mutableListOf<Outcome<Conversation>>()
        val pageJob = launch { repo.messages(account, folder).toList(pages) }
        val conversationJob = launch { repo.conversation(account, thread).toList(conversations) }
        try {
            withTimeout(5000) { while (pages.isEmpty() || conversations.isEmpty()) yield() }
            val id = pages.last().mails.single().id
            repo.setUnread(account, listOf(id), false)
            repo.setLabels(account, listOf(id), listOf(source.label.id))
            withTimeout(5000) { while (pages.last().mails.single().unread) yield() }
            assertEquals(Outcome.Success(Unit), repo.sync(account))
            withTimeout(5000) { while (!pages.last().mails.single().unread) yield() }
            assertTrue(pages.last().mails.single().labels.isEmpty())
            assertTrue((repo.load(account, id) as Outcome.Success).value.unread)
            withTimeout(5000) { while (!(conversations.last() as Outcome.Success).value.messages.single { it.id == id }.unread) yield() }
            assertTrue((conversations.last() as Outcome.Success).value.labels.isEmpty())
        } finally { pageJob.cancelAndJoin(); conversationJob.cancelAndJoin(); cache.deleteRecursively() }
    }
    @Test fun cyclesAreRejectedAndOrphansBecomeRoots() {
        assertThrows(IllegalArgumentException::class.java) { folderTree(listOf(Folder(folder, "cycle", FolderKind.Custom, 0, folder))) }
        assertNull(folderTree(listOf(Folder(folder, "orphan", FolderKind.Custom, 0, FolderId(entity("missing"))))).single().parent)
    }
}
