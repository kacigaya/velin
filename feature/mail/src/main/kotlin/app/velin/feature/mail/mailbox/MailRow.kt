package app.velin.feature.mail.mailbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.velin.core.designsystem.component.SenderAvatar
import app.velin.core.designsystem.icon.VelinIcons
import app.velin.feature.mail.R
import app.velin.feature.mail.model.LabelUi
import app.velin.feature.mail.model.MailSummaryUi
import app.velin.feature.mail.model.ReplyState

/** Accessibility actions that mirror the swipe gestures, so they never depend on gestures alone. */
data class MailRowActions(
    val onArchive: () -> Unit,
    val onTrash: () -> Unit,
    val onToggleRead: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailRow(
    mail: MailSummaryUi,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    actions: MailRowActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (selected) colors.secondaryContainer else Color.Transparent
    val description = mailDescription(mail)
    val archiveLabel = stringResource(R.string.action_archive)
    val trashLabel = stringResource(R.string.action_trash)
    val readLabel = stringResource(if (mail.unread) R.string.action_mark_read else R.string.action_mark_unread)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
                customActions = listOf(
                    CustomAccessibilityAction(archiveLabel) { actions.onArchive(); true },
                    CustomAccessibilityAction(trashLabel) { actions.onTrash(); true },
                    CustomAccessibilityAction(readLabel) { actions.onToggleRead(); true },
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SelectableAvatar(mail = mail, selected = selected, selectionMode = selectionMode)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.heading.ifBlank { mail.headingAddress },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (mail.unread) FontWeight.Bold else FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mail.conversationSize > 1) {
                    Text(
                        text = mail.conversationSize.toString(),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = remember(mail.receivedAt) { formatListDate(mail.receivedAt) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (mail.unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (mail.unread) colors.primary else colors.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.subject.ifBlank { stringResource(R.string.mail_no_subject) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (mail.unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (mail.unread) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusIcons(mail)
                if (mail.unread) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(8.dp)
                            .background(colors.primary, CircleShape),
                    )
                }
            }
            if (mail.labels.isNotEmpty()) {
                LabelRow(mail.labels, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SelectableAvatar(mail: MailSummaryUi, selected: Boolean, selectionMode: Boolean) {
    // Avatar flips to a check mark in selection mode, like Pixel apps.
    val scale by animateFloatAsState(if (selectionMode && !selected) 0.9f else 1f, label = "avatarScale")
    AnimatedContent(
        targetState = selected,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
        label = "avatarSelection",
    ) { isSelected ->
        if (isSelected) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(VelinIcons.Check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            SenderAvatar(name = mail.heading, address = mail.headingAddress)
        }
    }
}

@Composable
private fun StatusIcons(mail: MailSummaryUi) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val iconModifier = Modifier
        .padding(start = 4.dp)
        .size(16.dp)
    val replyIcon = when (mail.replyState) {
        ReplyState.None -> null
        ReplyState.Replied -> VelinIcons.Reply
        ReplyState.Forwarded, ReplyState.RepliedAndForwarded -> VelinIcons.Forward
    }
    if (replyIcon != null) Icon(painterResource(replyIcon), null, iconModifier, tint)
    if (mail.confidential) Icon(painterResource(VelinIcons.Lock), null, iconModifier, tint)
    if (mail.hasAttachments) Icon(painterResource(VelinIcons.AttachFile), null, iconModifier, tint)
}

@Composable
private fun LabelRow(labels: List<LabelUi>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.take(MAX_VISIBLE_LABELS).forEach { LabelChip(it) }
        if (labels.size > MAX_VISIBLE_LABELS) {
            Text(
                text = "+${labels.size - MAX_VISIBLE_LABELS}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LabelChip(label: LabelUi, modifier: Modifier = Modifier) {
    val (container, content) = labelColors(label.colorHex)
    Text(
        text = label.name,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * User-chosen label colors are arbitrary, so the text color is picked for contrast instead of
 * taken from the theme. Invalid or missing colors fall back to the tertiary container pair.
 */
@Composable
private fun labelColors(colorHex: String?): Pair<Color, Color> {
    val parsed = colorHex?.let(::parseHexColor)
        ?: return MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    return parsed to contentColorFor(parsed)
}

internal fun parseHexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000 or digits.toLong(16))
}

// WCAG relative luminance threshold where black and white text have equal contrast.
internal fun contentColorFor(background: Color): Color {
    fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val luminance = 0.2126f * channel(background.red) + 0.7152f * channel(background.green) + 0.0722f * channel(background.blue)
    return if (luminance > 0.179f) Color.Black else Color.White
}

@Composable
private fun mailDescription(mail: MailSummaryUi): String {
    val parts = buildList {
        if (mail.unread) add(stringResource(R.string.mail_unread))
        add(mail.heading.ifBlank { mail.headingAddress })
        add(mail.subject.ifBlank { stringResource(R.string.mail_no_subject) })
        add(formatListDate(mail.receivedAt))
        if (mail.conversationSize > 1) add(pluralStringResource(R.plurals.mail_conversation_size, mail.conversationSize, mail.conversationSize))
        when (mail.replyState) {
            ReplyState.None -> Unit
            ReplyState.Replied -> add(stringResource(R.string.mail_replied))
            ReplyState.Forwarded -> add(stringResource(R.string.mail_forwarded))
            ReplyState.RepliedAndForwarded -> add(stringResource(R.string.mail_replied_forwarded))
        }
        if (mail.confidential) add(stringResource(R.string.mail_confidential))
        if (mail.hasAttachments) add(stringResource(R.string.mail_has_attachments))
        mail.labels.forEach { add(it.name) }
    }
    return parts.joinToString(", ")
}

private const val MAX_VISIBLE_LABELS = 2
