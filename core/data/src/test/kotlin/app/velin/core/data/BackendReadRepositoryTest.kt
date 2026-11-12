package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.network.EntityChange
import app.velin.core.network.EntityOperation
import app.velin.core.network.SyncEvent
import java.nio.file.Files
import java.time.Duration
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
        var offline = false
        var serverError = false
        var mailboxCalls = 0
        var arrived = emptyList<MailSummary>()
        var located: Map<MailId, LocatedMail?>? = null
        var requested: PageCursor? = null
        var bodyCalls = 0
        override suspend fun locate(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, LocatedMail?>> =
            if (offline) Outcome.Failed(Failure.Offline) else located?.let { Outcome.Success(it) } ?: Outcome.Failed(Failure.Unsupported)
        override suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot> = if (offline) Outcome.Failed(Failure.Offline)
            else if (serverError) Outcome.Failed(Failure.Protocol(500))
            else Outcome.Success(MailboxSnapshot(listOf(
            Folder(folder, "Inbox", FolderKind.Inbox, 3),
            Folder(FolderId(entity("child")), "Child", FolderKind.Custom, 1, folder),
            Folder(FolderId(entity("trash")), "Trash", FolderKind.Trash, 0)), listOf(label), emptyList(),
            mapOf("inbox-entries" to folder.value))).also { mailboxCalls++ }
        override suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage> {
            if (offline) return Outcome.Failed(Failure.Offline)
            if (folder.value.element.value == "gone") return Outcome.Failed(Failure.Protocol(404))
            requested = cursor
            return Outcome.Success(if (cursor == null) MailPage(arrived + summary("new", 2), PageCursor(ElementId("entry")))
                else MailPage(listOf(summary("new", 2), summary("old")), null))
        }
        override suspend fun conversation(account: AccountId, id: ConversationId): Outcome<List<MailSummary>> =
            if (offline) Outcome.Failed(Failure.Offline) else Outcome.Success(arrived + listOf(
            summary("new", 2), summary("old"), summary("new", 2), summary("foreign", 4, ConversationId(entity("other")))))
        override suspend fun message(account: AccountId, id: MailId): Outcome<MailMessage> =
            if (offline) Outcome.Failed(Failure.Offline) else Outcome.Success(message(id))
        override suspend fun body(account: AccountId, id: MailId): Outcome<String> {
            if (offline) return Outcome.Failed(Failure.Offline)
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
            // A failed body is not kept. The next load tries again.
            source.failure = false
            assertTrue((repo.load(account, loaded.value.id) as Outcome.Success).value.body is BodyState.Loaded)
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
    private class MemoryOfflineStore : OfflineStore {
        val saved = mutableMapOf<AccountId, OfflineState>()
        var writes = 0
        override fun exists(account: AccountId) = account in saved
        override fun read(account: AccountId) = saved[account]
        override fun write(account: AccountId, state: OfflineState) { writes++; saved[account] = state }
        override fun delete(account: AccountId) { saved.remove(account) }
    }
    /** A generated ID for [millis], as EntityUtils.ts timestampToGeneratedId builds it. */
    private fun generatedId(millis: Long, counter: Int = 0): String {
        val bytes = java.nio.ByteBuffer.allocate(9).putLong(millis shl 22 or (counter.toLong() shl 8)).put(0).array()
        val alphabet = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
        val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return java.util.Base64.getEncoder().encodeToString(bytes).map { alphabet[standard.indexOf(it)] }.joinToString("")
    }
    private val clock = Instant.ofEpochSecond(1_800_000_000)
    private fun b(n: Int) = generatedId(clock.toEpochMilli() - 60_000, n)
    private fun batch(id: String, vararg changes: EntityChange) = SyncEvent.Batch("group", id, changes.toList())
    private fun mailChange(value: String, operation: EntityOperation) = EntityChange("tutanota", 97, "list", value, operation)
    private suspend fun firstPage(repo: BackendReadRepository): MailPage =
        withTimeout(5000) { repo.messages(account, folder).first { it.mails.isNotEmpty() } }

    @Test fun offlineColdStartShowsSavedListAndOpenedMail() = runBlocking {
        val store = MemoryOfflineStore()
        val cache = Files.createTempDirectory("mail-offline").toFile()
        val clock = Instant.ofEpochSecond(1_000)
        try {
            val online = BackendReadRepository(Source(), cache, store) { clock }
            online.open(account)
            assertEquals(Outcome.Success(Unit), online.sync(account))
            val id = firstPage(online).mails.single().id
            assertTrue((online.load(account, id) as Outcome.Success).value.body is BodyState.Loaded)
            // "old" is in the conversation but in no saved page, like a sent reply.
            withTimeout(5000) { online.conversation(account, thread).first { it is Outcome.Success } }

            val source = Source().apply { offline = true }
            val restarted = BackendReadRepository(source, cache, store) { clock }
            restarted.open(account)
            assertEquals(listOf(id), firstPage(restarted).mails.map { it.id })
            assertEquals("Inbox", restarted.folders(account).first().first().name)
            val body = (restarted.load(account, id) as Outcome.Success).value.body as BodyState.Loaded
            assertTrue(body.externalContentBlocked)
            val conversation = withTimeout(5000) { restarted.conversation(account, thread).first() } as Outcome.Success
            assertEquals(listOf("old", "new"), conversation.value.messages.map { it.id.value.element.value })
            assertEquals(Outcome.Failed(Failure.Offline), restarted.sync(account))
            assertEquals(SyncState.Offline(clock), restarted.syncState(account).first())
            assertEquals(0, source.bodyCalls)
        } finally { cache.deleteRecursively() }
    }
    @Test fun batchMovesCursorOnlyAfterItsChangesAreSaved() = runBlocking {
        val store = MemoryOfflineStore()
        val source = Source()
        val cache = Files.createTempDirectory("mail-events").toFile()
        try {
            val repo = BackendReadRepository(source, cache, store) { clock }
            repo.open(account)
            repo.sync(account)
            val pages = mutableListOf<MailPage>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, folder).toList(pages) }
            withTimeout(5000) { while (pages.lastOrNull()?.mails.isNullOrEmpty()) yield() }
            assertEquals(emptyMap<String, String>(), repo.cursors(account))

            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(1), mailChange("new", EntityOperation.Delete))))
            withTimeout(5000) { while (pages.last().mails.isNotEmpty()) yield() }
            assertEquals(mapOf("group" to b(1)), store.saved.getValue(account).cursors)
            assertTrue(store.saved.getValue(account).pages.getValue(folder).mails.isEmpty())

            // An unknown entry list means a new mail set: the mailbox must reload, and offline it cannot.
            source.offline = true
            val unknownList = EntityChange("tutanota", 1450, "new-entries", "entry", EntityOperation.Create)
            assertEquals(Outcome.Failed(Failure.Offline), repo.apply(account, batch(b(2), unknownList)))
            assertEquals(mapOf("group" to b(1)), repo.cursors(account))
            assertEquals(mapOf("group" to b(1)), store.saved.getValue(account).cursors)

            // A server error keeps the work too. The next batch finishes it. A replayed older batch never moves the cursor back.
            source.offline = false
            source.serverError = true
            assertEquals(Outcome.Failed(Failure.Protocol(500)), repo.apply(account, batch(b(3))))
            source.serverError = false
            val calls = source.mailboxCalls
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(4))))
            assertEquals(calls + 1, source.mailboxCalls)
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(0))))
            assertEquals(mapOf("group" to b(4)), store.saved.getValue(account).cursors)

            // A new mail in a known entry list reloads that folder.
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(5),
                EntityChange("tutanota", 1450, "inbox-entries", "entry", EntityOperation.Create))))
            withTimeout(5000) { while (pages.last().mails.isEmpty()) yield() }
            job.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun locatedMailLeavesFoldersItIsNoLongerIn() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-located").toFile()
        try {
            val repo = BackendReadRepository(source, cache, MemoryOfflineStore()) { clock }
            repo.open(account)
            val pages = mutableListOf<MailPage>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, folder).toList(pages) }
            withTimeout(5000) { while (pages.lastOrNull()?.mails.isNullOrEmpty()) yield() }
            val id = pages.last().mails.single().id
            repo.load(account, id)
            val labelPage = FolderId(source.label.id.value)
            val labelled = mutableListOf<MailPage>()
            val labelJob = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, labelPage).toList(labelled) }
            withTimeout(5000) { while (labelled.lastOrNull()?.mails.isNullOrEmpty()) yield() }
            repo.sync(account)
            val read = summary("new", 2).copy(unread = false, labels = listOf(source.label))
            source.located = mapOf(id to LocatedMail(read, FolderId(entity("trash"))))
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(1), mailChange("new", EntityOperation.Update))))
            withTimeout(5000) { while (pages.last().mails.isNotEmpty()) yield() }
            // The mail left Inbox but still has the label, so the label list keeps it.
            assertEquals(listOf(read), labelled.last().mails)
            assertFalse((repo.load(account, id) as Outcome.Success).value.unread)
            job.cancelAndJoin(); labelJob.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun expiredCursorsStartAgainAndForgottenAccountsStaySaved() = runBlocking {
        val store = MemoryOfflineStore()
        val fresh = generatedId(clock.minus(Duration.ofDays(43)).toEpochMilli())
        val expired = generatedId(clock.minus(Duration.ofDays(45)).toEpochMilli())
        assertEquals(clock.minus(Duration.ofDays(45)).toEpochMilli(), generatedIdTime(expired))
        store.saved[account] = OfflineState(mapOf("group" to fresh), clock)
        val cache = Files.createTempDirectory("mail-expired").toFile()
        try {
            assertEquals(mapOf("group" to fresh), BackendReadRepository(Source(), cache, store) { clock }.cursors(account))
            // A recent full sync does not make an old cursor valid again.
            store.saved[account] = OfflineState(mapOf("group" to fresh, "quiet" to expired), clock)
            val repo = BackendReadRepository(Source(), cache, store) { clock }
            repo.open(account)
            assertEquals(emptyMap<String, String>(), repo.cursors(account))
            repo.forget(account)
            assertFalse(store.exists(account))
            assertEquals(Outcome.Success(Unit), repo.sync(account))
            assertFalse(store.exists(account))
        } finally { cache.deleteRecursively() }
    }
    @Test fun newMailReachesOpenConversation() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-reply").toFile()
        try {
            val repo = BackendReadRepository(source, cache, MemoryOfflineStore()) { clock }
            repo.open(account)
            repo.sync(account)
            firstPage(repo)
            val updates = mutableListOf<Outcome<Conversation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.conversation(account, thread).toList(updates) }
            withTimeout(5000) { while ((updates.lastOrNull() as? Outcome.Success)?.value?.messages?.size != 2) yield() }
            source.arrived = listOf(summary("reply", 3))
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(1),
                EntityChange("tutanota", 1450, "inbox-entries", "entry", EntityOperation.Create))))
            withTimeout(5000) { while ((updates.last() as Outcome.Success).value.messages.size != 3) yield() }
            job.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun syncDropsPagesOfDeletedFolders() = runBlocking {
        val cache = Files.createTempDirectory("mail-gone").toFile()
        try {
            val repo = BackendReadRepository(Source(), cache)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.messages(account, FolderId(entity("gone"))).collect {} }
            yield()
            assertEquals(Outcome.Success(Unit), repo.sync(account))
            assertEquals(Outcome.Success(Unit), repo.sync(account))
            job.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun newMailInUnloadedFolderReachesOpenConversation() = runBlocking {
        val source = Source()
        val cache = Files.createTempDirectory("mail-filed").toFile()
        try {
            val repo = BackendReadRepository(source, cache, MemoryOfflineStore()) { clock }
            repo.open(account)
            val updates = mutableListOf<Outcome<Conversation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repo.conversation(account, thread).toList(updates) }
            withTimeout(5000) { while ((updates.lastOrNull() as? Outcome.Success)?.value?.messages?.size != 2) yield() }
            val reply = summary("reply", 3)
            source.arrived = listOf(reply)
            source.located = mapOf(reply.id to LocatedMail(reply, FolderId(entity("filed"))))
            assertEquals(Outcome.Success(Unit), repo.apply(account, batch(b(1), mailChange("reply", EntityOperation.Create))))
            withTimeout(5000) { while ((updates.last() as Outcome.Success).value.messages.size != 3) yield() }
            job.cancelAndJoin()
        } finally { cache.deleteRecursively() }
    }
    @Test fun batchIdsCompareByTime() {
        assertTrue(newerId("b", "a"))
        assertTrue(newerId("-0", "z"))
        assertFalse(newerId("a", "a"))
        assertFalse(newerId("_", "a"))
    }
    @Test fun cyclesAreRejectedAndOrphansBecomeRoots() {
        assertThrows(IllegalArgumentException::class.java) { folderTree(listOf(Folder(folder, "cycle", FolderKind.Custom, 0, folder))) }
        assertNull(folderTree(listOf(Folder(folder, "orphan", FolderKind.Custom, 0, FolderId(entity("missing"))))).single().parent)
    }
}
