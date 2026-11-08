package app.velin.feature.mail.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.velin.core.domain.Account
import app.velin.core.domain.AttachmentId
import app.velin.core.domain.ConversationId
import app.velin.core.domain.Failure
import app.velin.core.domain.FolderKind
import app.velin.core.domain.MailId
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.Outcome
import app.velin.feature.mail.model.entityIdOf
import app.velin.feature.mail.model.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ConversationEffect {
    data object Close : ConversationEffect
    data class Reply(val messageId: String, val all: Boolean) : ConversationEffect
    data class Forward(val messageId: String) : ConversationEffect
    data class EditDraft(val messageId: String) : ConversationEffect
    data class OpenLink(val uri: android.net.Uri) : ConversationEffect
    data class OpenAttachment(val attachmentId: String) : ConversationEffect
    data object PickFolder : ConversationEffect
    data object PickLabels : ConversationEffect
    data class ActionFailed(val failure: Failure) : ConversationEffect
}

class ConversationViewModel(
    private val account: Account,
    conversationUiId: String,
    private val mailbox: MailboxRepository,
    private val mail: MailRepository,
) : ViewModel() {
    private val conversationId = ConversationId(entityIdOf(conversationUiId))
    private val remoteAllowed = MutableStateFlow(emptySet<String>())
    private val effectChannel = Channel<ConversationEffect>(Channel.BUFFERED)
    val effects: Flow<ConversationEffect> = effectChannel.receiveAsFlow()

    private val folders = mailbox.folders(account.id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val state: StateFlow<ConversationUiState> = combine(mail.conversation(account.id, conversationId), remoteAllowed) { outcome, allowed ->
        when (outcome) {
            is Outcome.Success -> ConversationUiState.Loaded(outcome.value.toUi(), allowed)
            is Outcome.Failed -> ConversationUiState.NotFound
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConversationUiState.Loading)

    fun onEvent(event: ConversationEvent) {
        when (event) {
            ConversationEvent.Back -> effectChannel.trySend(ConversationEffect.Close)
            ConversationEvent.Archive -> moveAll(FolderKind.Archive, close = true)
            ConversationEvent.Trash -> moveAll(FolderKind.Trash, close = true)
            ConversationEvent.ReportSpam -> moveAll(FolderKind.Spam, close = true)
            ConversationEvent.MarkUnread -> act(close = true) { ids -> mail.setUnread(account.id, ids.takeLast(1), true) }
            ConversationEvent.Move -> effectChannel.trySend(ConversationEffect.PickFolder)
            ConversationEvent.Label -> effectChannel.trySend(ConversationEffect.PickLabels)
            is ConversationEvent.Reply -> effectChannel.trySend(ConversationEffect.Reply(event.messageId, all = false))
            is ConversationEvent.ReplyAll -> effectChannel.trySend(ConversationEffect.Reply(event.messageId, all = true))
            is ConversationEvent.Forward -> effectChannel.trySend(ConversationEffect.Forward(event.messageId))
            is ConversationEvent.EditDraft -> effectChannel.trySend(ConversationEffect.EditDraft(event.messageId))
            is ConversationEvent.OpenLink -> effectChannel.trySend(ConversationEffect.OpenLink(event.uri))
            is ConversationEvent.OpenAttachment -> effectChannel.trySend(ConversationEffect.OpenAttachment(event.attachment.id))
            // Per message and per view. The data layer re-sends the body with remote images kept.
            is ConversationEvent.ShowRemoteContent -> viewModelScope.launch {
                val outcome = mail.allowExternalContent(account.id, MailId(entityIdOf(event.messageId)))
                if (outcome is Outcome.Success) remoteAllowed.update { it + event.messageId }
                report(outcome)
            }
            is ConversationEvent.RetryBody -> viewModelScope.launch {
                report(mail.load(account.id, MailId(entityIdOf(event.messageId))))
            }
            // Cancelling a scheduled send needs a domain operation that M6 adds.
            is ConversationEvent.CancelScheduledSend -> effectChannel.trySend(ConversationEffect.ActionFailed(Failure.Unsupported))
        }
    }

    /**
     * Blocking: the WebView calls this on its own background thread. Bounded so a slow
     * download cannot hold that thread forever.
     */
    fun loadAttachmentImage(attachmentUiId: String): InlineImageData? = runBlocking(Dispatchers.IO) {
        val outcome = withTimeoutOrNull(INLINE_IMAGE_TIMEOUT_MS) {
            mail.inlineImage(account.id, AttachmentId(entityIdOf(attachmentUiId)))
        }
        (outcome as? Outcome.Success)?.value?.let { InlineImageData(it.bytes, it.mimeType) }
    }

    private fun moveAll(kind: FolderKind, close: Boolean) {
        val target = folders.value.firstOrNull { it.kind == kind } ?: return
        act(close) { ids -> mail.move(account.id, ids, target.id) }
    }

    private fun act(close: Boolean, action: suspend (List<MailId>) -> Outcome<Unit>) {
        val loaded = state.value as? ConversationUiState.Loaded ?: return
        val ids = loaded.conversation.messages.map { MailId(entityIdOf(it.id)) }
        viewModelScope.launch {
            val outcome = action(ids)
            report(outcome)
            if (close && outcome is Outcome.Success) effectChannel.trySend(ConversationEffect.Close)
        }
    }

    private fun report(outcome: Outcome<*>) {
        if (outcome is Outcome.Failed) effectChannel.trySend(ConversationEffect.ActionFailed(outcome.failure))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val INLINE_IMAGE_TIMEOUT_MS = 15_000L
    }
}
