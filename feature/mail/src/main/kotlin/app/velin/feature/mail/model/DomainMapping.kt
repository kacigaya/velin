package app.velin.feature.mail.model

import app.velin.core.domain.Address
import app.velin.core.domain.Attachment
import app.velin.core.domain.BodyState
import app.velin.core.domain.Conversation
import app.velin.core.domain.EntityId
import app.velin.core.domain.ElementId
import app.velin.core.domain.Folder
import app.velin.core.domain.Label
import app.velin.core.domain.ListId
import app.velin.core.domain.MailFinding
import app.velin.core.domain.MailMessage
import app.velin.core.domain.MailSummary
import app.velin.core.domain.ReplyType
import kotlinx.collections.immutable.toImmutableList
import app.velin.core.domain.FolderKind as DomainFolderKind

// Domain → UI mapping. UI ids are "list/element" strings so they fit navigation keys and
// saved state; Tuta element and list ids never contain '/'.

internal fun EntityId.toUiId(): String = "${list.value}/${element.value}"

internal fun entityIdOf(uiId: String): EntityId {
    val (list, element) = uiId.split('/', limit = 2).also { require(it.size == 2) { "Malformed id" } }
    return EntityId(ListId(list), ElementId(element))
}

internal fun Folder.toUi() = FolderUi(
    id = id.value.toUiId(),
    name = name,
    kind = kind.toUi(),
    unreadCount = unreadCount,
    depth = depth,
)

internal fun DomainFolderKind.toUi(): FolderKind = when (this) {
    DomainFolderKind.Inbox -> FolderKind.Inbox
    DomainFolderKind.Drafts -> FolderKind.Drafts
    DomainFolderKind.Sent -> FolderKind.Sent
    DomainFolderKind.Scheduled -> FolderKind.Scheduled
    DomainFolderKind.Archive -> FolderKind.Archive
    DomainFolderKind.Spam -> FolderKind.Spam
    DomainFolderKind.Trash -> FolderKind.Trash
    DomainFolderKind.Custom -> FolderKind.Custom
}

internal fun Label.toUi() = LabelUi(id.value.toUiId(), name, colorHex)

internal fun Address.toUi() = RecipientUi(name = name, address = address)

internal fun MailSummary.toUi(): MailSummaryUi {
    val first = heading.firstOrNull()
    return MailSummaryUi(
        id = id.value.toUiId(),
        conversationId = conversationId.value.toUiId(),
        // Sent mail lists several recipients; show names, fall back to addresses.
        heading = heading.joinToString { it.name.ifBlank { it.address } },
        headingAddress = first?.address.orEmpty(),
        subject = subject,
        receivedAt = receivedAt,
        unread = unread,
        hasAttachments = hasAttachments,
        confidential = confidential,
        replyState = when (replyType) {
            ReplyType.None -> ReplyState.None
            ReplyType.Replied -> ReplyState.Replied
            ReplyType.Forwarded -> ReplyState.Forwarded
            ReplyType.RepliedAndForwarded -> ReplyState.RepliedAndForwarded
        },
        labels = labels.map { it.toUi() }.toImmutableList(),
        conversationSize = conversationSize,
    )
}

internal fun Attachment.toUi() = AttachmentUi(id.value.toUiId(), name, mimeType, sizeBytes)

internal fun MailMessage.toUi(): MessageUi {
    val banners = buildList {
        when (finding) {
            MailFinding.Phishing -> add(MailBannerUi.Phishing)
            MailFinding.AuthenticationFailed -> add(MailBannerUi.AuthenticationFailed)
            MailFinding.AuthenticationUncertain -> add(MailBannerUi.AuthenticationUncertain)
            MailFinding.DeprecatedSenderKey -> add(MailBannerUi.DeprecatedSenderKey)
            null -> Unit
        }
        val loaded = body as? BodyState.Loaded
        if (loaded != null && loaded.externalContentPresent && loaded.externalContentBlocked) add(MailBannerUi.ExternalContentBlocked)
        scheduledSendAt?.let { add(MailBannerUi.ScheduledSend(it)) }
    }
    return MessageUi(
        id = id.value.toUiId(),
        sender = from.toUi(),
        to = to.map { it.toUi() }.toImmutableList(),
        cc = cc.map { it.toUi() }.toImmutableList(),
        bcc = bcc.map { it.toUi() }.toImmutableList(),
        replyTo = replyTo.map { it.toUi() }.toImmutableList(),
        sentAt = sentAt,
        confidential = confidential,
        unread = unread,
        isDraft = isDraft,
        body = when (val state = body) {
            BodyState.Loading -> BodyUi.Loading
            is BodyState.Loaded -> BodyUi.Loaded(state.html, state.inlineImages.mapValues { it.value.value.toUiId() })
            is BodyState.Failed -> BodyUi.Failed
        },
        attachments = attachments
            // Hide only images the body shows. Some senders give every file a Content-ID, so a
            // Content-ID alone does not make a file inline. Until the body loads, hide them to avoid a flash.
            .filter { attachment ->
                when (val state = body) {
                    is BodyState.Loaded -> attachment.id !in state.inlineImages.values
                    BodyState.Loading -> attachment.contentId == null
                    is BodyState.Failed -> true
                }
            }
            .map { it.toUi() }
            .toImmutableList(),
        banners = banners.toImmutableList(),
    )
}

internal fun Conversation.toUi() = ConversationUi(
    id = id.value.toUiId(),
    subject = subject,
    labels = labels.map { it.toUi() }.toImmutableList(),
    folderKind = folderKind?.toUi(),
    messages = messages.map { it.toUi() }.toImmutableList(),
)
