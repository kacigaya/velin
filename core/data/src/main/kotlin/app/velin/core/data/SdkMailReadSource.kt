package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.sdk.*
import app.velin.core.html.DraftHtml
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal interface SessionReadAccess {
    suspend fun <T> read(account: AccountId, operation: suspend (SdkMailReader) -> Outcome<T>): Outcome<T>
}

/** Session adapter. Sender authentication remains unverified. */
internal class SdkMailReadSource(private val sessions: SessionReadAccess) : MailReadSource {
    override suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String> = read(account) {
        it.recipientFingerprint(address.trim().lowercase(java.util.Locale.ROOT))
    }
    override suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit> = read(account) {
        it.verifyRecipient(address.trim().lowercase(java.util.Locale.ROOT), fingerprint)
    }
    override suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit> = read(account) {
        it.untrustRecipient(address.trim().lowercase(java.util.Locale.ROOT))
    }
    override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> = read(account) { sdk ->
        sdk.sendDraft(id.value.value.readId())
    }
    override suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient> {
        // RecipientsModel cleans addresses before resolving their type.
        val cleaned = address.trim().lowercase(java.util.Locale.ROOT)
        if (cleaned.length !in 3..254 || cleaned.count { it == '@' } != 1 ||
            cleaned.startsWith('@') || cleaned.endsWith('@') || cleaned.any { it.isWhitespace() || it.isISOControl() }) {
            return Outcome.Failed(Failure.RecipientResolution(RecipientResolutionReason.InvalidAddress))
        }
        return read(account) { sdk ->
            val resolved = sdk.resolveRecipient(cleaned)
            Recipient(Address(cleaned), if (resolved.internal) RecipientType.Internal else RecipientType.External,
                keyState = when (resolved.keyState) {
                    ReadRecipientKeyState.NoEntry -> RecipientKeyState.NoEntry
                    ReadRecipientKeyState.TrustStoreUnsupported -> RecipientKeyState.TrustStoreUnsupported
                    ReadRecipientKeyState.Mismatch -> RecipientKeyState.Mismatch
                    ReadRecipientKeyState.VerifiedTofu -> RecipientKeyState.VerifiedTofu
                    ReadRecipientKeyState.VerifiedManual -> RecipientKeyState.VerifiedManual
                })
        }
    }
    override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = read(account) { sdk ->
        if (draft.scheduledSendAt != null) throw SdkException(SdkFailure.DraftUnsupported)
        require(draft.sender.enabled && (draft.to + draft.cc + draft.bcc).none { it.invalid })
        val html = draft.htmlBody?.takeIf { DraftHtml.toPlainText(it) == draft.plainText }
            ?: DraftHtml.fromPlainText(draft.plainText)
        val existingId = draft.id?.value?.value?.readId()
        val input = WriteDraft(existingId, ReadAddress(draft.sender.address.address, draft.sender.address.name),
            draft.to.map { ReadAddress(it.address.address, it.address.name) }, draft.cc.map { ReadAddress(it.address.address, it.address.name) },
            draft.bcc.map { ReadAddress(it.address.address, it.address.name) }, draft.subject, html, draft.confidential,
            draft.attachments.map { it.id.value.readId() })
        val id = sdk.saveDraft(input)
        require(existingId == null || id == existingId)
        DraftId(MailId(id.domain()))
    }
    override suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft> = read(account) { sdk ->
        val mail = sdk.loadDraft(id.value.readId())
        require(mail.id.domain() == id.value && mail.draft)
        val sender = sdk.senders().singleOrNull { it.address == mail.sender.address }
        Draft(DraftId(id), SenderAlias(mail.sender.address(), enabled = sender != null),
            mail.to.map { Recipient(it.address(), RecipientType.Unknown) }, mail.cc.map { Recipient(it.address(), RecipientType.Unknown) },
            mail.bcc.map { Recipient(it.address(), RecipientType.Unknown) }, mail.subject, DraftHtml.toPlainText(mail.body),
            mail.confidential, mail.attachments.map { it.domainAttachment() }, mail.scheduledMillis?.let(Instant::ofEpochMilli), htmlBody = mail.body)
    }
    override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> = read(account) { sdk ->
        ids.distinct().chunked(50).forEach { batch -> sdk.setUnread(batch.map { it.value.readId() }, unread) }
    }
    override suspend fun currentFolders(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, FolderId>> = read(account) { sdk ->
        val folders = sdk.folders().filter { it.kind != 7L && it.kind != 8L }
        ids.distinct().associateWith { id ->
            val mail = sdk.summary(id.value.readId())
            require(mail.id == id.value.readId())
            FolderId(requireNotNull(folders.singleOrNull { it.id in mail.sets }).id.domain())
        }
    }
    override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> = read(account) { sdk ->
        val folder = sdk.folders().singleOrNull { it.id.domain() == target.value && it.kind != 7L && it.kind != 8L }
            ?: return@read null
        batches(ids).forEach { sdk.move(it, folder) }
    }
    override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> = read(account) { sdk ->
        val sets = sdk.folders()
        val available = sets.filter { it.kind == 8L }.map { it.id }.toSet()
        val desired = labels.distinct().map { it.value.readId() }.toSet()
        require(desired.size <= 5 && available.containsAll(desired))
        // Preflight every mail before writing. Label replacement is per mail, never per conversation.
        val changes = ids.distinct().map { id ->
            val mail = sdk.summary(id.value.readId())
            require(mail.id == id.value.readId())
            val existing = mail.sets.filter { it in available }.toSet()
            Triple(id.value.readId(), (desired - existing).toList(), (existing - desired).toList())
        }
        changes.groupBy { Triple(it.first.list, it.second, it.third) }.forEach { (_, group) ->
            val added = group.first().second
            val removed = group.first().third
            if (added.isNotEmpty() || removed.isNotEmpty()) group.chunked(50).forEach { batch ->
                sdk.applyLabels(batch.map { it.first }, added, removed)
            }
        }
    }
    override suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> = read(account) { sdk ->
        val folders = sdk.folders().filter { it.kind != 7L && it.kind != 8L }
        val locations = ids.distinct().map { id ->
            val mail = sdk.summary(id.value.readId())
            require(mail.id == id.value.readId())
            val folder = requireNotNull(folders.singleOrNull { it.id in mail.sets })
            require(folder.kind == 3L || folder.kind == 5L)
            id.value.readId() to folder.id
        }
        locations.groupBy { it.second }.forEach { (folder, mails) ->
            mails.map { it.first }.groupBy { it.list }.values.forEach { bag ->
                bag.chunked(50).forEach { sdk.delete(it, folder) }
            }
        }
    }
    private fun batches(ids: List<MailId>): List<List<ReadId>> = ids.distinct().map { it.value.readId() }
        .groupBy { it.list }.values.flatMap { it.chunked(50) }

    override suspend fun mailbox(account: AccountId): Outcome<MailboxSnapshot> = read(account) { sdk ->
        val sets = sdk.folders()
        val labels = sets.filter { it.kind == 8L }.map { Label(LabelId(it.id.domain()), it.name, it.color) }
        val folders = sets.filter { it.kind != 8L && it.kind != 7L }.map {
            Folder(FolderId(it.id.domain()), it.name, folderKind(it.kind), unreadOrZero(sdk, it),
                it.parent?.let { p -> FolderId(p.domain()) })
        }
        MailboxSnapshot(folders, labels, sdk.senders().map { SenderAlias(it.address(), enabled = true) },
            sets.filter { it.kind != 7L }.associate { it.entries to it.id.domain() })
    }
    override suspend fun locate(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, LocatedMail?>> = read(account) { sdk ->
        val sets = sdk.folders()
        val folders = sets.filter { it.kind != 7L && it.kind != 8L }
        ids.distinct().associateWith { id ->
            val mail = try { sdk.summary(id.value.readId()) }
            catch (e: SdkException) { if (e.failure == SdkFailure.NotFound) return@associateWith null else throw e }
            require(mail.id == id.value.readId())
            LocatedMail(summary(mail, sets), folders.singleOrNull { it.id in mail.sets }?.let { FolderId(it.id.domain()) })
        }
    }
    /**
     * Unread counts come over the event bus (MailModel.ts:455-478); CounterService GET returns 400
     * for mail folders. Counts stay best effort while the connection starts or reconnects.
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
                SdkFailure.SecureExternalUnsupported, SdkFailure.AttachmentsUnsupported, SdkFailure.DraftUnsupported -> Failure.Unsupported
                SdkFailure.IdentityTrustStorage -> Failure.RecipientResolution(RecipientResolutionReason.TrustStorageUnavailable)
                SdkFailure.SendUnsupported -> Failure.Unsupported.Operation(when (e.reason) {
                    1 -> UnsupportedReason.ScheduledSend
                    2 -> UnsupportedReason.NonConfidentialSend
                    3 -> UnsupportedReason.Attachments
                    4 -> UnsupportedReason.ReplyOrForward
                    5 -> UnsupportedReason.IdentityTrustStore
                    6 -> UnsupportedReason.ExternalRecipient
                    7 -> UnsupportedReason.CalendarMail
                    8 -> UnsupportedReason.CustomReplyTo
                    else -> UnsupportedReason.Unspecified
                })
                SdkFailure.SendBlocked -> when (e.reason) {
                    0 -> Failure.SendBlocked(SendBlockReason.RecipientNotFound, e.recipientIndices)
                    1 -> Failure.SendBlocked(SendBlockReason.KeyMismatch, e.recipientIndices)
                    else -> Failure.Protocol(502)
                }
                SdkFailure.RecipientRateLimited -> Failure.RecipientResolution(RecipientResolutionReason.RateLimited)
                SdkFailure.RecipientInvalidKey -> Failure.RecipientResolution(RecipientResolutionReason.InvalidKeyData)
                SdkFailure.Network -> Failure.Offline
                SdkFailure.NotFound -> Failure.Protocol(404)
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
