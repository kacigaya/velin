package app.velin.feature.mail.mailbox

import app.velin.core.domain.Failure
import app.velin.core.domain.FolderKind
import app.velin.core.domain.MailPage
import app.velin.core.domain.Outcome
import app.velin.feature.mail.FakeMailRepo
import app.velin.feature.mail.FakeMailbox
import app.velin.feature.mail.folder
import app.velin.feature.mail.summary
import app.velin.feature.mail.testAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModelTest {
    private val inbox = folder("inbox", FolderKind.Inbox, unread = 1)
    private val archive = folder("archive", FolderKind.Archive)
    private val trash = folder("trash", FolderKind.Trash)
    private val mailbox = FakeMailbox(listOf(inbox, archive, trash))
    private val repo = FakeMailRepo().apply {
        pages[inbox.id] = MutableStateFlow(MailPage(listOf(summary("m1", unread = true), summary("m2")), null))
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = MailboxViewModel(testAccount, mailbox, repo).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.folderPane.collect {} }
    }

    @Test
    fun opensInboxByDefault() = runTest {
        val vm = viewModel()
        assertEquals(FolderKind.Inbox.name, vm.state.value.folder?.kind?.name)
        assertEquals(listOf("mails/m1", "mails/m2"), vm.state.value.mails.map { it.id })
        assertFalse(vm.state.value.loading)
        assertEquals("folders/inbox", vm.folderPane.value.selectedId)
    }

    @Test
    fun archiveAndTrashMoveToSystemFolders() = runTest {
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Archive(setOf("mails/m1")))
        vm.onEvent(MailboxEvent.Trash(setOf("mails/m2")))
        assertEquals(listOf(archive.id, trash.id), repo.moves.map { it.second })
    }

    @Test
    fun restoreFromArchiveMovesToInbox() = runTest {
        val vm = viewModel()
        vm.onEvent(MailboxEvent.RestoreToInbox(setOf("mails/m1")))
        assertEquals(inbox.id, repo.moves.single().second)
    }

    @Test
    fun selectionTogglesAndClearsAfterAction() = runTest {
        val vm = viewModel()
        vm.onEvent(MailboxEvent.ToggleSelection("mails/m1"))
        vm.onEvent(MailboxEvent.ToggleSelection("mails/m2"))
        vm.onEvent(MailboxEvent.ToggleSelection("mails/m2"))
        assertEquals(setOf("mails/m1"), vm.state.value.selectedIds)
        vm.onEvent(MailboxEvent.Trash(vm.state.value.selectedIds))
        assertTrue(vm.state.value.selectedIds.isEmpty())
    }

    @Test
    fun reportsFailedActions() = runTest {
        repo.moveResult = Outcome.Failed(Failure.Offline)
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Archive(setOf("mails/m1")))
        assertEquals(MailboxEffect.ActionFailed(Failure.Offline), vm.effects.first())
    }
}
