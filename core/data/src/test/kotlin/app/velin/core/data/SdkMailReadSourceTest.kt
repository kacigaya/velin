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
        override suspend fun summary(id: ReadId): ReadSummary {
            summaryFailure?.let { throw SdkException(it) }
            return ReadSummary(id, ReadId("conversations", id.element),
            "sender@example.invalid", "Sender", "Subject", 1700000000000, true, false, true, 1,
            listOf(ReadId("folders", "label")))
        }
    }
    private fun source(reader: Reader, active: Boolean = true) = SdkMailReadSource(object : SessionReadAccess {
        override suspend fun <T> read(account: AccountId, operation: suspend (SdkMailReader) -> Outcome<T>): Outcome<T> =
            if (active) operation(reader) else Outcome.Failed(Failure.Unauthorized)
    })
    @Test fun foldersLabelsParentsAndCounters() = runBlocking {
        val reader = Reader().apply { sets = listOf(inbox, child, label) }
        val result = source(reader).mailbox(account) as Outcome.Success
        assertEquals(listOf(FolderKind.Inbox, FolderKind.Custom), result.value.folders.map { it.kind })
        assertEquals(FolderId(inbox.id.domain()), result.value.folders[1].parent)
        assertEquals(3, result.value.folders[0].unreadCount)
        assertEquals("Work", result.value.labels.single().name)
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
    @Test fun bodiesAndFilesRemainExplicitFailures() = runBlocking {
        val source = source(Reader())
        assertEquals(Outcome.Failed(Failure.Decryption), source.body(account, MailId(ReadId("m", "m").domain())))
        assertEquals(Outcome.Failed(Failure.Unsupported), source.attachment(account, AttachmentId(ReadId("f", "f").domain())))
    }
}
