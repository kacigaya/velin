package app.velin.feature.mail.composer

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import app.velin.core.domain.FolderKind
import app.velin.feature.mail.FakeMailRepo
import app.velin.feature.mail.FakeMailbox
import app.velin.feature.mail.folder
import app.velin.feature.mail.model.RecipientUi
import app.velin.feature.mail.testAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {
    private val mailbox = FakeMailbox(listOf(folder("inbox", FolderKind.Inbox), folder("trash", FolderKind.Trash)))
    private val repo = FakeMailRepo()

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ComposerViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ComposerViewModel(testAccount, mailbox, repo).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
        }
    }

    private fun TestScope.edit(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    @Test
    fun validatesAddressShape() {
        assertTrue(isValidAddress("a@tuta.com"))
        assertFalse(isValidAddress("a@tuta"))
        assertFalse(isValidAddress("@tuta.com"))
        assertFalse(isValidAddress("a b@tuta.com"))
        assertFalse(isValidAddress("a@@tuta.com"))
    }

    @Test
    fun invalidRecipientBlocksSending() = runTest {
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "not-an-address")))
        runCurrent()
        assertTrue(vm.state.value.to.single().invalid)
        assertFalse(vm.state.value.canSend)
        vm.onEvent(ComposerEvent.RemoveRecipient(RecipientField.To, "not-an-address"))
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("Lena", "lena@tuta.com")))
        runCurrent()
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun ignoresDuplicateRecipients() = runTest {
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "lena@tuta.com")))
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.Cc, RecipientUi("", "LENA@tuta.com")))
        runCurrent()
        assertEquals(1, vm.state.value.to.size + vm.state.value.cc.size)
    }

    @Test
    fun autosavesAfterTypingStopsAndUpdatesTheSameDraft() = runTest {
        val vm = viewModel()
        edit { vm.subject.setTextAndPlaceCursorAtEnd("Hello") }
        advanceTimeBy(1_000); runCurrent()
        assertTrue(repo.savedDrafts.isEmpty())
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, repo.savedDrafts.size)
        assertEquals(null, repo.savedDrafts.last().id)
        edit { vm.body.setTextAndPlaceCursorAtEnd("Body") }
        advanceTimeBy(2_500); runCurrent()
        assertEquals(2, repo.savedDrafts.size)
        assertEquals("d1", repo.savedDrafts.last().id?.value?.value?.element?.value)
    }

    @Test
    fun sendSavesThenSends() = runTest {
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "lena@tuta.com")))
        edit { vm.subject.setTextAndPlaceCursorAtEnd("Hi") }
        vm.onEvent(ComposerEvent.Send)
        runCurrent()
        assertEquals("Hi", repo.savedDrafts.last().subject)
        assertEquals(1, repo.sentDrafts.size)
    }
}
