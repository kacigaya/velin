package app.velin.core.domain

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun idsPreserveBothComponents() {
        val element = ElementId("same")
        assertNotEquals(MailId(EntityId(ListId("a"), element)), MailId(EntityId(ListId("b"), element)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectEmptyId() { ElementId("") }
    @Test(expected = IllegalArgumentException::class) fun rejectNegativeUnread() {
        Folder(FolderId(EntityId(ListId("a"), ElementId("b"))), "Inbox", FolderKind.Inbox, -1)
    }
}
