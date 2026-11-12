package app.velin.feature.mail.composer

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.core.domain.Failure
import app.velin.core.domain.RecipientResolutionReason
import app.velin.core.domain.SendBlockReason
import app.velin.core.domain.UnsupportedReason
import app.velin.feature.mail.R
import app.velin.feature.mail.model.AttachmentUi
import app.velin.feature.mail.model.RecipientUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

enum class RecipientField { To, Cc, Bcc }

@Immutable
data class ComposerRecipientUi(
    val recipient: RecipientUi,
    /** Not a Tuta address: confidential mail to it needs a password. */
    val external: Boolean,
    /** A Tuta address: confidential mail to it is end-to-end encrypted without a password. */
    val internal: Boolean = false,
    /** Address failed validation or lookup. */
    val invalid: Boolean = false,
    /** Address lookup in progress; sending waits for it. */
    val resolving: Boolean = false,
    /** The recipient's key does not match the trusted one. Sending is blocked. */
    val keyChanged: Boolean = false,
    /** The user said the fingerprint does not match. Sending is blocked until the recipient is removed. */
    val keyRejected: Boolean = false,
    /** Identity trust of a resolved Tuta recipient; null before lookup and for external addresses. */
    val trust: KeyTrustUi? = null,
)

/** How Velin trusts a recipient's identity key. */
enum class KeyTrustUi { FirstUse, Verified, Changed, NoKey, Unsupported }

/**
 * The open key sheet. [fingerprint] is the pinned identity, null while it loads or when there is none.
 * [rejected]: the user said the fingerprint does not match.
 */
@Immutable
data class RecipientKeyUi(
    val field: RecipientField,
    val address: String,
    val trust: KeyTrustUi,
    val fingerprint: String? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val rejected: Boolean = false,
)

/** Composer failures with a specific message; null means the caller's generic text. */
@StringRes
fun composerFailureMessage(failure: Failure): Int? = when (failure) {
    is Failure.SendBlocked -> when (failure.reason) {
        SendBlockReason.KeyMismatch -> R.string.composer_error_key_changed
        SendBlockReason.RecipientNotFound -> R.string.composer_error_not_found
    }
    is Failure.RecipientResolution -> when (failure.reason) {
        RecipientResolutionReason.RateLimited -> R.string.composer_error_rate_limited
        RecipientResolutionReason.KeyMismatch -> R.string.composer_error_key_changed
        RecipientResolutionReason.TrustStorageUnavailable -> R.string.composer_error_trust_storage
        RecipientResolutionReason.InvalidAddress, RecipientResolutionReason.InvalidKeyData -> R.string.composer_error_lookup
    }
    is Failure.Unsupported -> when (failure.reason) {
        UnsupportedReason.ExternalRecipient -> R.string.composer_error_external
        UnsupportedReason.NonConfidentialSend -> R.string.composer_error_not_confidential
        UnsupportedReason.Attachments -> R.string.composer_error_attachments
        else -> null
    }
    else -> null
}

data class ComposerUiState(
    val senders: ImmutableList<String> = persistentListOf(),
    val sender: String = "",
    val to: ImmutableList<ComposerRecipientUi> = persistentListOf(),
    val cc: ImmutableList<ComposerRecipientUi> = persistentListOf(),
    val bcc: ImmutableList<ComposerRecipientUi> = persistentListOf(),
    val suggestions: ImmutableList<RecipientUi> = persistentListOf(),
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val confidential: Boolean = true,
    /** Password per external address, required when [confidential] is on. */
    val externalPasswords: Map<String, String> = emptyMap(),
    val sending: Boolean = false,
    val canSend: Boolean = false,
    val recipientKey: RecipientKeyUi? = null,
)

sealed interface ComposerEvent {
    data object Close : ComposerEvent
    data object Send : ComposerEvent
    data object SendLater : ComposerEvent
    data object Attach : ComposerEvent
    data object DiscardDraft : ComposerEvent
    data class SelectSender(val address: String) : ComposerEvent
    data class QueryRecipients(val field: RecipientField, val query: String) : ComposerEvent
    data class AddRecipient(val field: RecipientField, val recipient: RecipientUi) : ComposerEvent
    data class RemoveRecipient(val field: RecipientField, val address: String) : ComposerEvent
    data class RemoveAttachment(val id: String) : ComposerEvent
    data class SetConfidential(val confidential: Boolean) : ComposerEvent
    data class SetExternalPassword(val address: String, val password: String) : ComposerEvent
    data class OpenRecipientKey(val field: RecipientField, val address: String) : ComposerEvent
    data object CloseRecipientKey : ComposerEvent
    /** The fingerprint matches the one the recipient sees in their own app. */
    data object ConfirmRecipientKey : ComposerEvent
    data object RejectRecipientKey : ComposerEvent
    /** Drop a changed pinned identity and trust the current key on first use. */
    data object ResetRecipientKey : ComposerEvent
}

/**
 * Mail editor. [subject] and [body] are owned by the caller so text survives recomposition and
 * process death through the draft store, and so typing never round-trips through [state].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposerScreen(
    state: ComposerUiState,
    subject: TextFieldState,
    body: TextFieldState,
    onEvent: (ComposerEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** The composer covers the whole window, so it shows the app's messages itself. */
    snackbarHostState: SnackbarHostState? = null,
) {
    var showCcBcc by rememberSaveable { mutableStateOf(state.cc.isNotEmpty() || state.bcc.isNotEmpty()) }
    Scaffold(
        modifier = modifier.imePadding(),
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { onEvent(ComposerEvent.Close) }) {
                            Icon(painterResource(VelinIcons.Close), stringResource(R.string.composer_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { onEvent(ComposerEvent.Attach) }) {
                            Icon(painterResource(VelinIcons.AttachFile), stringResource(R.string.composer_attach))
                        }
                        IconToggleButton(
                            checked = state.confidential,
                            onCheckedChange = { onEvent(ComposerEvent.SetConfidential(it)) },
                        ) {
                            Icon(
                                painterResource(if (state.confidential) VelinIcons.Lock else VelinIcons.LockOpen),
                                stringResource(R.string.composer_confidential),
                            )
                        }
                        SendSplitButton(state, onEvent, Modifier.padding(end = 8.dp))
                    },
                )
                if (state.sending) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
                SenderRow(state, onEvent)
                FieldDivider()
                RecipientRow(RecipientField.To, state.to, state, onEvent, trailing = {
                    if (!showCcBcc) {
                        IconButton(onClick = { showCcBcc = true }) {
                            Icon(painterResource(VelinIcons.ExpandMore), stringResource(R.string.composer_show_cc_bcc))
                        }
                    }
                })
                AnimatedVisibility(showCcBcc) {
                    Column {
                        FieldDivider()
                        RecipientRow(RecipientField.Cc, state.cc, state, onEvent)
                        FieldDivider()
                        RecipientRow(RecipientField.Bcc, state.bcc, state, onEvent)
                    }
                }
                FieldDivider()
                ExternalPasswords(state, onEvent)
                PlainField(
                    state = subject,
                    placeholder = stringResource(R.string.composer_subject),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                )
                FieldDivider()
                if (state.attachments.isNotEmpty()) {
                    AttachmentChips(state.attachments, onEvent)
                    FieldDivider()
                }
                PlainField(
                    state = body,
                    placeholder = stringResource(R.string.composer_body),
                    singleLine = false,
                    modifier = Modifier.heightIn(min = 240.dp),
                )
            }
        }
    }
    state.recipientKey?.let { RecipientKeySheet(it, onEvent) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SendSplitButton(state: ComposerUiState, onEvent: (ComposerEvent) -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val enabled = state.canSend && !state.sending
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(onClick = { onEvent(ComposerEvent.Send) }, enabled = enabled) {
                Icon(painterResource(VelinIcons.Send), contentDescription = null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.composer_send))
            }
        },
        trailingButton = {
            val moreLabel = stringResource(R.string.composer_send_options)
            Column {
                SplitButtonDefaults.TrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = moreLabel },
                ) {
                    Icon(painterResource(VelinIcons.ExpandMore), contentDescription = null, Modifier.size(SplitButtonDefaults.TrailingIconSize))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.composer_send_later)) },
                        leadingIcon = { Icon(painterResource(VelinIcons.ScheduleSend), contentDescription = null) },
                        onClick = { menuOpen = false; onEvent(ComposerEvent.SendLater) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.composer_discard)) },
                        leadingIcon = { Icon(painterResource(VelinIcons.Delete), contentDescription = null) },
                        onClick = { menuOpen = false; onEvent(ComposerEvent.DiscardDraft) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SenderRow(state: ComposerUiState, onEvent: (ComposerEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val canChoose = state.senders.size > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canChoose) Modifier.clickable { open = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FieldLabel(stringResource(R.string.composer_from))
        Text(state.sender, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (canChoose) {
            Icon(painterResource(VelinIcons.ExpandMore), contentDescription = null)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.senders.forEach { address ->
                    DropdownMenuItem(text = { Text(address) }, onClick = { open = false; onEvent(ComposerEvent.SelectSender(address)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(
    field: RecipientField,
    recipients: List<ComposerRecipientUi>,
    state: ComposerUiState,
    onEvent: (ComposerEvent) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val query = rememberTextFieldState()
    var focused by remember { mutableStateOf(false) }
    val label = when (field) {
        RecipientField.To -> stringResource(R.string.composer_to)
        RecipientField.Cc -> stringResource(R.string.composer_cc)
        RecipientField.Bcc -> stringResource(R.string.composer_bcc)
    }
    Column {
        // Top-aligned so the label stays on the first chip line when recipients wrap.
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            FieldLabel(label, Modifier.padding(end = 12.dp, top = 12.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                recipients.forEach { item -> RecipientChip(field, item, state.confidential, onEvent) }
                val removeLast = remember(recipients) { recipients.lastOrNull()?.recipient?.address }
                TextField(
                    state = query,
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .onBackspaceWhenEmpty(query) { removeLast?.let { onEvent(ComposerEvent.RemoveRecipient(field, it)) } },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    onKeyboardAction = {
                        val text = query.text.toString().trim()
                        if (text.isNotEmpty()) {
                            onEvent(ComposerEvent.AddRecipient(field, RecipientUi(name = "", address = text)))
                            query.clearText()
                        }
                    },
                    colors = transparentFieldColors(),
                )
            }
            trailing()
        }
        // Suggestions belong to the field being edited only.
        val text = query.text.toString()
        LaunchedQuery(field, text, onEvent)
        if (focused && text.isNotBlank() && state.suggestions.isNotEmpty()) {
            Suggestions(state.suggestions) { picked ->
                onEvent(ComposerEvent.AddRecipient(field, picked))
                query.clearText()
            }
        }
    }
}

@Composable
private fun RecipientChip(field: RecipientField, item: ComposerRecipientUi, confidential: Boolean, onEvent: (ComposerEvent) -> Unit) {
    val removeLabel = stringResource(R.string.composer_remove_recipient, item.recipient.address)
    val resolvingLabel = stringResource(R.string.composer_resolving)
    val colors = MaterialTheme.colorScheme
    // A resolved Tuta recipient opens its key; the close icon still removes it. Other chips remove on tap.
    val hasKey = item.trust != null && !item.resolving
    val remove = { onEvent(ComposerEvent.RemoveRecipient(field, item.recipient.address)) }
    InputChip(
        selected = false,
        onClick = if (hasKey) ({ onEvent(ComposerEvent.OpenRecipientKey(field, item.recipient.address)) }) else remove,
        label = { Text(item.recipient.name.ifBlank { item.recipient.address }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        avatar = {
            when {
                item.resolving -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = resolvingLabel },
                    strokeWidth = 2.dp,
                )
                item.keyChanged || item.keyRejected -> Icon(
                    painterResource(VelinIcons.Warning),
                    contentDescription = stringResource(if (item.keyRejected) R.string.key_trust_rejected else R.string.composer_key_changed),
                    modifier = Modifier.size(18.dp),
                )
                confidential && item.internal -> Icon(
                    painterResource(VelinIcons.Lock),
                    contentDescription = stringResource(R.string.mail_confidential),
                    modifier = Modifier.size(18.dp),
                )
                else -> SenderAvatar(item.recipient.name, item.recipient.address, size = 24.dp)
            }
        },
        trailingIcon = {
            if (hasKey) {
                Icon(painterResource(VelinIcons.Close), removeLabel, Modifier.size(18.dp).clickable(
                    onClickLabel = removeLabel, role = Role.Button, onClick = remove,
                ))
            } else {
                Icon(painterResource(VelinIcons.Close), removeLabel, Modifier.size(18.dp))
            }
        },
        colors = if (item.invalid || item.keyChanged || item.keyRejected) {
            InputChipDefaults.inputChipColors(containerColor = colors.errorContainer, labelColor = colors.onErrorContainer)
        } else {
            InputChipDefaults.inputChipColors()
        },
    )
}

@Composable
private fun Suggestions(suggestions: List<RecipientUi>, onPick: (RecipientUi) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            suggestions.take(MAX_SUGGESTIONS).forEach { recipient ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(recipient) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SenderAvatar(recipient.name, recipient.address, size = 32.dp)
                    Column {
                        if (recipient.name.isNotBlank()) Text(recipient.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            recipient.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Confidential mail to non-Tuta recipients is protected by a password shared out of band. */
@Composable
private fun ExternalPasswords(state: ComposerUiState, onEvent: (ComposerEvent) -> Unit) {
    if (!state.confidential) return
    val external = (state.to + state.cc + state.bcc).filter { it.external && !it.invalid }
    if (external.isEmpty()) return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.composer_external_password_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        external.forEach { item ->
            val address = item.recipient.address
            OutlinedTextField(
                value = state.externalPasswords[address].orEmpty(),
                onValueChange = { onEvent(ComposerEvent.SetExternalPassword(address, it)) },
                label = { Text(stringResource(R.string.composer_password_for, address)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    FieldDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(attachments: List<AttachmentUi>, onEvent: (ComposerEvent) -> Unit) {
    val context = LocalContext.current
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            val removeLabel = stringResource(R.string.composer_remove_attachment, attachment.name)
            InputChip(
                selected = false,
                onClick = { onEvent(ComposerEvent.RemoveAttachment(attachment.id)) },
                label = {
                    Text(
                        "${attachment.name} · ${Formatter.formatShortFileSize(context, attachment.sizeBytes)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(painterResource(VelinIcons.AttachFile), contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(painterResource(VelinIcons.Close), removeLabel, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun PlainField(
    state: TextFieldState,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    TextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        textStyle = textStyle,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
        colors = transparentFieldColors(),
    )
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun FieldDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

/** Backspace in an empty recipient field removes the last chip, as in other mail apps. */
private fun Modifier.onBackspaceWhenEmpty(state: TextFieldState, action: () -> Unit) = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && state.text.isEmpty()) {
        action()
        true
    } else {
        false
    }
}

/** Debounced so contact search does not run on every keystroke. */
@Composable
private fun LaunchedQuery(field: RecipientField, text: String, onEvent: (ComposerEvent) -> Unit) {
    LaunchedEffect(field, text) {
        if (text.isBlank()) return@LaunchedEffect
        delay(SUGGESTION_DEBOUNCE_MS)
        onEvent(ComposerEvent.QueryRecipients(field, text.trim()))
    }
}

private const val MAX_SUGGESTIONS = 5
private const val SUGGESTION_DEBOUNCE_MS = 150L
