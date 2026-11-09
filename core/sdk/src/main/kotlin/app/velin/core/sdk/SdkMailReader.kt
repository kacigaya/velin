package app.velin.core.sdk

import de.tutao.tutasdk.*

/** Velin records keep generated bindings and cryptographic material inside this module. */
data class ReadId(val list: String, val element: String)
data class ReadFolder(val id: ReadId, val name: String, val kind: Long, val parent: ReadId?,
    val entries: String, val ownerGroup: String, val color: String?)
data class ReadEntry(val cursor: String, val mail: ReadId)
data class ReadSummary(val id: ReadId, val conversationEntry: ReadId, val senderAddress: String,
    val senderName: String, val subject: String, val receivedMillis: Long, val unread: Boolean,
    val hasAttachments: Boolean, val confidential: Boolean, val replyType: Long, val sets: List<ReadId>)

data class ReadAttachment(val id: ReadId, val name: String, val mimeType: String, val size: Long, val cid: String?)
data class ReadAttachmentContent(val metadata: ReadAttachment, val bytes: ByteArray)

data class ReadAddress(val address: String, val name: String)
data class ReadMessage(val id: ReadId, val sender: ReadAddress, val to: List<ReadAddress>,
    val cc: List<ReadAddress>, val bcc: List<ReadAddress>, val replyTo: List<ReadAddress>,
    val subject: String, val sentMillis: Long, val confidential: Boolean, val unread: Boolean,
    val draft: Boolean, val phishing: Long, val encryptionAuth: Long?, val body: String,
    val scheduledMillis: Long?, val mailAuthStatus: Long = -1, val attachments: List<ReadAttachment> = emptyList())

data class WriteDraft(val id: ReadId?, val sender: ReadAddress, val to: List<ReadAddress>, val cc: List<ReadAddress>,
    val bcc: List<ReadAddress>, val subject: String, val html: String, val confidential: Boolean, val attachments: List<ReadId>)

interface SdkMailReader {
    suspend fun saveDraft(draft: WriteDraft): ReadId = throw SdkException(SdkFailure.DraftUnsupported)
    suspend fun loadDraft(id: ReadId): ReadMessage = throw SdkException(SdkFailure.DraftUnsupported)
    suspend fun setUnread(ids: List<ReadId>, unread: Boolean): Unit = throw SdkException(SdkFailure.Protocol)
    suspend fun move(ids: List<ReadId>, target: ReadFolder): Unit = throw SdkException(SdkFailure.Protocol)
    suspend fun applyLabels(ids: List<ReadId>, added: List<ReadId>, removed: List<ReadId>): Unit = throw SdkException(SdkFailure.Protocol)
    suspend fun delete(ids: List<ReadId>, folder: ReadId): Unit = throw SdkException(SdkFailure.Protocol)
    suspend fun senders(): List<ReadAddress> = throw SdkException(SdkFailure.Protocol)
    suspend fun folders(): List<ReadFolder>
    suspend fun unread(folder: ReadFolder): Long
    suspend fun entries(list: String, cursor: String?): List<ReadEntry>
    suspend fun summary(id: ReadId): ReadSummary
    suspend fun message(id: ReadId): ReadMessage = throw SdkException(SdkFailure.InvalidKey)
    suspend fun attachment(id: ReadId): ReadAttachmentContent = throw SdkException(SdkFailure.AttachmentsUnsupported)
    suspend fun conversation(id: ReadId): List<ReadId> = throw SdkException(SdkFailure.Protocol)
}

internal class NativeMailReader(private val facade: MailFacade) : SdkMailReader, AutoCloseable {
    override suspend fun saveDraft(draft: WriteDraft): ReadId = api {
        fun ReadAddress.sender() = VelinSender(address, name)
        facade.velinSaveDraft(VelinDraftInput(draft.id?.native(), draft.sender.sender(), draft.to.map { it.sender() },
            draft.cc.map { it.sender() }, draft.bcc.map { it.sender() }, draft.subject, draft.html, draft.confidential,
            draft.attachments.map { it.native() })).readId()
    }
    override suspend fun loadDraft(id: ReadId): ReadMessage = api {
        val content = facade.velinLoadDraft(id.native())
        val mail = content.mail
        val details = content.details
        require(mail.id?.readId() == id && mail.mailDetailsDraft != null)
        fun MailAddress.address() = ReadAddress(address, name)
        ReadMessage(id, mail.sender.address(), details.recipients.toRecipients.map { it.address() },
            details.recipients.ccRecipients.map { it.address() }, details.recipients.bccRecipients.map { it.address() },
            details.replyTos.map { ReadAddress(it.address, it.name) }, mail.subject, 0, mail.confidential, mail.unread,
            true, mail.phishingStatus, mail.encryptionAuthStatus, details.body.compressedText ?: details.body.text.orEmpty(),
            mail.sendAt?.millis(), attachments = content.attachments.map { it.metadata() })
    }
    override suspend fun setUnread(ids: List<ReadId>, unread: Boolean): Unit = api {
        facade.setUnreadStatusForMails(ids.distinct().map { it.native() }, unread)
    }
    override suspend fun move(ids: List<ReadId>, target: ReadFolder): Unit = api {
        // Exact folder IDs are required for undo, including Trash and Archive.
        facade.velinMove(ids.map { it.native() }, target.id.native())
    }
    override suspend fun applyLabels(ids: List<ReadId>, added: List<ReadId>, removed: List<ReadId>): Unit = api {
        facade.velinLabels(ids.map { it.native() }, added.map { it.native() }, removed.map { it.native() })
    }
    override suspend fun delete(ids: List<ReadId>, folder: ReadId): Unit = api {
        facade.velinDelete(ids.map { it.native() }, folder.native())
    }
    private val attachmentParents = LinkedHashMap<ReadId, ReadId>()
    override fun close() { synchronized(attachmentParents) { attachmentParents.clear() }; facade.close() }
    private fun TutanotaFile.metadata(): ReadAttachment {
        if (errors.isNotEmpty() || size < 0) throw SdkException(SdkFailure.InvalidKey)
        return ReadAttachment(requireNotNull(id).readId(), name, mimeType ?: "application/octet-stream", size, cid)
    }
    override suspend fun attachment(id: ReadId): ReadAttachmentContent = api {
        val parent = synchronized(attachmentParents) { attachmentParents[id] }
            ?: throw SdkException(SdkFailure.Protocol)
        val file = facade.velinAttachment(IdTupleGenerated(parent.list, parent.element), IdTupleGenerated(id.list, id.element))
        val metadata = file.metadata()
        require(metadata.id == id && metadata.size <= 20 * 1024 * 1024)
        val bytes = facade.velinAttachmentContent(IdTupleGenerated(parent.list, parent.element), IdTupleGenerated(id.list, id.element))
        require(bytes.size.toLong() == metadata.size)
        ReadAttachmentContent(metadata, bytes)
    }
    override suspend fun senders(): List<ReadAddress> = api {
        facade.velinSenders().map { ReadAddress(it.address, it.name) }
    }
    override suspend fun folders(): List<ReadFolder> = api {
        facade.velinFolders().map { folder ->
            if (folder.errors.isNotEmpty()) throw SdkException(SdkFailure.InvalidKey)
            ReadFolder(requireNotNull(folder.id).readId(), folder.name, folder.folderType,
                folder.parentFolder?.readId(), folder.entries, requireNotNull(folder.ownerGroup), folder.color)
        }
    }
    // EventBusClient.ts:374-381 delivers counts. CounterService GET is not a mailbox snapshot.
    override suspend fun unread(folder: ReadFolder): Long = 0
    override suspend fun entries(list: String, cursor: String?): List<ReadEntry> = api {
        facade.velinEntries(list, cursor).map { ReadEntry(requireNotNull(it.id).elementId, it.mail.readId()) }
    }
    override suspend fun summary(id: ReadId): ReadSummary = api {
        val mail = facade.velinMail(IdTupleGenerated(id.list, id.element))
        if (mail.errors.isNotEmpty() || mail.sender.errors.isNotEmpty()) throw SdkException(SdkFailure.InvalidKey)
        require(mail.id?.readId() == id)
        ReadSummary(requireNotNull(mail.id).readId(), mail.conversationEntry.readId(),
            mail.sender.address, mail.sender.name, mail.subject, mail.receivedDate.also { require(it <= Long.MAX_VALUE.toULong()) }.toLong(),
            mail.unread, mail.attachments.isNotEmpty(), mail.confidential, mail.replyType, mail.sets.map { it.readId() })
    }
    override suspend fun conversation(id: ReadId): List<ReadId> = api {
        val entries = facade.velinConversation(id.list)
        require(entries.any { it.id?.readId() == id })
        entries.mapNotNull { it.mail?.readId() }.distinct()
    }
    override suspend fun message(id: ReadId): ReadMessage = api {
        val mail = facade.velinMail(IdTupleGenerated(id.list, id.element))
        val details = facade.velinDetails(IdTupleGenerated(id.list, id.element))
        if (mail.errors.isNotEmpty() || mail.sender.errors.isNotEmpty() || details.body.errors.isNotEmpty() ||
            details.replyTos.any { it.errors.isNotEmpty() } ||
            (details.recipients.toRecipients + details.recipients.ccRecipients + details.recipients.bccRecipients).any { it.errors.isNotEmpty() }) {
            throw SdkException(SdkFailure.InvalidKey)
        }
        require(mail.id?.readId() == id)
        require(mail.attachments.size <= 100)
        val attachments = mail.attachments.map { fileId ->
            facade.velinAttachment(IdTupleGenerated(id.list, id.element), fileId).metadata().also {
                require(it.id == fileId.readId())
            }
        }
        synchronized(attachmentParents) {
            attachments.forEach { attachmentParents[it.id] = id }
        }
        ReadMessage(id, ReadAddress(mail.sender.address, mail.sender.name),
            details.recipients.toRecipients.map { ReadAddress(it.address, it.name) },
            details.recipients.ccRecipients.map { ReadAddress(it.address, it.name) },
            details.recipients.bccRecipients.map { ReadAddress(it.address, it.name) },
            details.replyTos.map { ReadAddress(it.address, it.name) }, mail.subject,
            details.sentDate.millis(), mail.confidential, mail.unread, mail.mailDetailsDraft != null,
            mail.phishingStatus, mail.encryptionAuthStatus,
            details.body.compressedText ?: details.body.text ?: "", mail.sendAt?.millis(), details.authStatus, attachments)
    }
    private suspend fun <T> api(block: suspend () -> T): T = try { block() }
    catch (e: ApiCallException) { throw apiException(e) }
    catch (_: IllegalArgumentException) { throw SdkException(SdkFailure.Protocol) }
    catch (_: IllegalStateException) { throw SdkException(SdkFailure.Protocol) }
}
private fun IdTupleGenerated.readId() = ReadId(listId, elementId)

private fun ULong.millis(): Long { require(this <= Long.MAX_VALUE.toULong()); return toLong() }

private fun ReadId.native() = IdTupleGenerated(list, element)
