package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class SdkMailReadSourceTest {
    private val account = AccountId(ElementId("user"))
    // tutanota.rs:837-865. Native parsing is tested separately in velin_wire_tests.
    private val inbox = JSONObject("""{
        "431":["folders","inbox"],"435":"Inbox","436":"1",
        "1459":"entries","589":"group"
    }""").let { entity ->
        val id = entity.getJSONArray("431")
        ReadFolder(ReadId(id.getString(0), id.getString(1)), entity.getString("435"),
            entity.getLong("436"), null, entity.getString("1459"), entity.getString("589"), null)
    }
    private val child = ReadFolder(ReadId("folders", "child"), "Child", 0, inbox.id, "childEntries", "group", null)
    private val label = ReadFolder(ReadId("folders", "label"), "Work", 8, null, "labels", "group", "#112233")
    private class Reader : SdkMailReader {
        val saved = mutableListOf<WriteDraft>()
        var draftFailure: SdkFailure? = null
        var loadedDraft: ReadMessage? = null
        override suspend fun saveDraft(draft: WriteDraft): ReadId {
            draftFailure?.let { throw SdkException(it) }
            saved += draft
            return draft.id ?: ReadId("mail", "draft")
        }
        override suspend fun loadDraft(id: ReadId): ReadMessage = requireNotNull(loadedDraft)

        val moves = mutableListOf<Pair<List<ReadId>, ReadFolder>>()
        val labelsChanged = mutableListOf<Triple<List<ReadId>, List<ReadId>, List<ReadId>>>()
        val deleted = mutableListOf<Pair<List<ReadId>, ReadId>>()
        val marked = mutableListOf<List<ReadId>>()
        var memberships = emptyMap<ReadId, List<ReadId>>()
        var membership = listOf(ReadId("folders", "label"))
        override suspend fun setUnread(ids: List<ReadId>, unread: Boolean) { marked += ids }
        override suspend fun move(ids: List<ReadId>, target: ReadFolder) { moves += ids to target }
        override suspend fun applyLabels(ids: List<ReadId>, added: List<ReadId>, removed: List<ReadId>) { labelsChanged += Triple(ids, added, removed) }
        override suspend fun delete(ids: List<ReadId>, folder: ReadId) { deleted += ids to folder }
        var aliases = emptyList<ReadAddress>()
        var senderFailure: SdkFailure? = null
        override suspend fun senders(): List<ReadAddress> {
            senderFailure?.let { throw SdkException(it) }
            return aliases
        }
        var sets = emptyList<ReadFolder>()
        var rows = emptyList<ReadEntry>()
        var requested: Pair<String, String?>? = null
        var count = 3L
        var summaryFailure: SdkFailure? = null
        override suspend fun folders() = sets
        override suspend fun unread(folder: ReadFolder) = count
        override suspend fun entries(list: String, cursor: String?): List<ReadEntry> {
            requested = list to cursor
            return rows
        }
        var file: ReadAttachmentContent? = null
        override suspend fun attachment(id: ReadId): ReadAttachmentContent = file ?: throw SdkException(SdkFailure.AttachmentsUnsupported)
        var loadedMessage: ReadMessage? = null
        var conversationMails: List<ReadId> = emptyList()
        override suspend fun message(id: ReadId): ReadMessage = loadedMessage ?: throw SdkException(SdkFailure.InvalidKey)
        override suspend fun conversation(id: ReadId): List<ReadId> = conversationMails
        override suspend fun summary(id: ReadId): ReadSummary {
            summaryFailure?.let { throw SdkException(it) }
            return ReadSummary(id, ReadId("conversations", id.element),
            "sender@example.invalid", "Sender", "Subject", if (id.element == "one") 1700000000000 else 1700000000001, true, false, true, 1,
            memberships[id] ?: membership)
        }
    }
    private fun source(reader: Reader, active: Boolean = true) = SdkMailReadSource(object : SessionReadAccess {
        override suspend fun <T> read(account: AccountId, operation: suspend (SdkMailReader) -> Outcome<T>): Outcome<T> =
            if (active) operation(reader) else Outcome.Failed(Failure.Unauthorized)
    })
    private fun draft(id: DraftId? = null) = Draft(id, SenderAlias(Address("sender@example.invalid", "Sender"), true),
        listOf(Recipient(Address("to@example.invalid", "To"), RecipientType.Unknown)), emptyList(), emptyList(),
        "Subject", "a < b & c\nnext", true, emptyList(), null)

    @Test fun repeatedSavesKeepReturnedDraftIdAndEscapePlainText() = runBlocking {
        val reader = Reader()
        val source = source(reader)
        val first = source.saveDraft(account, draft()) as Outcome.Success
        assertEquals(DraftId(MailId(ReadId("mail", "draft").domain())), first.value)
        assertEquals(Outcome.Success(first.value), source.saveDraft(account, draft(first.value).copy(subject = "Changed")))
        assertNull(reader.saved.first().id)
        assertEquals(ReadId("mail", "draft"), reader.saved.last().id)
        assertEquals("a &lt; b &amp; c<br>next", reader.saved.first().html)
        assertTrue(reader.saved.all { it.confidential })
        assertEquals("to@example.invalid", reader.saved.first().to.single().address)
    }

    @Test fun draftReopeningRetainsHtmlAndAttachmentIdsUntilEdited() = runBlocking {
        val id = ReadId("mail", "draft")
        val file = ReadAttachment(ReadId("files", "file"), "File", "text/plain", 2, null)
        val reader = Reader().apply {
            aliases = listOf(ReadAddress("sender@example.invalid", "Sender"))
            loadedDraft = ReadMessage(id, aliases.single(), listOf(ReadAddress("to@example.invalid", "To")), emptyList(), emptyList(),
                listOf(ReadAddress("reply@example.invalid", "Reply")), "Subject", 0, true, false, true, 0, null,
                "<p><b>Original</b></p>", null, attachments = listOf(file))
        }
        val source = source(reader)
        val loaded = (source.loadDraft(account, MailId(id.domain())) as Outcome.Success).value
        assertEquals("Original", loaded.plainText)
        assertEquals(RecipientType.Unknown, loaded.to.single().type)
        assertEquals(AttachmentId(file.id.domain()), loaded.attachments.single().id)
        source.saveDraft(account, loaded.copy(subject = "Changed"))
        assertEquals("<p><b>Original</b></p>", reader.saved.last().html)
        assertEquals(listOf(file.id), reader.saved.last().attachments)
        source.saveDraft(account, loaded.copy(plainText = "Edited <text>"))
        assertEquals("Edited &lt;text&gt;", reader.saved.last().html)
    }

    @Test fun draftWritesKeepSessionAndFailureBoundaries() = runBlocking {
        val reader = Reader()
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).saveDraft(account, draft()))
        assertEquals(Outcome.Failed(Failure.Unsupported), source(reader).saveDraft(account, draft().copy(scheduledSendAt = java.time.Instant.EPOCH)))
        assertTrue(reader.saved.isEmpty())
        reader.draftFailure = SdkFailure.InvalidKey
        assertEquals(Outcome.Failed(Failure.Decryption), source(reader).saveDraft(account, draft()))
        reader.draftFailure = SdkFailure.DraftUnsupported
        assertEquals(Outcome.Failed(Failure.Unsupported), source(reader).saveDraft(account, draft()))
        reader.draftFailure = SdkFailure.Network
        assertEquals(Outcome.Failed(Failure.Offline), source(reader).saveDraft(account, draft()))
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).loadDraft(account, MailId(ReadId("mail", "draft").domain())))
    }

    @Test fun foldersLabelsParentsAndCounters() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox, child, label) }
        val result = source(reader).mailbox(account) as Outcome.Success
        assertEquals(listOf(FolderKind.Inbox, FolderKind.Custom), result.value.folders.map { it.kind })
        assertEquals(FolderId(inbox.id.domain()), result.value.folders[1].parent)
        assertEquals(3, result.value.folders[0].unreadCount)
        assertEquals("Work", result.value.labels.single().name)
    }
    @Test fun senderAliasesKeepServerAddressAndName() = runBlocking {
        val reader = Reader().apply {
            sets = listOf(inbox)
            aliases = listOf(ReadAddress("primary@example.invalid", "Primary"), ReadAddress("alias@example.invalid", "Alias"))
        }
        val snapshot = source(reader).mailbox(account) as Outcome.Success
        assertEquals(reader.aliases.map { SenderAlias(Address(it.address, it.name), true) }, snapshot.value.senders)
        reader.senderFailure = SdkFailure.InvalidKey
        assertEquals(Outcome.Failed(Failure.Decryption), source(reader).mailbox(account))
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).mailbox(account))
    }
    @Test fun customCursorUsesEntryNotMailId() = runBlocking {
        val reader = Reader().apply {
            sets = listOf(inbox, label)
            rows = (0 until 50).map { ReadEntry("entry$it", ReadId("mails", "mail$it")) }
        }
        val result = source(reader).page(account, FolderId(inbox.id.domain()), PageCursor(ElementId("before"))) as Outcome.Success
        assertEquals("entries" to "before", reader.requested)
        assertEquals("entry49", result.value.next?.value?.value)
        assertEquals("Work", result.value.mails.first().labels.single().name)
        assertEquals(ReplyType.Replied, result.value.mails.first().replyType)
    }
    @Test fun partialAndEmptyPagesEnd() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox) }
        assertNull((source(reader).page(account, FolderId(inbox.id.domain()), null) as Outcome.Success).value.next)
    }
    @Test fun invalidCountersDoNotBlockFoldersButUnknownKindsFail() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox); count = -1 }
        val snapshot = source(reader).mailbox(account) as Outcome.Success
        assertEquals(0, snapshot.value.folders.single().unreadCount)
        reader.count = 0; reader.sets = listOf(inbox.copy(kind = 999))
        assertTrue(source(reader).mailbox(account) is Outcome.Failed)
    }
    @Test fun missingSessionAndMissingFolderFail() = runBlocking {
        val reader = Reader()
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).mailbox(account))
        assertEquals(Outcome.Failed(Failure.Protocol(404)), source(reader).page(account, FolderId(inbox.id.domain()), null))
    }
    @Test fun secureExternalMailReturnsUnsupported() = runBlocking {
        val reader = Reader().apply {
            sets = listOf(inbox)
            rows = listOf(ReadEntry("entry", ReadId("mail", "one")))
            summaryFailure = SdkFailure.SecureExternalUnsupported
        }
        assertEquals(Outcome.Failed(Failure.Unsupported),
            source(reader).page(account, FolderId(inbox.id.domain()), null))
    }
    @Test fun receivedBodyAndFindingsKeepAuthenticationUnknown() = runBlocking {
        val id = ReadId("mails", "one")
        val reader = Reader().apply { loadedMessage = readMessage(id).copy(phishing = 1, encryptionAuth = 5) }
        val source = source(reader)
        val loaded = source.message(account, MailId(id.domain())) as Outcome.Success
        assertEquals(MailAuthentication.Unknown, loaded.value.authentication)
        assertEquals(MailFinding.Phishing, loaded.value.finding)
        assertEquals(BodyState.Loading, loaded.value.body)
        assertEquals(Outcome.Success("<p>Body</p>"), source.body(account, MailId(id.domain())))
        reader.loadedMessage = readMessage(id).copy(phishing = 0, encryptionAuth = 5, draft = true)
        val draft = (source.message(account, MailId(id.domain())) as Outcome.Success).value
        assertTrue(draft.isDraft)
        assertEquals(MailFinding.DeprecatedSenderKey, draft.finding)
    }
    @Test fun onlyRealSignalsProduceFindings() = runBlocking {
        val id = ReadId("mails", "one")
        val reader = Reader()
        val source = source(reader)
        fun finding(status: Long) = run {
            reader.loadedMessage = readMessage(id).copy(phishing = 0, encryptionAuth = 0, mailAuthStatus = status)
            runBlocking { (source.message(account, MailId(id.domain())) as Outcome.Success).value.finding }
        }
        assertEquals(null, finding(0))
        assertEquals(MailFinding.AuthenticationFailed, finding(1))
        assertEquals(MailFinding.AuthenticationUncertain, finding(2))
        assertEquals(MailFinding.AuthenticationFailed, finding(3))
    }
    @Test fun conversationUsesEntryListAndSortsMessages() = runBlocking {
        val reader = Reader().apply { conversationMails = listOf(ReadId("mails", "two"), ReadId("mails", "one"), ReadId("mails", "one")) }
        val id = ConversationId(ReadId("conversation", "entry").domain())
        val result = source(reader).conversation(account, id) as Outcome.Success
        assertEquals(listOf("one", "two"), result.value.map { it.id.value.element.value })
        assertTrue(result.value.all { it.conversationId == id })
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).conversation(account, id))
    }
    @Test fun attachmentsPreserveMetadataAndDoNotBlockBody() = runBlocking {
        val id = ReadId("mails", "one")
        val file = ReadAttachment(ReadId("files", "one"), "image.png", "image/png", 3, "logo")
        val reader = Reader().apply {
            loadedMessage = readMessage(id).copy(attachments = listOf(file))
            this.file = ReadAttachmentContent(file, byteArrayOf(1, 2, 3))
        }
        val source = source(reader)
        val message = (source.message(account, MailId(id.domain())) as Outcome.Success).value
        assertEquals("logo", message.attachments.single().contentId)
        assertEquals(Outcome.Success("<p>Body</p>"), source.body(account, MailId(id.domain())))
        val content = (source.attachment(account, AttachmentId(file.id.domain())) as Outcome.Success).value
        assertArrayEquals(byteArrayOf(1, 2, 3), content.bytes)
        assertEquals(Outcome.Failed(Failure.Unauthorized), source(reader, false).attachment(account, AttachmentId(file.id.domain())))
        reader.file = ReadAttachmentContent(file.copy(size = 4), byteArrayOf(1, 2, 3))
        assertTrue(source.attachment(account, AttachmentId(file.id.domain())) is Outcome.Failed)
    }
    @Test fun actionsBatchDeduplicateAndKeepMailbagsSeparate() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox) }
        val source = source(reader)
        val ids = (0..100).map { MailId(EntityId(ListId(if (it == 100) "other" else "bag"), ElementId("m$it"))) }
        assertEquals(Outcome.Success(Unit), source.move(account, ids + ids.first(), FolderId(inbox.id.domain())))
        assertEquals(listOf(50, 50, 1), reader.moves.map { it.first.size })
        assertTrue(reader.moves.all { it.first.map { id -> id.list }.distinct().size == 1 && it.second == inbox })
        assertEquals(Outcome.Success(Unit), source.setUnread(account, ids + ids.first(), false))
        assertEquals(listOf(50, 50, 1), reader.marked.map { it.size })
        reader.sets = listOf(inbox, label)
        assertEquals(Outcome.Success(Unit), source.setLabels(account, ids, emptyList()))
        assertEquals(listOf(50, 50, 1), reader.labelsChanged.map { it.first.size })
        val trash = inbox.copy(kind = 3)
        reader.sets = listOf(trash)
        reader.membership = listOf(trash.id)
        assertEquals(Outcome.Success(Unit), source.delete(account, ids))
        assertEquals(listOf(50, 50, 1), reader.deleted.map { it.first.size })
    }
    @Test fun labelsReplacePerMailAndEmptyRemoves() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox, label) }
        val source = source(reader)
        val ids = listOf(MailId(ReadId("bag", "one").domain()))
        assertEquals(Outcome.Success(Unit), source.setLabels(account, ids, emptyList()))
        assertEquals(listOf(label.id), reader.labelsChanged.single().third)
        reader.membership = listOf(inbox.id)
        assertEquals(Outcome.Success(Unit), source.setLabels(account, ids, listOf(LabelId(label.id.domain()))))
        assertEquals(listOf(label.id), reader.labelsChanged.last().second)
        val calls = reader.labelsChanged.size
        assertTrue(source.setLabels(account, ids, listOf(LabelId(ReadId("sets", "unknown").domain()))) is Outcome.Failed)
        assertEquals(calls, reader.labelsChanged.size)
    }
    @Test fun labelReplacementPreservesDifferencesBetweenSelectedMails() = runBlocking {
        val second = label.copy(id = ReadId("folders", "second"))
        val one = ReadId("bag", "one")
        val two = ReadId("bag", "two")
        val reader = Reader().apply {
            sets = listOf(inbox, label, second)
            memberships = mapOf(one to listOf(inbox.id, label.id), two to listOf(inbox.id, second.id))
        }
        assertEquals(Outcome.Success(Unit), source(reader).setLabels(account, listOf(one, two).map { MailId(it.domain()) }, listOf(LabelId(label.id.domain()))))
        val change = reader.labelsChanged.single()
        assertEquals(listOf(two), change.first)
        assertEquals(listOf(label.id), change.second)
        assertEquals(listOf(second.id), change.third)
    }
    @Test fun labelsRejectMoreThanFiveBeforeWriting() = runBlocking {
        val labels = (1..6).map { label.copy(id = ReadId("folders", "label$it")) }
        val reader = Reader().apply { sets = listOf(inbox) + labels }
        val ids = listOf(MailId(ReadId("bag", "one").domain()))
        assertTrue(source(reader).setLabels(account, ids, labels.map { LabelId(it.id.domain()) }) is Outcome.Failed)
        assertTrue(reader.labelsChanged.isEmpty())
    }
    @Test fun deletePreflightsTrashOrSpamAndUsesFolderFilter() = runBlocking {
        val trash = inbox.copy(id = ReadId("folders", "trash"), kind = 3)
        val spam = inbox.copy(id = ReadId("folders", "spam"), kind = 5)
        val reader = Reader().apply { sets = listOf(inbox, trash, spam); membership = listOf(inbox.id) }
        val source = source(reader)
        val ids = listOf(MailId(ReadId("bag", "one").domain()))
        assertTrue(source.delete(account, ids) is Outcome.Failed)
        assertTrue(reader.deleted.isEmpty())
        reader.membership = emptyList()
        assertTrue(source.currentFolders(account, ids) is Outcome.Failed)
        assertTrue(source.delete(account, ids) is Outcome.Failed)
        assertTrue(reader.deleted.isEmpty())
        for (folder in listOf(trash, spam)) {
            reader.membership = listOf(folder.id)
            assertEquals(Outcome.Success(mapOf(ids.single() to FolderId(folder.id.domain()))), source.currentFolders(account, ids))
            assertEquals(Outcome.Success(Unit), source.delete(account, ids))
            assertEquals(folder.id, reader.deleted.last().second)
        }
    }
    private fun readMessage(id: ReadId) = ReadMessage(id, ReadAddress("from@example.invalid", "Sender"),
        emptyList(), emptyList(), emptyList(), emptyList(), "Subject", 1700000000000, true, true,
        false, 0, null, "<p>Body</p>", null)
    @Test fun bodiesAndFilesRemainExplicitFailures() = runBlocking {
        val source = source(Reader())
        assertEquals(Outcome.Failed(Failure.Decryption), source.body(account, MailId(ReadId("m", "m").domain())))
        assertEquals(Outcome.Failed(Failure.Unsupported), source.attachment(account, AttachmentId(ReadId("f", "f").domain())))
    }
}
