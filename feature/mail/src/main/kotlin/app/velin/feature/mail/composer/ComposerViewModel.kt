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
import app.velin.core.domain.DraftResponse
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
import app.velin.core.domain.ResponseKind
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
import kotlinx.coroutines.flow.onEach
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
    /** A forward does not carry the files of the forwarded mail yet. */
    data class AttachmentsNotForwarded(val count: Int) : ComposerEffect
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
    /** UI id of the mail to reply to or forward with [responseKind]. Ignored when [draftToOpen] is set. */
    respondTo: String? = null,
    responseKind: ResponseKind = ResponseKind.Reply,
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
    /** Set for a new reply or forward until its draft exists. */
    private var response: DraftResponse? = null
    /** False until the user changes the loaded content. */
    private var edited = false
    /** Lowercase addresses with a key lookup in flight. */
    private val resolving = MutableStateFlow(emptySet<String>())
    private val passwords = MutableStateFlow(emptyMap<String, String>())
    private val query = MutableStateFlow<String?>(null)
    private val sending = MutableStateFlow(false)
    private val recipientKey = MutableStateFlow<RecipientKeyUi?>(null)
    /** Lowercase addresses whose fingerprint the user rejected. They block sending until removed. */
    private val rejected = MutableStateFlow(emptySet<String>())
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
        combine(recipients, resolving, rejected, ::Triple),
        combine(confidential, passwords, ::Pair),
        suggestions,
        combine(sending, recipientKey, ::Pair),
    ) { (all, chosen), (fields, inFlight, rejectedKeys), (isConfidential, externalPasswords), suggested, (isSending, key) ->
        val active = all.filter { it.enabled }
        val from = chosen ?: active.firstOrNull()
        val everyone = fields.values.flatten()
        val external = everyone.filter { it.type == RecipientType.External && !it.invalid }
        val passwordsComplete = !isConfidential || external.all { externalPasswords[it.address.address].orEmpty().isNotBlank() }
        ComposerUiState(
            senders = active.map { it.address.address }.toImmutableList(),
            sender = from?.address?.address.orEmpty(),
            to = fields.getValue(RecipientField.To).map { it.toUi(inFlight, rejectedKeys) }.toImmutableList(),
            cc = fields.getValue(RecipientField.Cc).map { it.toUi(inFlight, rejectedKeys) }.toImmutableList(),
            bcc = fields.getValue(RecipientField.Bcc).map { it.toUi(inFlight, rejectedKeys) }.toImmutableList(),
            suggestions = suggested.map { RecipientUi(it.address.name, it.address.address) }.toImmutableList(),
            confidential = isConfidential,
            externalPasswords = externalPasswords,
            sending = isSending,
            // A changed key blocks sending, as upstream does; the backend checks keys again on send.
            canSend = from != null && everyone.isNotEmpty() &&
                everyone.none { it.invalid || it.keyState == RecipientKeyState.Mismatch || it.address.address.lowercase() in rejectedKeys } &&
                inFlight.isEmpty() && passwordsComplete && !isSending,
            recipientKey = key,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ComposerUiState())

    init {
        viewModelScope.launch {
            if (draftToOpen != null) {
                when (val loaded = mail.loadDraft(account.id, MailId(entityIdOf(draftToOpen)))) {
                    is Outcome.Success -> fill(loaded.value)
                    is Outcome.Failed -> effectChannel.send(ComposerEffect.Failed(loaded.failure))
                }
            } else if (respondTo != null) {
                val previous = MailId(entityIdOf(respondTo))
                when (val prepared = mail.prepareResponse(account.id, previous, responseKind)) {
                    is Outcome.Success -> {
                        response = DraftResponse(previous, responseKind)
                        fill(prepared.value.draft, cursorAtStart = true)
                        if (prepared.value.omittedAttachments > 0) {
                            effectChannel.send(ComposerEffect.AttachmentsNotForwarded(prepared.value.omittedAttachments))
                        }
                    }
                    is Outcome.Failed -> {
                        // An empty composer here would send a new mail outside the conversation.
                        effectChannel.send(ComposerEffect.Failed(prepared.failure))
                        effectChannel.send(ComposerEffect.Close)
                        return@launch
                    }
                }
            }
            // The first emission is the loaded draft, so reopening never saves it unchanged.
            // Only addresses count: a finished key lookup is not an edit.
            combine(
                snapshotFlow { subject.text.toString() to body.text.toString() },
                recipients.map { fields -> fields.mapValues { (_, list) -> list.map { it.address } } }.distinctUntilChanged(),
                sender,
                confidential,
                rejected,
            ) { _, _, _, _, _ -> Unit }
                .drop(1)
                .onEach { edited = true }
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { if (!sending.value && hasContent()) save() }
        }
    }

    /** A response starts with the cursor above the quote. */
    private fun fill(draft: Draft, cursorAtStart: Boolean = false) {
        draftId.value = draft.id
        sender.value = draft.sender
        rejected.value = draft.rejectedRecipients.map { it.lowercase() }.toSet()
        recipients.value = mapOf(RecipientField.To to draft.to, RecipientField.Cc to draft.cc, RecipientField.Bcc to draft.bcc)
        confidential.value = draft.confidential
        originalHtml = draft.htmlBody
        attachments = draft.attachments
        subject.setTextAndPlaceCursorAtEnd(draft.subject)
        body.setTextAndPlaceCursorAtEnd(draft.plainText)
        if (cursorAtStart) body.edit { placeCursorBeforeCharAt(0) }
        Snapshot.sendApplyNotifications()
        // Saved drafts do not keep the recipient type, so look every address up again.
        (draft.to + draft.cc + draft.bcc).filterNot { it.invalid }.forEach { resolve(it.address.address) }
    }

    fun onEvent(event: ComposerEvent) {
        when (event) {
            ComposerEvent.Close -> viewModelScope.launch {
                // An untouched reply or forward closes without a draft.
                if (hasContent() && (edited || response == null)) {
                    val saved = save()
                    if (saved is Outcome.Failed) {
                        report(saved)
                        return@launch
                    }
                }
                effectChannel.send(ComposerEffect.Close)
            }
            ComposerEvent.Send -> send()
            ComposerEvent.DiscardDraft -> discard()
            is ComposerEvent.SelectSender -> sender.value = senders.value.firstOrNull { it.address.address == event.address }
            is ComposerEvent.QueryRecipients -> query.value = event.query
            is ComposerEvent.AddRecipient -> addRecipient(event.field, event.recipient)
            is ComposerEvent.RemoveRecipient -> {
                recipients.update { fields ->
                    fields + (event.field to fields.getValue(event.field).filterNot { it.address.address == event.address })
                }
                if (recipient(event.address) == null) rejected.update { it - event.address.lowercase() }
                if (recipientKey.value?.address == event.address) recipientKey.value = null
            }
            is ComposerEvent.SetConfidential -> confidential.value = event.confidential
            is ComposerEvent.SetExternalPassword -> passwords.update { it + (event.address to event.password) }
            is ComposerEvent.OpenRecipientKey -> openKey(event.field, event.address)
            ComposerEvent.CloseRecipientKey -> recipientKey.value = null
            ComposerEvent.ConfirmRecipientKey -> confirmKey()
            ComposerEvent.RejectRecipientKey -> recipientKey.value?.let { key ->
                rejected.update { it + key.address.lowercase() }
                recipientKey.value = key.copy(rejected = true)
            }
            ComposerEvent.ResetRecipientKey -> resetKey()
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
        if (address.lowercase() in resolving.value) return
        viewModelScope.launch { lookup(address) }
    }

    /** Returns the key state after the lookup, or null when it failed. */
    private suspend fun lookup(address: String): RecipientKeyState? {
        val key = address.lowercase()
        resolving.update { it + key }
        try {
            when (val outcome = mail.resolveRecipient(account.id, address)) {
                is Outcome.Success -> {
                    updateRecipient(address) { it.copy(type = outcome.value.type, keyState = outcome.value.keyState) }
                    return outcome.value.keyState
                }
                is Outcome.Failed -> {
                    val failure = outcome.failure
                    if (failure is Failure.RecipientResolution && failure.reason == RecipientResolutionReason.InvalidAddress) {
                        updateRecipient(address) { it.copy(invalid = true) }
                    } else if (failure !is Failure.Unsupported) {
                        // The type stays unknown; send resolves again and reports what blocks it.
                        effectChannel.send(ComposerEffect.Failed(failure))
                    }
                    return null
                }
            }
        } finally {
            resolving.update { it - key }
        }
    }

    private fun recipient(address: String) =
        recipients.value.values.flatten().firstOrNull { it.address.address.equals(address, ignoreCase = true) }

    private fun openKey(field: RecipientField, address: String) {
        val trust = recipient(address)?.toTrust() ?: return
        if (address.lowercase() in rejected.value) {
            recipientKey.value = RecipientKeyUi(field, address, trust, rejected = true)
            return
        }
        recipientKey.value = RecipientKeyUi(field, address, trust)
        if (trust == KeyTrustUi.FirstUse || trust == KeyTrustUi.Verified) loadFingerprint(address)
    }

    private fun loadFingerprint(address: String) = viewModelScope.launch {
        val outcome = mail.recipientFingerprint(account.id, address)
        recipientKey.update { key ->
            if (key?.address != address) key
            else when (outcome) {
                is Outcome.Success -> key.copy(fingerprint = outcome.value, failed = false)
                is Outcome.Failed -> key.copy(failed = true)
            }
        }
    }

    /** Pins the shown fingerprint as checked by hand. The backend refuses it if the key changed meanwhile. */
    private fun confirmKey() {
        val key = recipientKey.value ?: return
        val fingerprint = key.fingerprint ?: return
        if (key.busy) return
        recipientKey.value = key.copy(busy = true, failed = false)
        viewModelScope.launch {
            val outcome = mail.verifyRecipient(account.id, key.address, fingerprint)
            val state = lookup(key.address)
            recipientKey.update { current ->
                if (current?.address != key.address) current
                else current.copy(busy = false, failed = outcome is Outcome.Failed,
                    trust = state?.toTrust() ?: current.trust)
            }
        }
    }

    /**
     * Drops a changed pinned identity, then trusts the current key on first use. Only offered for a changed key:
     * on a checked key it would silently accept whatever key the server sends now.
     */
    private fun resetKey() {
        val key = recipientKey.value ?: return
        if (key.busy || key.trust != KeyTrustUi.Changed) return
        recipientKey.value = key.copy(busy = true, failed = false)
        viewModelScope.launch {
            val outcome = mail.untrustRecipient(account.id, key.address)
            val state = if (outcome is Outcome.Success) lookup(key.address) else null
            val trust = state?.toTrust()
            recipientKey.update { current ->
                if (current?.address != key.address) current
                else current.copy(busy = false, failed = trust == null, trust = trust ?: current.trust, fingerprint = null, rejected = false)
            }
            if (trust == KeyTrustUi.FirstUse || trust == KeyTrustUi.Verified) loadFingerprint(key.address)
        }
    }

    private fun updateRecipient(address: String, change: (Recipient) -> Recipient) = recipients.update { fields ->
        fields.mapValues { (_, list) -> list.map { if (it.address.address.equals(address, ignoreCase = true)) change(it) else it } }
    }

    private fun send() {
        if (!state.value.canSend || sending.value || rejected.value.isNotEmpty()) return
        sending.value = true
        viewModelScope.launch {
            val saved = save()
            if (rejected.value.isNotEmpty()) {
                // A fingerprint can be rejected while the network save is in flight.
                save()
                sending.value = false
                return@launch
            }
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
            response = response,
            rejectedRecipients = rejected.value,
        )
        val outcome = mail.saveDraft(account.id, draft)
        if (outcome is Outcome.Failed) {
            (outcome.failure as? Failure.DraftPersistence)?.let {
                draftId.value = it.id
                response = null
            }
        }
        if (outcome is Outcome.Success) {
            draftId.value = outcome.value
            response = null
        }
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

private fun Recipient.toTrust(): KeyTrustUi? = if (type != RecipientType.Internal) null else keyState.toTrust()

private fun RecipientKeyState.toTrust(): KeyTrustUi? = when (this) {
    RecipientKeyState.VerifiedTofu -> KeyTrustUi.FirstUse
    RecipientKeyState.VerifiedManual -> KeyTrustUi.Verified
    RecipientKeyState.Mismatch -> KeyTrustUi.Changed
    RecipientKeyState.NoEntry -> KeyTrustUi.NoKey
    RecipientKeyState.TrustStoreUnsupported -> KeyTrustUi.Unsupported
    RecipientKeyState.Unresolved -> null
}

private fun Recipient.toUi(inFlight: Set<String>, rejected: Set<String>) = ComposerRecipientUi(
    recipient = RecipientUi(address.name, address.address),
    external = type == RecipientType.External,
    internal = type == RecipientType.Internal,
    invalid = invalid,
    resolving = address.address.lowercase() in inFlight,
    keyChanged = keyState == RecipientKeyState.Mismatch,
    keyRejected = address.address.lowercase() in rejected,
    trust = toTrust(),
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
