package app.velin.feature.mail.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.velin.core.domain.Account
import app.velin.core.domain.Address
import app.velin.core.domain.Attachment
import app.velin.core.domain.Draft
import app.velin.core.domain.DraftId
import app.velin.core.domain.Failure
import app.velin.core.domain.FolderKind
import app.velin.core.domain.MailId
import app.velin.core.domain.MailRepository
import app.velin.core.domain.MailboxRepository
import app.velin.core.domain.Outcome
import app.velin.core.domain.Recipient
import app.velin.core.domain.RecipientKeyState
import app.velin.core.domain.RecipientResolutionReason
import app.velin.core.domain.RecipientType
import app.velin.core.domain.SendBlockReason
import app.velin.core.domain.SenderAlias
import app.velin.feature.mail.model.RecipientUi
import app.velin.feature.mail.model.entityIdOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ComposerEffect {
    data object Close : ComposerEffect
    data object Sent : ComposerEffect
    data class Failed(val failure: Failure) : ComposerEffect
}

/**
 * Composer state and draft lifecycle. The draft autosaves [AUTOSAVE_DELAY_MS] after the last
 * change and is always updated in place once it exists. Sending saves first, then sends.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ComposerViewModel(
    private val account: Account,
    private val mailbox: MailboxRepository,
    private val mail: MailRepository,
    /** UI id of an existing draft to edit; null starts a new mail. */
    draftToOpen: String? = null,
) : ViewModel() {
    val subject = TextFieldState()
    val body = TextFieldState()

    private val draftId = MutableStateFlow<DraftId?>(null)
    private val sender = MutableStateFlow<SenderAlias?>(null)
    private val recipients = MutableStateFlow(
        mapOf(RecipientField.To to emptyList<Recipient>(), RecipientField.Cc to emptyList(), RecipientField.Bcc to emptyList()),
    )
    private val confidential = MutableStateFlow(true)
    /** HTML of a reopened draft; the backend keeps it when the plain text is unchanged. */
    private var originalHtml: String? = null
    private var attachments: List<Attachment> = emptyList()
    /** Lowercase addresses with a key lookup in flight. */
    private val resolving = MutableStateFlow(emptySet<String>())
    private val passwords = MutableStateFlow(emptyMap<String, String>())
    private val query = MutableStateFlow<String?>(null)
    private val sending = MutableStateFlow(false)
    private val saveLock = Mutex()
    private val effectChannel = Channel<ComposerEffect>(Channel.BUFFERED)
    val effects: Flow<ComposerEffect> = effectChannel.receiveAsFlow()

    private val senders = mailbox.senders(account.id)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val suggestions = query.flatMapLatest { text ->
        if (text.isNullOrBlank()) emptyFlow() else mail.suggestRecipients(account.id, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val state: StateFlow<ComposerUiState> = combine(
        combine(senders, sender, ::Pair),
        combine(recipients, resolving, ::Pair),
        combine(confidential, passwords, ::Pair),
        suggestions,
        sending,
    ) { (all, chosen), (fields, inFlight), (isConfidential, externalPasswords), suggested, isSending ->
        val active = all.filter { it.enabled }
        val from = chosen ?: active.firstOrNull()
        val everyone = fields.values.flatten()
        val external = everyone.filter { it.type == RecipientType.External && !it.invalid }
        val passwordsComplete = !isConfidential || external.all { externalPasswords[it.address.address].orEmpty().isNotBlank() }
        ComposerUiState(
            senders = active.map { it.address.address }.toImmutableList(),
            sender = from?.address?.address.orEmpty(),
            to = fields.getValue(RecipientField.To).map { it.toUi(inFlight) }.toImmutableList(),
            cc = fields.getValue(RecipientField.Cc).map { it.toUi(inFlight) }.toImmutableList(),
            bcc = fields.getValue(RecipientField.Bcc).map { it.toUi(inFlight) }.toImmutableList(),
            suggestions = suggested.map { RecipientUi(it.address.name, it.address.address) }.toImmutableList(),
            confidential = isConfidential,
            externalPasswords = externalPasswords,
            sending = isSending,
            // A changed key blocks sending, as upstream does; the backend checks keys again on send.
            canSend = from != null && everyone.isNotEmpty() && everyone.none { it.invalid || it.keyState == RecipientKeyState.Mismatch } &&
                inFlight.isEmpty() && passwordsComplete && !isSending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ComposerUiState())

    init {
        viewModelScope.launch {
            if (draftToOpen != null) {
                when (val loaded = mail.loadDraft(account.id, MailId(entityIdOf(draftToOpen)))) {
                    is Outcome.Success -> fill(loaded.value)
                    is Outcome.Failed -> effectChannel.send(ComposerEffect.Failed(loaded.failure))
                }
            }
            // The first emission is the loaded draft, so reopening never saves it unchanged.
            // Only addresses count: a finished key lookup is not an edit.
            combine(
                snapshotFlow { subject.text.toString() to body.text.toString() },
                recipients.map { fields -> fields.mapValues { (_, list) -> list.map { it.address } } }.distinctUntilChanged(),
                sender,
                confidential,
            ) { _, _, _, _ -> Unit }
                .drop(1)
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { if (!sending.value && hasContent()) save() }
        }
    }

    private fun fill(draft: Draft) {
        draftId.value = draft.id
        sender.value = draft.sender
        recipients.value = mapOf(RecipientField.To to draft.to, RecipientField.Cc to draft.cc, RecipientField.Bcc to draft.bcc)
        confidential.value = draft.confidential
        originalHtml = draft.htmlBody
        attachments = draft.attachments
        subject.setTextAndPlaceCursorAtEnd(draft.subject)
        body.setTextAndPlaceCursorAtEnd(draft.plainText)
        Snapshot.sendApplyNotifications()
        // Saved drafts do not keep the recipient type, so look every address up again.
        (draft.to + draft.cc + draft.bcc).filterNot { it.invalid }.forEach { resolve(it.address.address) }
    }

    fun onEvent(event: ComposerEvent) {
        when (event) {
            ComposerEvent.Close -> viewModelScope.launch {
                if (hasContent()) report(save())
                effectChannel.send(ComposerEffect.Close)
            }
            ComposerEvent.Send -> send()
            ComposerEvent.DiscardDraft -> discard()
            is ComposerEvent.SelectSender -> sender.value = senders.value.firstOrNull { it.address.address == event.address }
            is ComposerEvent.QueryRecipients -> query.value = event.query
            is ComposerEvent.AddRecipient -> addRecipient(event.field, event.recipient)
            is ComposerEvent.RemoveRecipient -> recipients.update { fields ->
                fields + (event.field to fields.getValue(event.field).filterNot { it.address.address == event.address })
            }
            is ComposerEvent.SetConfidential -> confidential.value = event.confidential
            is ComposerEvent.SetExternalPassword -> passwords.update { it + (event.address to event.password) }
            // Attachments, scheduled sending and attachment removal arrive with the M6 backend.
            ComposerEvent.Attach, ComposerEvent.SendLater, is ComposerEvent.RemoveAttachment ->
                effectChannel.trySend(ComposerEffect.Failed(Failure.Unsupported))
        }
    }

    private fun addRecipient(field: RecipientField, recipient: RecipientUi) {
        val address = recipient.address.trim()
        if (address.isEmpty()) return
        recipients.update { fields ->
            if (fields.values.flatten().any { it.address.address.equals(address, ignoreCase = true) }) return@update fields
            val added = Recipient(Address(address, recipient.name), RecipientType.Unknown, invalid = !isValidAddress(address))
            fields + (field to fields.getValue(field) + added)
        }
        query.value = null
        if (isValidAddress(address)) resolve(address)
    }

    /** Looks up whether [address] is a Tuta address and the state of its key. */
    private fun resolve(address: String) {
        val key = address.lowercase()
        if (key in resolving.value) return
        resolving.update { it + key }
        viewModelScope.launch {
            try {
                when (val outcome = mail.resolveRecipient(account.id, address)) {
                    is Outcome.Success -> updateRecipient(address) { it.copy(type = outcome.value.type, keyState = outcome.value.keyState) }
                    is Outcome.Failed -> {
                        val failure = outcome.failure
                        if (failure is Failure.RecipientResolution && failure.reason == RecipientResolutionReason.InvalidAddress) {
                            updateRecipient(address) { it.copy(invalid = true) }
                        } else if (failure !is Failure.Unsupported) {
                            // The type stays unknown; send resolves again and reports what blocks it.
                            effectChannel.send(ComposerEffect.Failed(failure))
                        }
                    }
                }
            } finally {
                resolving.update { it - key }
            }
        }
    }

    private fun updateRecipient(address: String, change: (Recipient) -> Recipient) = recipients.update { fields ->
        fields.mapValues { (_, list) -> list.map { if (it.address.address.equals(address, ignoreCase = true)) change(it) else it } }
    }

    private fun send() {
        if (!state.value.canSend) return
        viewModelScope.launch {
            sending.value = true
            val saved = save()
            val outcome = if (saved is Outcome.Success) mail.sendDraft(account.id, saved.value) else saved.toUnitOutcome()
            sending.value = false
            when (outcome) {
                is Outcome.Success -> {
                    effectChannel.send(ComposerEffect.Sent)
                    effectChannel.send(ComposerEffect.Close)
                }
                is Outcome.Failed -> {
                    (outcome.failure as? Failure.SendBlocked)?.let(::markBlocked)
                    effectChannel.send(ComposerEffect.Failed(outcome.failure))
                }
            }
        }
    }

    /** Marks the recipients the server refused. Indices count To, then Cc, then Bcc. */
    private fun markBlocked(blocked: Failure.SendBlocked) {
        val fields = recipients.value
        val ordered = RecipientField.entries.flatMap { field -> fields.getValue(field) }
        blocked.recipientIndices.mapNotNull { ordered.getOrNull(it)?.address?.address }.forEach { address ->
            updateRecipient(address) {
                when (blocked.reason) {
                    SendBlockReason.KeyMismatch -> it.copy(keyState = RecipientKeyState.Mismatch)
                    SendBlockReason.RecipientNotFound -> it.copy(invalid = true)
                }
            }
        }
    }

    /** Moves an existing draft to Trash (reversible) and closes; an unsaved draft just closes. */
    private fun discard() {
        viewModelScope.launch {
            val id = saveLock.withLock { draftId.value }
            if (id != null) {
                val trash = mailbox.folders(account.id).first().firstOrNull { it.kind == FolderKind.Trash }
                if (trash != null) report(mail.move(account.id, listOf(id.value), trash.id))
            }
            effectChannel.send(ComposerEffect.Close)
        }
    }

    /** Creates the draft once, then updates the same draft. Serialized so saves never race. */
    private suspend fun save(): Outcome<DraftId> = saveLock.withLock {
        val from = sender.value ?: senders.value.firstOrNull { it.enabled }
            ?: return@withLock Outcome.Failed(Failure.Unsupported)
        val fields = recipients.value
        val draft = Draft(
            id = draftId.value,
            sender = from,
            to = fields.getValue(RecipientField.To),
            cc = fields.getValue(RecipientField.Cc),
            bcc = fields.getValue(RecipientField.Bcc),
            subject = subject.text.toString(),
            plainText = body.text.toString(),
            confidential = confidential.value,
            attachments = attachments,
            scheduledSendAt = null,
            htmlBody = originalHtml,
        )
        val outcome = mail.saveDraft(account.id, draft)
        if (outcome is Outcome.Success) draftId.value = outcome.value
        outcome
    }

    private fun hasContent(): Boolean =
        subject.text.isNotBlank() || body.text.isNotBlank() || recipients.value.values.any { it.isNotEmpty() }

    private fun report(outcome: Outcome<*>) {
        if (outcome is Outcome.Failed) effectChannel.trySend(ComposerEffect.Failed(outcome.failure))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val AUTOSAVE_DELAY_MS = 2_000L
    }
}

private fun Recipient.toUi(inFlight: Set<String>) = ComposerRecipientUi(
    recipient = RecipientUi(address.name, address.address),
    external = type == RecipientType.External,
    internal = type == RecipientType.Internal,
    invalid = invalid,
    resolving = address.address.lowercase() in inFlight,
    keyChanged = keyState == RecipientKeyState.Mismatch,
)

private fun Outcome<DraftId>.toUnitOutcome(): Outcome<Unit> = when (this) {
    is Outcome.Success -> Outcome.Success(Unit)
    is Outcome.Failed -> this
}

/**
 * Basic shape check only: one @, a non-empty local part and a dotted domain without spaces.
 * The server decides whether the address exists.
 */
internal fun isValidAddress(address: String): Boolean {
    val at = address.lastIndexOf('@')
    if (at <= 0 || at == address.length - 1 || address.any { it.isWhitespace() }) return false
    val domain = address.substring(at + 1)
    return '.' in domain && !domain.startsWith('.') && !domain.endsWith('.') && address.count { it == '@' } == 1
}
