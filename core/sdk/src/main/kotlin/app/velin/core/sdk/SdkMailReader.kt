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

interface SdkMailReader {
    suspend fun folders(): List<ReadFolder>
    suspend fun unread(folder: ReadFolder): Long
    suspend fun entries(list: String, cursor: String?): List<ReadEntry>
    suspend fun summary(id: ReadId): ReadSummary
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
    override suspend fun unread(folder: ReadFolder): Long = api {
        facade.velinUnread(folder.ownerGroup, folder.id.element)
    }
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
    private suspend fun <T> api(block: suspend () -> T): T = try { block() }
    catch (e: ApiCallException) { throw SdkException(mapApiFailure(e)) }
    catch (_: IllegalArgumentException) { throw SdkException(SdkFailure.Protocol) }
    catch (_: IllegalStateException) { throw SdkException(SdkFailure.Protocol) }
}
private fun IdTupleGenerated.readId() = ReadId(listId, elementId)
