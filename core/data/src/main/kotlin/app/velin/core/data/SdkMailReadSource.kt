package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.sdk.*
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal interface SessionReadAccess {
    suspend fun <T> read(account: AccountId, operation: suspend (SdkMailReader) -> Outcome<T>): Outcome<T>
}

/** Read-only adapter. Sender authentication remains unverified. */
internal class SdkMailReadSource(private val sessions: SessionReadAccess) : MailReadSource {
    override suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot> = read(account) { sdk ->
        val sets = sdk.folders()
        val labels = sets.filter { it.kind == 8L }.map { Label(LabelId(it.id.domain()), it.name, it.color) }
        val folders = sets.filter { it.kind != 8L && it.kind != 7L }.map {
            Folder(FolderId(it.id.domain()), it.name, folderKind(it.kind), unreadOrZero(sdk, it),
                it.parent?.let { p -> FolderId(p.domain()) })
        }
        MailboxSnapshot(folders, labels, emptyList())
    }
    /**
     * Upstream receives unread counts over the event bus (WebsocketCounterData, MailModel.ts:455-478),
     * not from CounterService GET, which the server rejects with 400 for mail folders.
     * Counts remain best effort during connection setup and reconnect.
     */
    private suspend fun unreadOrZero(sdk: SdkMailReader, folder: ReadFolder): Int = try {
        sdk.unread(folder).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    } catch (e: CancellationException) { throw e
    } catch (_: SdkException) { 0 }

    override suspend fun page(account: AccountId, folder: FolderId, cursor: PageCursor?): Outcome<MailPage> = read(account) { sdk ->
        val sets = sdk.folders()
        val target = sets.singleOrNull { it.id.domain() == folder.value } ?: return@read null
        val entries = sdk.entries(target.entries, cursor?.value?.value)
        require(entries.size <= 50 && entries.map { it.cursor }.distinct().size == entries.size)
        val summaries = entries.map { entry -> summary(sdk.summary(entry.mail), sets) }
        MailPage(summaries, if (entries.size == 50) PageCursor(ElementId(entries.last().cursor)) else null)
    }
    override suspend fun conversation(account: AccountId, id: ConversationId): Outcome<List<MailSummary>> = read(account) { sdk ->
        val sets = sdk.folders()
        sdk.conversation(id.value.readId()).map { summary(sdk.summary(it), sets).copy(conversationId = id) }
            .distinctBy { it.id }.sortedBy { it.receivedAt }
    }
    override suspend fun message(account: AccountId, id: MailId): Outcome<MailMessage> = read(account) { sdk ->
        val mail = sdk.message(id.value.readId())
        require(mail.id.domain() == id.value)
        MailMessage(id, mail.sender.address(), mail.to.map { it.address() }, mail.cc.map { it.address() },
            mail.bcc.map { it.address() }, mail.replyTo.map { it.address() }, mail.subject,
            Instant.ofEpochMilli(mail.sentMillis), mail.confidential, mail.unread, mail.draft,
            BodyState.Loading, mail.attachments.map { it.domainAttachment() }, MailAuthentication.Unknown,
            when {
                mail.phishing == 1L -> MailFinding.Phishing
                mail.mailAuthStatus in listOf(1L, 3L, 4L) -> MailFinding.AuthenticationFailed
                mail.encryptionAuth == 5L -> MailFinding.DeprecatedSenderKey
                // MailViewerViewModel.ts:427-442: only SOFT_FAIL (2) is "uncertain". Missing sender-key
                // verification is Velin's own gap, so it must not put a warning on every mail.
                mail.mailAuthStatus == 2L -> MailFinding.AuthenticationUncertain
                else -> null
            }, mail.scheduledMillis?.let(Instant::ofEpochMilli))
    }
    override suspend fun body(account: AccountId, id: MailId): Outcome<String> = read(account) { sdk ->
        sdk.message(id.value.readId()).also { require(it.id.domain() == id.value) }.body
    }
    override suspend fun attachment(account: AccountId, id: AttachmentId): Outcome<AttachmentContent> = read(account) { sdk ->
        val content = sdk.attachment(id.value.readId())
        require(content.metadata.id.domain() == id.value && content.metadata.size == content.bytes.size.toLong())
        AttachmentContent(content.metadata.domainAttachment(), content.bytes)
    }

    private suspend fun <T : Any> read(account: AccountId, operation: suspend (SdkMailReader) -> T?): Outcome<T> =
        sessions.read(account) { sdk ->
            try { operation(sdk)?.let { Outcome.Success(it) } ?: Outcome.Failed(Failure.Protocol(404)) }
            catch (e: CancellationException) { throw e }
            catch (e: SdkException) { Outcome.Failed(when (e.failure) {
                SdkFailure.SecureExternalUnsupported, SdkFailure.AttachmentsUnsupported -> Failure.Unsupported
                SdkFailure.Network -> Failure.Offline
                SdkFailure.InvalidAccessToken -> Failure.Unauthorized
                SdkFailure.InvalidSoftwareVersion -> Failure.InvalidSoftwareVersion
                SdkFailure.InvalidKey -> Failure.Decryption
                else -> Failure.Protocol(502, e.diagnostic)
            }) }
            catch (_: IllegalArgumentException) { Outcome.Failed(Failure.Protocol(502)) }
            catch (_: IllegalStateException) { Outcome.Failed(Failure.Protocol(502)) }
        }
}

// folder_system.rs:14-23; entities/tutanota/Utils.ts:16-28 adds labels and scheduled.
internal fun folderKind(kind: Long): FolderKind = when (kind) {
    0L, 9L -> FolderKind.Custom
    1L -> FolderKind.Inbox
    2L -> FolderKind.Sent
    3L -> FolderKind.Trash
    4L -> FolderKind.Archive
    5L -> FolderKind.Spam
    6L -> FolderKind.Drafts
    10L -> FolderKind.Scheduled
    else -> throw IllegalArgumentException("Unknown folder kind")
}
internal fun ReadId.domain() = EntityId(ListId(list), ElementId(element))
internal fun summary(mail: ReadSummary, sets: List<ReadFolder>): MailSummary = MailSummary(
    MailId(mail.id.domain()), ConversationId(mail.conversationEntry.domain()),
    listOf(Address(mail.senderAddress, mail.senderName)), mail.subject, Instant.ofEpochMilli(mail.receivedMillis),
    mail.unread, mail.hasAttachments, mail.confidential, when (mail.replyType) {
        0L -> ReplyType.None; 1L -> ReplyType.Replied; 2L -> ReplyType.Forwarded; 3L -> ReplyType.RepliedAndForwarded
        else -> throw IllegalArgumentException("Unknown reply type")
    }, sets.filter { it.kind == 8L && it.id in mail.sets }.map { Label(LabelId(it.id.domain()), it.name, it.color) }, 1)

private fun EntityId.readId() = ReadId(list.value, element.value)
private fun ReadAddress.address() = Address(address, name)

private fun ReadAttachment.domainAttachment() = Attachment(AttachmentId(id.domain()), name, mimeType, size, cid)
