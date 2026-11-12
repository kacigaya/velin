package app.velin.core.data

import android.util.AtomicFile
import app.velin.core.domain.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Saved mail data for one account. [cursors] are the last applied event batch per group.
 * They are saved in the same write as the data they describe, so a cursor never runs ahead of it.
 */
internal data class OfflineState(
    val cursors: Map<String, String> = emptyMap(),
    val syncedAt: Instant? = null,
    val mailbox: MailboxSnapshot = MailboxSnapshot(emptyList(), emptyList(), emptyList()),
    val pages: Map<FolderId, MailPage> = emptyMap(),
    val messages: List<SavedMessage> = emptyList(),
    val draftRejections: Map<MailId, Set<String>> = emptyMap(),
)
/**
 * [body] is the decrypted HTML before sanitizing. Velin sanitizes it again on each restore.
 * [summary] links the mail to its conversation when no saved page lists it, for example sent mail.
 */
internal data class SavedMessage(val message: MailMessage, val body: String, val summary: MailSummary? = null)

internal interface OfflineStore {
    fun exists(account: AccountId): Boolean
    /** Returns null when nothing is saved or the saved data cannot be authenticated. */
    fun read(account: AccountId): OfflineState?
    fun write(account: AccountId, state: OfflineState)
    fun delete(account: AccountId)
}

/**
 * One AES-GCM file per account, replaced atomically on each write. Each write rewrites the
 * whole file, so callers bound its size. Local search (M8) needs an indexed store instead.
 */
internal class EncryptedOfflineStore(private val directory: File, private val key: () -> SecretKey) : OfflineStore {
    // The file name hides the user ID. The AAD binds the content to that user.
    private fun file(account: AccountId) = AtomicFile(File(directory, MessageDigest.getInstance("SHA-256")
        .digest(account.value.value.toByteArray()).joinToString("") { "%02x".format(it) } + ".v1"))
    private fun aad(account: AccountId) = "velin.offline.v1:${account.value.value}".toByteArray()

    @Synchronized override fun exists(account: AccountId): Boolean {
        val base = file(account).baseFile
        return base.exists() || File(base.path + ".bak").exists()
    }
    @Synchronized override fun read(account: AccountId): OfflineState? {
        if (!exists(account)) return null
        val file = file(account)
        val encrypted = file.openRead().use {
            require(it.channel.size() <= MAX_FILE_BYTES)
            it.readBytes()
        }
        val plaintext = try { CredentialCipher.decrypt(key(), encrypted, aad(account)) }
        catch (_: AEADBadTagException) { file.delete(); return null }
        return try { decodeOffline(String(plaintext, Charsets.UTF_8)) }
        catch (_: JSONException) { file.delete(); null }
        catch (_: IllegalArgumentException) { file.delete(); null }
        finally { plaintext.fill(0) }
    }
    @Synchronized override fun write(account: AccountId, state: OfflineState) {
        val plaintext = encodeOffline(state).toByteArray(Charsets.UTF_8)
        val encrypted = try { CredentialCipher.encrypt(key(), plaintext, aad(account)) } finally { plaintext.fill(0) }
        require(encrypted.size <= MAX_FILE_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("No offline directory")
        val file = file(account)
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized override fun delete(account: AccountId) { file(account).delete() }
    private companion object { const val MAX_FILE_BYTES = 32 * 1024 * 1024 }
}

private const val FORMAT = 1

internal fun encodeOffline(state: OfflineState): String = JSONObject().apply {
    put("format", FORMAT)
    put("cursors", JSONObject().apply { state.cursors.forEach { (group, batch) -> put(group, batch) } })
    state.syncedAt?.let { put("syncedAt", it.toEpochMilli()) }
    put("folders", JSONArray(state.mailbox.folders.map { folder ->
        JSONObject().put("id", folder.id.value.json()).put("name", folder.name).put("kind", folder.kind.name)
            .put("unread", folder.unreadCount).put("parent", folder.parent?.value?.json())
    }))
    put("labels", JSONArray(state.mailbox.labels.map { it.json() }))
    put("senders", JSONArray(state.mailbox.senders.map { JSONObject().put("address", it.address.json()).put("enabled", it.enabled) }))
    put("entryLists", JSONObject().apply { state.mailbox.entryLists.forEach { (list, set) -> put(list, set.json()) } })
    put("pages", JSONArray(state.pages.map { (folder, page) ->
        JSONObject().put("folder", folder.value.json()).put("next", page.next?.value?.value)
            .put("mails", JSONArray(page.mails.map { it.json() }))
    }))
    put("draftRejections", JSONArray(state.draftRejections.map { (id, addresses) ->
        JSONObject().put("id", id.value.json()).put("addresses", JSONArray(addresses.toList()))
    }))
    put("messages", JSONArray(state.messages.map { (m, body, summary) ->
        JSONObject().put("id", m.id.value.json()).put("from", m.from.json()).put("to", m.to.json()).put("cc", m.cc.json())
            .put("bcc", m.bcc.json()).put("replyTo", m.replyTo.json()).put("subject", m.subject)
            .put("sentAt", m.sentAt.toEpochMilli()).put("confidential", m.confidential).put("unread", m.unread)
            .put("draft", m.isDraft).put("attachments", JSONArray(m.attachments.map { a ->
                JSONObject().put("id", a.id.value.json()).put("name", a.name).put("mime", a.mimeType)
                    .put("size", a.sizeBytes).put("cid", a.contentId)
            })).put("authentication", m.authentication.name).put("finding", m.finding?.name)
            .put("scheduledAt", m.scheduledSendAt?.toEpochMilli()).put("received", m.received).put("body", body).put("summary", summary?.json())
    }))
}.toString()

internal fun decodeOffline(text: String): OfflineState {
    val root = JSONObject(text)
    require(root.getInt("format") == FORMAT)
    val cursors = root.getJSONObject("cursors").let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
    val folders = root.getJSONArray("folders").objects().map {
        Folder(FolderId(it.getJSONArray("id").entity()), it.getString("name"), FolderKind.valueOf(it.getString("kind")),
            it.getInt("unread"), it.optJSONArray("parent")?.let { p -> FolderId(p.entity()) })
    }
    val labels = root.getJSONArray("labels").objects().map { it.label() }
    val senders = root.getJSONArray("senders").objects().map { SenderAlias(it.getJSONArray("address").address(), it.getBoolean("enabled")) }
    val entryLists = root.getJSONObject("entryLists").let { json -> json.keys().asSequence().associateWith { json.getJSONArray(it).entity() } }
    val pages = root.getJSONArray("pages").objects().associate { page ->
        FolderId(page.getJSONArray("folder").entity()) to MailPage(page.getJSONArray("mails").objects().map { it.summary() },
            page.nullableString("next")?.let { PageCursor(ElementId(it)) })
    }
    val messages = root.getJSONArray("messages").objects().map {
        SavedMessage(MailMessage(MailId(it.getJSONArray("id").entity()), it.getJSONArray("from").address(),
            it.getJSONArray("to").addresses(), it.getJSONArray("cc").addresses(), it.getJSONArray("bcc").addresses(),
            it.getJSONArray("replyTo").addresses(), it.getString("subject"), Instant.ofEpochMilli(it.getLong("sentAt")),
            it.getBoolean("confidential"), it.getBoolean("unread"), it.getBoolean("draft"), BodyState.Loading,
            it.getJSONArray("attachments").objects().map { a ->
                Attachment(AttachmentId(a.getJSONArray("id").entity()), a.getString("name"), a.getString("mime"),
                    a.getLong("size"), a.nullableString("cid"))
            }, MailAuthentication.valueOf(it.getString("authentication")),
            it.nullableString("finding")?.let(MailFinding::valueOf),
            if (it.has("scheduledAt")) Instant.ofEpochMilli(it.getLong("scheduledAt")) else null,
            // Files saved by v0.3 have no state. Most saved mail is received mail.
            it.optBoolean("received", true)), it.getString("body"),
            it.optJSONObject("summary")?.summary())
    }
    val rejections = root.optJSONArray("draftRejections")?.objects()?.associate { entry ->
        MailId(entry.getJSONArray("id").entity()) to entry.getJSONArray("addresses").let { addresses ->
            (0 until addresses.length()).map { addresses.getString(it).lowercase() }.toSet()
        }
    }.orEmpty()
    return OfflineState(cursors, if (root.has("syncedAt")) Instant.ofEpochMilli(root.getLong("syncedAt")) else null,
        MailboxSnapshot(folders, labels, senders, entryLists), pages, messages, rejections)
}

private fun EntityId.json() = JSONArray().put(list.value).put(element.value)
private fun JSONArray.entity() = EntityId(ListId(getString(0)), ElementId(getString(1))).also { require(length() == 2) }
private fun Address.json() = JSONArray().put(address).put(name)
private fun List<Address>.json() = JSONArray(map { it.json() })
private fun JSONArray.address() = Address(getString(0), getString(1)).also { require(length() == 2) }
private fun JSONArray.addresses() = List(length()) { getJSONArray(it).address() }
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.nullableString(name: String): String? = if (has(name)) getString(name) else null
private fun Label.json() = JSONObject().put("id", id.value.json()).put("name", name).put("color", colorHex)
private fun JSONObject.label() = Label(LabelId(getJSONArray("id").entity()), getString("name"), nullableString("color"))
private fun MailSummary.json() = JSONObject().put("id", id.value.json()).put("conversation", conversationId.value.json())
    .put("heading", heading.json()).put("subject", subject).put("receivedAt", receivedAt.toEpochMilli())
    .put("unread", unread).put("attachments", hasAttachments).put("confidential", confidential)
    .put("replyType", replyType.name).put("labels", JSONArray(labels.map { it.json() })).put("size", conversationSize)
private fun JSONObject.summary() = MailSummary(MailId(getJSONArray("id").entity()), ConversationId(getJSONArray("conversation").entity()),
    getJSONArray("heading").addresses(), getString("subject"), Instant.ofEpochMilli(getLong("receivedAt")),
    getBoolean("unread"), getBoolean("attachments"), getBoolean("confidential"), ReplyType.valueOf(getString("replyType")),
    getJSONArray("labels").objects().map { it.label() }, getInt("size"))
