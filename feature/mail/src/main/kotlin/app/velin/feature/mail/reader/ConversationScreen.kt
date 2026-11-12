package app.velin.feature.mail.reader

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.LoadingPane
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.component.StatusMessage
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R
import app.velin.feature.mail.mailbox.LabelChip
import app.velin.feature.mail.mailbox.OverflowAction
import app.velin.feature.mail.mailbox.OverflowMenu
import app.velin.feature.mail.mailbox.formatListDate
import app.velin.feature.mail.model.AttachmentUi
import app.velin.feature.mail.model.BodyUi
import app.velin.feature.mail.model.ConversationUi
import app.velin.feature.mail.model.FolderKind
import app.velin.feature.mail.model.MailBannerUi
import app.velin.feature.mail.model.MessageUi
import app.velin.feature.mail.model.RecipientUi
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed interface ConversationUiState {
    data object Loading : ConversationUiState
    data object NotFound : ConversationUiState
    /** The conversation exists but this Velin version cannot open it yet. */
    data object Unsupported : ConversationUiState
    /** Loading failed, for example offline; retrying can help. */
    data object Failed : ConversationUiState
    data class Loaded(
        val conversation: ConversationUi,
        /** Messages whose remote content the user allowed for this view. */
        val remoteContentAllowed: Set<String> = emptySet(),
    ) : ConversationUiState
}

sealed interface ConversationEvent {
    data object Back : ConversationEvent
    data object Archive : ConversationEvent
    data object Trash : ConversationEvent
    data object MarkUnread : ConversationEvent
    data object Move : ConversationEvent
    data object Label : ConversationEvent
    data object ReportSpam : ConversationEvent
    data class Reply(val messageId: String) : ConversationEvent
    data class ReplyAll(val messageId: String) : ConversationEvent
    data class Forward(val messageId: String) : ConversationEvent
    data class EditDraft(val messageId: String) : ConversationEvent
    data class ShowRemoteContent(val messageId: String) : ConversationEvent
    data class OpenAttachment(val messageId: String, val attachment: AttachmentUi) : ConversationEvent
    data class OpenLink(val uri: Uri) : ConversationEvent
    data class RetryBody(val messageId: String) : ConversationEvent
    data class CancelScheduledSend(val messageId: String) : ConversationEvent
}

/**
 * Conversation detail pane. The newest message starts expanded; older ones collapse to a header
 * row. [showBack] is false when the list pane is visible beside it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onEvent: (ConversationEvent) -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    /** Loads inline image bytes by attachment id. Called off the main thread. */
    loadAttachmentImage: (attachmentId: String) -> InlineImageData? = { null },
    /** Set when the conversation covers the list; beside the list, the list shows messages. */
    snackbarHostState: SnackbarHostState? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val toolbarScrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(exitDirection = FloatingToolbarExitDirection.Bottom)
    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(toolbarScrollBehavior),
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { onEvent(ConversationEvent.Back) }) {
                            Icon(painterResource(VelinIcons.ArrowBack), stringResource(R.string.action_back))
                        }
                    }
                },
                actions = { if (state is ConversationUiState.Loaded) ConversationActions(state.conversation, onEvent) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ConversationUiState.Loading -> LoadingPane(stringResource(R.string.reader_loading))
                ConversationUiState.NotFound -> StatusMessage(
                    icon = VelinIcons.Mail,
                    title = stringResource(R.string.reader_not_found),
                )
                ConversationUiState.Unsupported -> StatusMessage(
                    icon = VelinIcons.Mail,
                    title = stringResource(R.string.reader_unsupported),
                )
                ConversationUiState.Failed -> StatusMessage(
                    icon = VelinIcons.CloudOff,
                    title = stringResource(R.string.reader_failed),
                )
                is ConversationUiState.Loaded -> {
                    MessageList(state, onEvent, loadAttachmentImage)
                    val latest = state.conversation.messages.lastOrNull { !it.isDraft }
                    if (latest != null) {
                        ReplyToolbar(
                            messageId = latest.id,
                            canReplyAll = latest.to.size + latest.cc.size > 1,
                            onEvent = onEvent,
                            scrollBehavior = toolbarScrollBehavior,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = -ScreenOffset),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationActions(conversation: ConversationUi, onEvent: (ConversationEvent) -> Unit) {
    if (conversation.folderKind != FolderKind.Archive) {
        IconButton(onClick = { onEvent(ConversationEvent.Archive) }) {
            Icon(painterResource(VelinIcons.Archive), stringResource(R.string.action_archive))
        }
    }
    IconButton(onClick = { onEvent(ConversationEvent.Trash) }) {
        Icon(painterResource(VelinIcons.Delete), stringResource(R.string.action_trash))
    }
    IconButton(onClick = { onEvent(ConversationEvent.MarkUnread) }) {
        Icon(painterResource(VelinIcons.MarkUnread), stringResource(R.string.action_mark_unread))
    }
    OverflowMenu(
        listOf(
            OverflowAction(stringResource(R.string.action_move), VelinIcons.Move) { onEvent(ConversationEvent.Move) },
            OverflowAction(stringResource(R.string.action_label), VelinIcons.Label) { onEvent(ConversationEvent.Label) },
            OverflowAction(stringResource(R.string.action_report_spam), VelinIcons.Report) { onEvent(ConversationEvent.ReportSpam) },
        ),
    )
}

@Composable
private fun MessageList(
    state: ConversationUiState.Loaded,
    onEvent: (ConversationEvent) -> Unit,
    loadAttachmentImage: (String) -> InlineImageData?,
) {
    val conversation = state.conversation
    val listState = rememberLazyListState()
    val lastId = conversation.messages.lastOrNull()?.id
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Space for the floating reply toolbar.
        contentPadding = PaddingValues(bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "subject") { SubjectHeader(conversation) }
        items(conversation.messages, key = { it.id }) { message ->
            MessageCard(
                message = message,
                initiallyExpanded = message.id == lastId || message.unread || message.isDraft,
                remoteContentAllowed = message.id in state.remoteContentAllowed,
                onEvent = onEvent,
                loadAttachmentImage = loadAttachmentImage,
                modifier = Modifier
                    .widthIn(max = READING_WIDTH)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectHeader(conversation: ConversationUi) {
    Column(
        modifier = Modifier
            .widthIn(max = READING_WIDTH)
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = conversation.subject.ifBlank { stringResource(R.string.mail_no_subject) },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (conversation.labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                conversation.labels.forEach { LabelChip(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MessageCard(
    message: MessageUi,
    initiallyExpanded: Boolean,
    remoteContentAllowed: Boolean,
    onEvent: (ConversationEvent) -> Unit,
    loadAttachmentImage: (String) -> InlineImageData?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(initiallyExpanded) }
    val expandedLabel = stringResource(R.string.reader_expanded)
    val collapsedLabel = stringResource(R.string.reader_collapsed)
    Surface(
        // No animateContentSize here: AnimatedVisibility below animates expanding, and a second size
        // animation kept a stale height in the two-pane layout when the WebView body grew after loading.
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            MessageHeader(
                message = message,
                expanded = expanded,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel },
            )
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    message.banners.forEach { banner ->
                        MailBanner(banner, message.id, onEvent, Modifier.padding(bottom = 12.dp))
                    }
                    when (val body = message.body) {
                        BodyUi.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                        BodyUi.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.reader_body_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onEvent(ConversationEvent.RetryBody(message.id)) }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                        is BodyUi.Loaded -> MailBody(
                            html = body.html,
                            allowRemoteContent = remoteContentAllowed,
                            onLinkClick = { onEvent(ConversationEvent.OpenLink(it)) },
                            // Unknown placeholders resolve to nothing: never guess an attachment.
                            loadInlineImage = { url -> body.inlineImages[url]?.let(loadAttachmentImage) },
                        )
                    }
                    if (message.attachments.isNotEmpty()) {
                        Attachments(message, onEvent, Modifier.padding(top = 12.dp))
                    }
                    if (message.isDraft) {
                        FilledTonalButton(
                            onClick = { onEvent(ConversationEvent.EditDraft(message.id)) },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(stringResource(R.string.reader_edit_draft)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageHeader(message: MessageUi, expanded: Boolean, modifier: Modifier = Modifier) {
    var showDetails by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SenderAvatar(name = message.sender.name, address = message.sender.address)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.sender.name.ifBlank { message.sender.address },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (message.confidential) {
                        Icon(
                            painter = painterResource(VelinIcons.Lock),
                            contentDescription = stringResource(R.string.mail_confidential),
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = if (expanded) recipientsLine(message) else message.sender.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatListDate(message.sentAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                IconButton(onClick = { showDetails = !showDetails }) {
                    Icon(
                        painterResource(if (showDetails) VelinIcons.ExpandLess else VelinIcons.ExpandMore),
                        stringResource(if (showDetails) R.string.reader_hide_details else R.string.reader_show_details),
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded && showDetails) { HeaderDetails(message) }
    }
}

@Composable
private fun recipientsLine(message: MessageUi): String {
    val names = (message.to + message.cc).joinToString { it.name.ifBlank { it.address } }
    return stringResource(R.string.reader_to_line, names)
}

@Composable
private fun HeaderDetails(message: MessageUi) {
    val zone = ZoneId.systemDefault()
    val full = remember(message.sentAt) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(message.sentAt.atZone(zone))
    }
    Column(Modifier.padding(start = 52.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailLine(stringResource(R.string.reader_from), listOf(message.sender))
        DetailLine(stringResource(R.string.reader_to), message.to)
        DetailLine(stringResource(R.string.reader_cc), message.cc)
        DetailLine(stringResource(R.string.reader_bcc), message.bcc)
        DetailLine(stringResource(R.string.reader_reply_to), message.replyTo)
        Text(full, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(if (message.confidential) R.string.mail_confidential else R.string.reader_not_confidential),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailLine(label: String, recipients: List<RecipientUi>) {
    if (recipients.isEmpty()) return
    Text(
        text = label + " " + recipients.joinToString { if (it.name.isBlank()) it.address else "${it.name} <${it.address}>" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MailBanner(banner: MailBannerUi, messageId: String, onEvent: (ConversationEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    data class Spec(val icon: Int, val text: String, val container: Color, val content: Color, val action: Pair<String, ConversationEvent>?)
    val spec = when (banner) {
        MailBannerUi.Phishing -> Spec(
            VelinIcons.Phishing, stringResource(R.string.banner_phishing), colors.errorContainer, colors.onErrorContainer, null,
        )
        MailBannerUi.AuthenticationFailed -> Spec(
            VelinIcons.Warning, stringResource(R.string.banner_auth_failed), colors.errorContainer, colors.onErrorContainer, null,
        )
        MailBannerUi.AuthenticationUncertain -> Spec(
            VelinIcons.Warning, stringResource(R.string.banner_auth_uncertain), colors.tertiaryContainer, colors.onTertiaryContainer, null,
        )
        MailBannerUi.SenderKeyMismatch -> Spec(
            VelinIcons.Key, stringResource(R.string.banner_sender_key_mismatch), colors.errorContainer, colors.onErrorContainer, null,
        )
        MailBannerUi.DeprecatedSenderKey -> Spec(
            VelinIcons.Key, stringResource(R.string.banner_deprecated_key), colors.tertiaryContainer, colors.onTertiaryContainer, null,
        )
        MailBannerUi.ExternalContentBlocked -> Spec(
            VelinIcons.Image, stringResource(R.string.banner_remote_blocked), colors.secondaryContainer, colors.onSecondaryContainer,
            stringResource(R.string.action_show) to ConversationEvent.ShowRemoteContent(messageId),
        )
        is MailBannerUi.ScheduledSend -> Spec(
            VelinIcons.ScheduleSend,
            stringResource(
                R.string.banner_scheduled,
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(banner.sendAt.atZone(ZoneId.systemDefault())),
            ),
            colors.secondaryContainer, colors.onSecondaryContainer,
            stringResource(R.string.action_cancel_send) to ConversationEvent.CancelScheduledSend(messageId),
        )
    }
    Surface(color = spec.container, contentColor = spec.content, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(spec.icon), contentDescription = null)
            Text(spec.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            spec.action?.let { (label, event) ->
                TextButton(onClick = { onEvent(event) }) { Text(label, color = spec.content) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Attachments(message: MessageUi, onEvent: (ConversationEvent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        message.attachments.forEach { attachment ->
            AssistChip(
                onClick = { onEvent(ConversationEvent.OpenAttachment(message.id, attachment)) },
                label = {
                    Text(
                        "${attachment.name} · ${Formatter.formatShortFileSize(context, attachment.sizeBytes)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(painterResource(VelinIcons.AttachFile), contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReplyToolbar(
    messageId: String,
    canReplyAll: Boolean,
    onEvent: (ConversationEvent) -> Unit,
    scrollBehavior: FloatingToolbarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconButton(onClick = { onEvent(ConversationEvent.Reply(messageId)) }) {
            Icon(painterResource(VelinIcons.Reply), stringResource(R.string.action_reply))
        }
        if (canReplyAll) {
            IconButton(onClick = { onEvent(ConversationEvent.ReplyAll(messageId)) }) {
                Icon(painterResource(VelinIcons.ReplyAll), stringResource(R.string.action_reply_all))
            }
        }
        IconButton(onClick = { onEvent(ConversationEvent.Forward(messageId)) }) {
            Icon(painterResource(VelinIcons.Forward), stringResource(R.string.action_forward))
        }
    }
}

private val READING_WIDTH = 840.dp
