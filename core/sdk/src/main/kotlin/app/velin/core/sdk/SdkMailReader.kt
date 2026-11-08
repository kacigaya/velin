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

data class ReadAddress(val address: String, val name: String)
data class ReadMessage(val id: ReadId, val sender: ReadAddress, val to: List<ReadAddress>,
    val cc: List<ReadAddress>, val bcc: List<ReadAddress>, val replyTo: List<ReadAddress>,
    val subject: String, val sentMillis: Long, val confidential: Boolean, val unread: Boolean,
    val draft: Boolean, val phishing: Long, val encryptionAuth: Long?, val body: String,
    val scheduledMillis: Long?, val mailAuthStatus: Long = -1)

interface SdkMailReader {
    suspend fun folders(): List<ReadFolder>
    suspend fun unread(folder: ReadFolder): Long
    suspend fun entries(list: String, cursor: String?): List<ReadEntry>
    suspend fun summary(id: ReadId): ReadSummary
    suspend fun message(id: ReadId): ReadMessage = throw SdkException(SdkFailure.InvalidKey)
    suspend fun conversation(id: ReadId): List<ReadId> = throw SdkException(SdkFailure.Protocol)
}

internal class NativeMailReader(private val facade: MailFacade) : SdkMailReader, AutoCloseable {
    override fun close() { facade.close() }
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
        // Attachment key providers and downloads are a separate slice. Never export fake metadata.
        if (mail.attachments.isNotEmpty()) throw SdkException(SdkFailure.AttachmentsUnsupported)
        ReadMessage(id, ReadAddress(mail.sender.address, mail.sender.name),
            details.recipients.toRecipients.map { ReadAddress(it.address, it.name) },
            details.recipients.ccRecipients.map { ReadAddress(it.address, it.name) },
            details.recipients.bccRecipients.map { ReadAddress(it.address, it.name) },
            details.replyTos.map { ReadAddress(it.address, it.name) }, mail.subject,
            details.sentDate.millis(), mail.confidential, mail.unread, mail.mailDetailsDraft != null,
            mail.phishingStatus, mail.encryptionAuthStatus,
            details.body.compressedText ?: details.body.text ?: "", mail.sendAt?.millis(), details.authStatus)
    }
    private suspend fun <T> api(block: suspend () -> T): T = try { block() }
    catch (e: ApiCallException) { throw apiException(e) }
    catch (_: IllegalArgumentException) { throw SdkException(SdkFailure.Protocol) }
    catch (_: IllegalStateException) { throw SdkException(SdkFailure.Protocol) }
}
private fun IdTupleGenerated.readId() = ReadId(listId, elementId)

private fun ULong.millis(): Long { require(this <= Long.MAX_VALUE.toULong()); return toLong() }
