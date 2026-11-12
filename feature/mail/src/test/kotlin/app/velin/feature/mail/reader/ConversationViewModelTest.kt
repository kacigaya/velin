package app.velin.feature.mail.reader

import app.velin.core.domain.Address
import app.velin.core.domain.AttachmentId
import app.velin.core.domain.BodyState
import app.velin.core.domain.Conversation
import app.velin.core.domain.ConversationId
import app.velin.core.domain.Failure
import app.velin.core.domain.FolderKind
import app.velin.core.domain.InlineImage
import app.velin.core.domain.MailAuthentication
import app.velin.core.domain.MailId
import app.velin.core.domain.MailMessage
import app.velin.core.domain.Outcome
import app.velin.feature.mail.FakeMailRepo
import app.velin.feature.mail.FakeMailbox
import app.velin.feature.mail.eid
import app.velin.feature.mail.folder
import app.velin.feature.mail.model.AttachmentUi
import app.velin.feature.mail.model.BodyUi
import app.velin.feature.mail.testAccount
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val repo = FakeMailRepo()
    private val mailbox = FakeMailbox(listOf(folder("inbox", FolderKind.Inbox)))
    private val placeholder = "https://velin.invalid/inline/0"

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun message(id: String, unread: Boolean = false, draft: Boolean = false) = MailMessage(
        id = MailId(eid("mails", id)), from = Address("a@tuta.com"), to = emptyList(), cc = emptyList(),
        bcc = emptyList(), replyTo = emptyList(), subject = "s", sentAt = Instant.EPOCH, confidential = true,
        unread = unread, isDraft = draft,
        body = BodyState.Loaded("<img src=\"$placeholder\">", true, true, mapOf(placeholder to AttachmentId(eid("files", "f1")))),
        attachments = emptyList(), authentication = MailAuthentication.Unknown, finding = null, scheduledSendAt = null,
    )

    private fun TestScope.viewModel(messages: List<MailMessage> = listOf(message("m1"))): ConversationViewModel {
        repo.conversation.value = Outcome.Success(Conversation(ConversationId(eid("conv", "c1")), "s", emptyList(), null, messages))
        return ConversationViewModel(testAccount, "conv/c1", mailbox, repo).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        }
    }

    @Test
    fun mapsInlinePlaceholdersToAttachmentIds() = runTest {
        val loaded = viewModel().state.value as ConversationUiState.Loaded
        val body = loaded.conversation.messages.single().body as BodyUi.Loaded
        assertEquals(mapOf(placeholder to "files/f1"), body.inlineImages)
    }

    @Test
    fun showRemoteContentAsksDataLayerThenAllows() = runTest {
        val vm = viewModel()
        vm.onEvent(ConversationEvent.ShowRemoteContent("mails/m1"))
        assertEquals(listOf(MailId(eid("mails", "m1"))), repo.consents)
        assertTrue("mails/m1" in (vm.state.value as ConversationUiState.Loaded).remoteContentAllowed)
    }

    @Test
    fun openingAnAttachmentDownloadsItFirst() = runTest {
        val vm = viewModel()
        val attachment = AttachmentUi("files/f2", "a.pdf", "application/pdf", 3)
        val effects = mutableListOf<ConversationEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onEvent(ConversationEvent.OpenAttachment("mails/m1", attachment))
        assertEquals(listOf(ConversationEffect.ActionFailed(Failure.Unsupported)), effects)
        val file = java.io.File("a.pdf")
        repo.download = Outcome.Success(file)
        vm.onEvent(ConversationEvent.OpenAttachment("mails/m1", attachment))
        assertEquals(ConversationEffect.OpenAttachment(file, "application/pdf"), effects.last())
        assertEquals(List(2) { AttachmentId(eid("files", "f2")) }, repo.downloads)
    }

    @Test
    fun loadsInlineImageBytesOrNothing() = runTest {
        val vm = viewModel()
        assertNull(vm.loadAttachmentImage("files/f1"))
        repo.inline = Outcome.Success(InlineImage(byteArrayOf(1, 2), "image/png"))
        val image = vm.loadAttachmentImage("files/f1")!!
        assertArrayEquals(byteArrayOf(1, 2), image.bytes)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun openingMarksUnreadMessagesReadOnce() = runTest {
        val messages = listOf(message("m1", unread = true), message("m2"), message("d1", unread = true, draft = true))
        viewModel(messages)
        assertEquals(listOf(listOf(MailId(eid("mails", "m1"))) to false), repo.unreadCalls)
        // A refresh that still reports the message unread does not mark it again.
        repo.conversation.value = Outcome.Success(Conversation(ConversationId(eid("conv", "c1")), "s2", emptyList(), null, messages))
        assertEquals(1, repo.unreadCalls.size)
    }
}
