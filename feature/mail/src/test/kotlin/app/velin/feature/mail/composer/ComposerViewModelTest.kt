package app.velin.feature.mail.composer

import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import app.velin.core.domain.Address
import app.velin.core.domain.Draft
import app.velin.core.domain.Failure
import app.velin.core.domain.Outcome
import app.velin.core.domain.DraftId
import app.velin.core.domain.DraftResponse
import app.velin.core.domain.PreparedResponse
import app.velin.core.domain.ResponseKind
import app.velin.core.domain.FolderKind
import app.velin.core.domain.MailId
import app.velin.core.domain.Recipient
import app.velin.core.domain.RecipientKeyState
import app.velin.core.domain.RecipientType
import app.velin.core.domain.SendBlockReason
import app.velin.core.domain.SenderAlias
import app.velin.feature.mail.eid
import app.velin.feature.mail.FakeMailRepo
import app.velin.feature.mail.FakeMailbox
import app.velin.feature.mail.folder
import app.velin.feature.mail.model.RecipientUi
import app.velin.feature.mail.testAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

    private fun TestScope.viewModel(draft: String? = null, respondTo: String? = null,
        kind: ResponseKind = ResponseKind.Reply): ComposerViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ComposerViewModel(testAccount, mailbox, repo, draft, respondTo, kind).also { vm ->
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

    @Test
    fun reopenedDraftLoadsWithoutSavingAndKeepsItsIdAndHtml() = runTest {
        val id = DraftId(MailId(eid("drafts", "d7")))
        repo.storedDraft = Draft(
            id = id, sender = SenderAlias(Address("alex@tuta.com", "Alex"), true),
            to = listOf(Recipient(Address("lena@tuta.com"), RecipientType.Internal)), cc = emptyList(), bcc = emptyList(),
            subject = "Plan", plainText = "Hello", confidential = true, attachments = emptyList(),
            scheduledSendAt = null, htmlBody = "<b>Hello</b>",
        )
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com")
        val vm = viewModel(draft = "drafts/d7")
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Plan", vm.subject.text.toString())
        assertEquals(listOf("lena@tuta.com"), vm.state.value.to.map { it.recipient.address })
        assertTrue("recipients are looked up again", vm.state.value.to.single().internal)
        assertTrue("opening and the lookup must not save", repo.savedDrafts.isEmpty())
        edit { vm.subject.setTextAndPlaceCursorAtEnd("Plan v2") }
        advanceTimeBy(2_500); runCurrent()
        assertEquals(id, repo.savedDrafts.single().id)
        assertEquals("<b>Hello</b>", repo.savedDrafts.single().htmlBody)
    }

    private fun response(kind: ResponseKind, omitted: Int = 0) = PreparedResponse(Draft(
        id = null, sender = SenderAlias(Address("alex@tuta.com", "Alex"), true),
        to = listOf(Recipient(Address("lena@tuta.com"), RecipientType.Unknown)), cc = emptyList(), bcc = emptyList(),
        subject = "Re: Plan", plainText = "\n\n> Hello", confidential = true, attachments = emptyList(), scheduledSendAt = null,
        response = DraftResponse(MailId(eid("inbox", "m1")), kind),
    ), omitted)

    @Test
    fun replyStartsAboveTheQuoteAndClosesWithoutDraftWhenUntouched() = runTest {
        repo.response = Outcome.Success(response(ResponseKind.ReplyAll))
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com")
        val vm = viewModel(respondTo = "inbox/m1", kind = ResponseKind.ReplyAll)
        assertEquals(listOf(MailId(eid("inbox", "m1")) to ResponseKind.ReplyAll), repo.responseCalls)
        assertEquals("Re: Plan", vm.subject.text.toString())
        assertEquals(0, vm.body.selection.start)
        assertTrue("recipients are looked up", vm.state.value.to.single().internal)
        advanceTimeBy(5_000); runCurrent()
        vm.onEvent(ComposerEvent.Close)
        runCurrent()
        assertTrue("an untouched reply saves nothing", repo.savedDrafts.isEmpty())
        assertEquals(ComposerEffect.Close, vm.effects.first())
    }

    @Test
    fun editedReplyCreatesThreadedDraftOnceThenUpdatesIt() = runTest {
        repo.response = Outcome.Success(response(ResponseKind.Reply))
        val vm = viewModel(respondTo = "inbox/m1")
        edit { vm.body.edit { insert(0, "Thanks") } }
        advanceTimeBy(2_500); runCurrent()
        val created = repo.savedDrafts.single()
        assertEquals(null, created.id)
        assertEquals(DraftResponse(MailId(eid("inbox", "m1")), ResponseKind.Reply), created.response)
        assertEquals("Thanks\n\n> Hello", created.plainText)
        vm.onEvent(ComposerEvent.Send)
        runCurrent()
        assertEquals(null, repo.savedDrafts.last().response)
        assertEquals(repo.sentDrafts.single(), repo.savedDrafts.last().id)
    }

    @Test
    fun forwardReportsAttachmentsItCannotCarry() = runTest {
        repo.response = Outcome.Success(response(ResponseKind.Forward, omitted = 2))
        val vm = viewModel(respondTo = "inbox/m1", kind = ResponseKind.Forward)
        assertEquals(ComposerEffect.AttachmentsNotForwarded(2), vm.effects.first())
        repo.response = Outcome.Failed(Failure.Offline)
        val failed = viewModel(respondTo = "inbox/m1")
        assertEquals(listOf(ComposerEffect.Failed(Failure.Offline), ComposerEffect.Close), failed.effects.take(2).toList())
    }

    private fun TestScope.withRecipient(key: RecipientKeyState): ComposerViewModel {
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com", key)
        return viewModel().also { vm ->
            vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("Lena", "lena@tuta.com")))
            runCurrent()
        }
    }

    @Test
    fun confirmingTheFingerprintMarksTheKeyCheckedByHand() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedTofu)
        assertEquals(KeyTrustUi.FirstUse, vm.state.value.to.single().trust)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertEquals("ab".repeat(32), vm.state.value.recipientKey?.fingerprint)
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com", RecipientKeyState.VerifiedManual)
        vm.onEvent(ComposerEvent.ConfirmRecipientKey)
        runCurrent()
        // The shown fingerprint goes to the backend, which refuses it if the key changed meanwhile.
        assertEquals(listOf("lena@tuta.com" to "ab".repeat(32)), repo.verifications)
        assertEquals(KeyTrustUi.Verified, vm.state.value.recipientKey?.trust)
        assertEquals(KeyTrustUi.Verified, vm.state.value.to.single().trust)
        assertFalse(vm.state.value.recipientKey!!.busy)
    }

    @Test
    fun aRejectedFingerprintLeadsToRemovingTheRecipient() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedTofu)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        vm.onEvent(ComposerEvent.RejectRecipientKey)
        runCurrent()
        assertTrue(vm.state.value.recipientKey!!.rejected)
        assertTrue(repo.verifications.isEmpty())
        // Closing the sheet keeps the rejection: the chip warns, sending stays blocked and the sheet reopens rejected.
        vm.onEvent(ComposerEvent.CloseRecipientKey)
        runCurrent()
        assertTrue(vm.state.value.to.single().keyRejected)
        assertFalse(vm.state.value.canSend)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertTrue(vm.state.value.recipientKey!!.rejected)
        vm.onEvent(ComposerEvent.RemoveRecipient(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertEquals(null, vm.state.value.recipientKey)
        assertTrue(vm.state.value.to.isEmpty())
        // Added again, the address starts unrejected.
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("Lena", "lena@tuta.com")))
        runCurrent()
        assertFalse(vm.state.value.to.single().keyRejected)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun rejectionSurvivesClosingAndReopeningTheDraft() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedTofu)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        vm.onEvent(ComposerEvent.RejectRecipientKey)
        runCurrent()
        vm.onEvent(ComposerEvent.Close)
        runCurrent()
        val saved = repo.savedDrafts.last()
        assertEquals(setOf("lena@tuta.com"), saved.rejectedRecipients)
        repo.storedDraft = saved.copy(id = DraftId(MailId(eid("drafts", "d1"))))
        val reopened = viewModel(draft = "drafts/d1")
        assertTrue(reopened.state.value.to.single().keyRejected)
        assertFalse(reopened.state.value.canSend)
        reopened.onEvent(ComposerEvent.Send)
        runCurrent()
        assertTrue(repo.sentDrafts.isEmpty())
    }

    @Test
    fun removingOneDuplicateKeepsTheOtherRejected() = runTest {
        val recipient = internal("lena@tuta.com", RecipientKeyState.VerifiedTofu)
        repo.resolved["lena@tuta.com"] = recipient
        repo.storedDraft = Draft(DraftId(MailId(eid("drafts", "d1"))), SenderAlias(testAccount.address, true),
            listOf(recipient), listOf(recipient.copy(address = Address("LENA@tuta.com"))), emptyList(),
            "", "", true, emptyList(), null)
        val vm = viewModel(draft = "drafts/d1")
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        vm.onEvent(ComposerEvent.RejectRecipientKey)
        vm.onEvent(ComposerEvent.RemoveRecipient(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertTrue(vm.state.value.to.isEmpty())
        assertTrue(vm.state.value.cc.single().keyRejected)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun rejectingDuringSaveStopsSending() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedTofu)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.saveGate = gate
        vm.onEvent(ComposerEvent.Send)
        runCurrent()
        vm.onEvent(ComposerEvent.RejectRecipientKey)
        gate.complete(Unit)
        runCurrent()
        assertTrue(repo.sentDrafts.isEmpty())
        assertEquals(setOf("lena@tuta.com"), repo.savedDrafts.last().rejectedRecipients)
    }

    @Test
    fun localPersistenceFailureKeepsAllocatedDraftIdForRetry() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedTofu)
        val id = DraftId(MailId(eid("drafts", "allocated")))
        repo.saveFailure = Failure.DraftPersistence(id)
        vm.onEvent(ComposerEvent.Close)
        runCurrent()
        repo.saveFailure = null
        vm.onEvent(ComposerEvent.Close)
        runCurrent()
        assertEquals(id, repo.savedDrafts.last().id)
    }

    @Test
    fun aCheckedKeyIsNeverDroppedFromTheSheet() = runTest {
        val vm = withRecipient(RecipientKeyState.VerifiedManual)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertEquals(KeyTrustUi.Verified, vm.state.value.recipientKey?.trust)
        // Dropping it would trust whatever key the server sends now.
        vm.onEvent(ComposerEvent.ResetRecipientKey)
        runCurrent()
        assertTrue(repo.untrusted.isEmpty())
    }

    @Test
    fun trustingAChangedKeyPinsItAgainAndUnblocksSending() = runTest {
        val vm = withRecipient(RecipientKeyState.Mismatch)
        assertFalse(vm.state.value.canSend)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "lena@tuta.com"))
        runCurrent()
        assertEquals(KeyTrustUi.Changed, vm.state.value.recipientKey?.trust)
        assertEquals("a changed key shows no fingerprint", null, vm.state.value.recipientKey?.fingerprint)
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com", RecipientKeyState.VerifiedTofu)
        vm.onEvent(ComposerEvent.ResetRecipientKey)
        runCurrent()
        assertEquals(listOf("lena@tuta.com"), repo.untrusted)
        assertEquals(KeyTrustUi.FirstUse, vm.state.value.recipientKey?.trust)
        assertEquals("ab".repeat(32), vm.state.value.recipientKey?.fingerprint)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun externalOrUnresolvedRecipientsHaveNoKeySheet() = runTest {
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "someone@example.com")))
        runCurrent()
        assertEquals(null, vm.state.value.to.single().trust)
        vm.onEvent(ComposerEvent.OpenRecipientKey(RecipientField.To, "someone@example.com"))
        runCurrent()
        assertEquals(null, vm.state.value.recipientKey)
    }

    private fun internal(address: String, key: RecipientKeyState = RecipientKeyState.VerifiedTofu) =
        Recipient(Address(address), RecipientType.Internal, keyState = key)

    @Test
    fun addedRecipientIsLookedUp() = runTest {
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com")
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("Lena", "Lena@tuta.com")))
        runCurrent()
        val chip = vm.state.value.to.single()
        assertTrue(chip.internal)
        assertFalse(chip.resolving)
        assertEquals("the typed name stays", "Lena", chip.recipient.name)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun changedKeyBlocksSending() = runTest {
        repo.resolved["lena@tuta.com"] = internal("lena@tuta.com", RecipientKeyState.Mismatch)
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "lena@tuta.com")))
        runCurrent()
        assertTrue(vm.state.value.to.single().keyChanged)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun refusedSendMarksTheRecipient() = runTest {
        repo.sendOutcome = Outcome.Failed(Failure.SendBlocked(SendBlockReason.KeyMismatch, listOf(1)))
        val vm = viewModel()
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.To, RecipientUi("", "lena@tuta.com")))
        vm.onEvent(ComposerEvent.AddRecipient(RecipientField.Cc, RecipientUi("", "max@tuta.com")))
        runCurrent()
        vm.onEvent(ComposerEvent.Send)
        runCurrent()
        assertFalse(vm.state.value.to.single().keyChanged)
        assertTrue("index 1 is the first Cc", vm.state.value.cc.single().keyChanged)
        assertEquals(ComposerEffect.Failed(repo.sendOutcome.let { (it as Outcome.Failed).failure }), vm.effects.first())
        assertFalse(vm.state.value.canSend)
    }
}
