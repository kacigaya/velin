package app.velin.core.data

import app.velin.core.domain.*
import java.nio.file.Files
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class OfflineStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun entity(value: String) = EntityId(ListId("list"), ElementId(value))
    private val account = AccountId(ElementId("account"))
    private val other = AccountId(ElementId("other"))
    private val label = Label(LabelId(entity("label")), "Work", null)
    private val summary = MailSummary(MailId(entity("mail")), ConversationId(entity("thread")),
        listOf(Address("sender@example.invalid", "Sender")), "Subject", Instant.ofEpochMilli(5), true, false, true,
        ReplyType.Replied, listOf(label), 2)
    private val message = MailMessage(summary.id, Address("sender@example.invalid"), listOf(Address("to@example.invalid")),
        emptyList(), emptyList(), emptyList(), "Subject", Instant.ofEpochMilli(6), true, false, false, BodyState.Loading,
        listOf(Attachment(AttachmentId(entity("file")), "a.png", "image/png", 3, null)), MailAuthentication.Unknown,
        MailFinding.Phishing, null)
    private val state = OfflineState(mapOf("group" to "batch"), Instant.ofEpochMilli(7),
        MailboxSnapshot(listOf(Folder(FolderId(entity("inbox")), "Inbox", FolderKind.Inbox, 4),
            Folder(FolderId(entity("child")), "Child", FolderKind.Custom, 0, FolderId(entity("inbox")))),
            listOf(label), listOf(SenderAlias(Address("me@example.invalid", "Me"), true)), mapOf("entries" to entity("inbox"))),
        mapOf(FolderId(entity("inbox")) to MailPage(listOf(summary), PageCursor(ElementId("next")))),
        listOf(SavedMessage(message, "<p>body</p>", summary), SavedMessage(message.copy(id = MailId(entity("sent")), received = false), "")))

    @Test fun roundTripKeepsEveryField() {
        val directory = Files.createTempDirectory("offline").toFile()
        try {
            val store = EncryptedOfflineStore(directory, { key })
            assertNull(store.read(account))
            store.write(account, state)
            assertTrue(store.exists(account))
            assertEquals(state, store.read(account))
            assertFalse(directory.listFiles()!!.any { it.name.contains("account") })
            store.delete(account)
            assertFalse(store.exists(account))
        } finally { directory.deleteRecursively() }
    }
    @Test fun tamperedOrMovedDataIsDeletedNotRead() {
        val directory = Files.createTempDirectory("offline").toFile()
        try {
            val store = EncryptedOfflineStore(directory, { key })
            store.write(account, state)
            val file = directory.listFiles()!!.single()
            // A file copied to another account's name fails authentication: the AAD names the account.
            store.write(other, OfflineState())
            val target = directory.listFiles()!!.single { it != file }
            file.copyTo(target, overwrite = true)
            assertNull(store.read(other))
            assertFalse(store.exists(other))
            val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            file.writeBytes(bytes)
            assertNull(store.read(account))
            assertFalse(store.exists(account))
        } finally { directory.deleteRecursively() }
    }
}
