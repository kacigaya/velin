package app.velin.feature.mail.mailbox

import app.velin.core.domain.Failure
import app.velin.core.domain.FolderKind
import app.velin.core.domain.MailId
import app.velin.core.domain.MailPage
import app.velin.core.domain.MailReportType
import app.velin.core.domain.Outcome
import app.velin.feature.mail.FakeMailRepo
import app.velin.feature.mail.FakeMailbox
import app.velin.feature.mail.eid
import app.velin.feature.mail.folder
import app.velin.feature.mail.label
import app.velin.feature.mail.summary
import app.velin.feature.mail.model.toUi
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
    fun ordersSystemFoldersLikeOtherMailApps() = runTest {
        mailbox.folders.value = listOf(trash, archive, inbox, folder("drafts", FolderKind.Drafts))
        val vm = viewModel()
        assertEquals(listOf("Inbox", "Drafts", "Archive", "Trash"), vm.folderPane.value.systemFolders.map { it.kind.name })
    }

    @Test
    fun syncsOnceWhenOpened() = runTest {
        viewModel()
        assertEquals(1, mailbox.syncs)
    }

    @Test
    fun archiveAndTrashMoveToSystemFolders() = runTest {
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Archive(setOf("mails/m1")))
        vm.onEvent(MailboxEvent.Trash(setOf("mails/m2")))
        assertEquals(listOf(archive.id, trash.id), repo.moves.map { it.second })
    }

    // MailReportDialog.ts: a move to Spam asks first; a report sends the mail's session key to Tuta.
    @Test
    fun aMoveToSpamAsksBeforeItReports() = runTest {
        val spam = folder("spam", FolderKind.Spam)
        mailbox.folders.value = listOf(inbox, archive, trash, spam)
        val vm = viewModel()
        val effects = mutableListOf<MailboxEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onEvent(MailboxEvent.ReportSpam(setOf("mails/m1")))
        vm.moveToFolder(setOf("mails/m2"), "folders/spam")
        assertEquals(listOf(MailboxEffect.ConfirmSpamReport(setOf("mails/m1")), MailboxEffect.ConfirmSpamReport(setOf("mails/m2"))), effects)
        assertTrue(repo.moves.isEmpty())
        vm.moveToSpam(setOf("mails/m1"), sendReport = false)
        assertEquals(listOf(listOf(MailId(eid("mails", "m1"))) to spam.id), repo.moves)
        assertTrue("no consent, no report", repo.reports.isEmpty())
        assertEquals(MailboxEffect.Moved(1, spam.toUi()), effects.last())
        vm.moveToSpam(setOf("mails/m2"), sendReport = true)
        // MailGuiUtils.ts:345-349: the report waits until the undo offer ends; the host then calls reportSpam.
        assertEquals(MailboxEffect.Moved(1, spam.toUi(), setOf("mails/m2")), effects.last())
        assertTrue(repo.reports.isEmpty())
        vm.reportSpam((effects.last() as MailboxEffect.Moved).report)
        assertEquals(listOf(listOf(MailId(eid("mails", "m2"))) to MailReportType.Spam), repo.reports)
    }

    @Test
    fun aFailedMoveToSpamOffersNoReport() = runTest {
        mailbox.folders.value = listOf(inbox, folder("spam", FolderKind.Spam))
        repo.moveResult = Outcome.Failed(Failure.Offline)
        val vm = viewModel()
        val effects = mutableListOf<MailboxEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.moveToSpam(setOf("mails/m1"), sendReport = true)
        assertTrue(effects.none { it is MailboxEffect.Moved })
        assertTrue(repo.reports.isEmpty())
    }

    @Test
    fun restoreFromArchiveMovesToInbox() = runTest {
        repo.pages[archive.id] = MutableStateFlow(MailPage(listOf(summary("m1")), null))
        val vm = viewModel()
        vm.selectFolder("folders/archive")
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
    fun undoMovesMailsBackToTheirFolder() = runTest {
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Trash(setOf("mails/m1")))
        assertEquals(MailboxEffect.Moved(1, trash.toUi()), vm.effects.first())
        vm.undoLastMove()
        assertEquals(listOf(trash.id, inbox.id), repo.moves.map { it.second })
        vm.undoLastMove() // Only once.
        assertEquals(2, repo.moves.size)
    }

    @Test
    fun failedMoveOffersNoUndo() = runTest {
        repo.moveResult = Outcome.Failed(Failure.Offline)
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Archive(setOf("mails/m1")))
        vm.undoLastMove()
        assertEquals(1, repo.moves.size)
    }

    @Test
    fun moveTargetsExcludeTheCurrentFolderAndScheduled() = runTest {
        mailbox.folders.value = mailbox.folders.value + folder("scheduled", FolderKind.Scheduled)
        val vm = viewModel()
        assertEquals(listOf("folders/archive", "folders/trash"), vm.moveTargets().map { it.id })
    }

    @Test
    fun labelChangesKeepEachMailsOtherLabelsAndGroupCalls() = runTest {
        repo.pages[inbox.id] = MutableStateFlow(MailPage(listOf(
            summary("m1", labels = listOf(label("a"), label("b"))),
            summary("m2", labels = listOf(label("b"))),
            summary("m3", labels = listOf(label("b"))),
        ), null))
        val vm = viewModel()
        vm.applyLabels(setOf("mails/m1", "mails/m2", "mails/m3"), added = setOf("labels/c"), removed = setOf("labels/b"))
        val calls = repo.labelCalls.associate { (mails, labels) -> mails.map { it.value.element.value }.toSet() to labels.map { it.value.element.value }.toSet() }
        assertEquals(mapOf(setOf("m1") to setOf("a", "c"), setOf("m2", "m3") to setOf("c")), calls)
    }

    @Test
    fun refusesMoreThanFiveLabels() = runTest {
        repo.pages[inbox.id] = MutableStateFlow(MailPage(listOf(summary("m1", labels = (1..5).map { label("l$it") })), null))
        val vm = viewModel()
        vm.applyLabels(setOf("mails/m1"), added = setOf("labels/l6"), removed = emptySet())
        assertEquals(MailboxEffect.TooManyLabels, vm.effects.first())
        assertTrue(repo.labelCalls.isEmpty())
    }

    @Test
    fun trashInTrashAsksThenDeletesForGood() = runTest {
        repo.pages[trash.id] = MutableStateFlow(MailPage(listOf(summary("m9")), null))
        val vm = viewModel()
        vm.selectFolder("folders/trash")
        vm.onEvent(MailboxEvent.Trash(setOf("mails/m9")))
        assertEquals(MailboxEffect.ConfirmDelete(setOf("mails/m9")), vm.effects.first())
        assertTrue(repo.moves.isEmpty())
        vm.deleteForever(setOf("mails/m9"))
        assertEquals(1, repo.deletes.size)
    }

    @Test
    fun deleteForeverIsIgnoredOutsideTrashAndSpam() = runTest {
        val vm = viewModel()
        vm.deleteForever(setOf("mails/m1"))
        assertTrue(repo.deletes.isEmpty())
    }

    @Test
    fun reportsFailedActions() = runTest {
        repo.moveResult = Outcome.Failed(Failure.Offline)
        val vm = viewModel()
        vm.onEvent(MailboxEvent.Archive(setOf("mails/m1")))
        assertEquals(MailboxEffect.ActionFailed(Failure.Offline), vm.effects.first())
    }
}
