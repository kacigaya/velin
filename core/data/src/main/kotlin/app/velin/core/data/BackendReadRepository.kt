package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.html.MailHtmlSanitizer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Memory-only read cache. All source and filesystem work runs on IO. */
class BackendReadRepository(private val source: MailReadSource, private val cache: File) : MailboxRepository, MailRepository {
    private val mutex = Mutex()
    private val accounts = mutableMapOf<AccountId, AccountCache>()
    private class AccountCache {
        val mailbox = MutableStateFlow(MailboxSnapshot(emptyList(), emptyList(), emptyList()))
        val sync = MutableStateFlow<SyncState>(SyncState.Idle)
        val pages = mutableMapOf<FolderId, MutableStateFlow<MailPage>>()
        val conversations = mutableMapOf<ConversationId, MutableStateFlow<Outcome<Conversation>>>()
        val viewers = mutableMapOf<ConversationId, Int>()
        val messages = mutableMapOf<MailId, MailMessage>()
        val rawBodies = mutableMapOf<MailId, String>()
        val consent = mutableSetOf<MailId>()
    }
    // Flow creation can occur on the main thread. Mutations below hold mutex as well.
    private fun account(id: AccountId): AccountCache = synchronized(accounts) { accounts.getOrPut(id) { AccountCache() } }
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(accounts) {
                accounts.values.forEach { state ->
                    state.mailbox.value = MailboxSnapshot(emptyList(), emptyList(), emptyList())
                    state.pages.values.forEach { it.value = MailPage(emptyList(), null) }
                    state.conversations.values.forEach { it.value = Outcome.Failed(Failure.Unauthorized) }
                    state.messages.clear()
                    state.rawBodies.clear()
                    state.consent.clear()
                }
            }
            cache.listFiles()?.filter { it.name.startsWith("attachment-") && it.name.endsWith(".bin") }?.forEach { it.delete() }
        }
    }
    private val sanitizer = MailHtmlSanitizer()
    override fun folders(account: AccountId) = this.account(account).mailbox.map { it.folders }
    override fun labels(account: AccountId) = this.account(account).mailbox.map { it.labels }
    override fun senders(account: AccountId) = this.account(account).mailbox.map { it.senders }
    override fun syncState(account: AccountId): Flow<SyncState> = this.account(account).sync.asStateFlow()
    override suspend fun sync(account: AccountId): Outcome<Unit> = io {
        val state = this.account(account)
        state.sync.value = SyncState.Syncing
        when (val result = source.mailbox(account)) {
            is Outcome.Failed -> { state.sync.value = SyncState.Failed(result.failure); result }
            is Outcome.Success -> {
                state.mailbox.value = result.value.copy(folders = folderTree(result.value.folders))
                state.sync.value = SyncState.Idle
                Outcome.Success(Unit)
            }
        }
    }
    override fun messages(account: AccountId, folder: FolderId): Flow<MailPage> = flow {
        val state = this@BackendReadRepository.account(account)
        val page = mutex.withLock { state.pages.getOrPut(folder) { MutableStateFlow(MailPage(emptyList(), null)) } }
        io {
            when (val result = source.page(account, folder, null)) {
                is Outcome.Success -> page.value = result.value.copy(mails = newest(result.value.mails))
                is Outcome.Failed -> state.sync.value = SyncState.Failed(result.failure)
            }
            Outcome.Success(Unit)
        }
        emitAll(page)
    }
    override suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit> = io {
        val page = this.account(account).pages[folder] ?: return@io Outcome.Failed(Failure.Protocol(409))
        if (page.value.next != cursor) return@io Outcome.Failed(Failure.Protocol(409))
        when (val result = source.page(account, folder, cursor)) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                if (result.value.next == cursor) return@io Outcome.Failed(Failure.Protocol(502))
                page.value = MailPage(newest((page.value.mails + result.value.mails).distinctBy { it.id }), result.value.next)
                Outcome.Success(Unit)
            }
        }
    }
    override suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage> = io { loadMessage(account, id) }
    private suspend fun loadMessage(account: AccountId, id: MailId): Outcome<MailMessage> {
        val state = this.account(account)
        state.messages[id]?.let { return Outcome.Success(it) }
        val message = when (val result = source.message(account, id)) {
            is Outcome.Failed -> return result
            is Outcome.Success -> result.value
        }
        if (message.id != id) return Outcome.Failed(Failure.Protocol(502))
        val body = when (val result = source.body(account, id)) {
            is Outcome.Failed -> BodyState.Failed(result.failure)
            is Outcome.Success -> {
                state.rawBodies[id] = result.value
                sanitized(message, result.value, id in state.consent)
            }
        }
        return Outcome.Success(message.copy(body = body).also { state.messages[id] = it })
    }
    private fun sanitized(message: MailMessage, raw: String, allowed: Boolean): BodyState.Loaded {
        val clean = sanitizer.sanitize(raw, allowed)
        val attachments = message.attachments.filter { !it.contentId.isNullOrBlank() }.associateBy { it.contentId!!.removeSurrounding("<", ">") }
        return BodyState.Loaded(clean.html, clean.externalContentPresent, clean.externalContentBlocked,
            clean.inlineImages.mapNotNull { (url, cid) -> attachments[cid.removeSurrounding("<", ">")]?.let { url to it.id } }.toMap())
    }
    override fun conversation(account: AccountId, id: ConversationId): Flow<Outcome<Conversation>> = flow {
        val state = this@BackendReadRepository.account(account)
        val conversation = mutex.withLock { state.conversations.getOrPut(id) { MutableStateFlow(Outcome.Failed(Failure.Offline)) } }
        mutex.withLock { state.viewers[id] = (state.viewers[id] ?: 0) + 1 }
        try {
        io {
            conversation.value = when (val result = source.conversation(account, id)) {
                is Outcome.Failed -> result
                is Outcome.Success -> {
                    val summaries = result.value.filter { it.conversationId == id }.distinctBy { it.id }.sortedBy { it.receivedAt }
                    val messages = mutableListOf<MailMessage>()
                    var failure: Failure? = null
                    for (summary in summaries) when (val loaded = loadMessage(account, summary.id)) {
                        is Outcome.Failed -> { failure = loaded.failure; break }
                        is Outcome.Success -> messages += loaded.value
                    }
                    failure?.let { Outcome.Failed(it) } ?: Outcome.Success(Conversation(id,
                        messages.lastOrNull()?.subject.orEmpty(), summaries.flatMap { it.labels }.distinctBy { it.id }, null,
                        messages.sortedBy { it.sentAt }))
                }
            }
            Outcome.Success(Unit)
        }
        emitAll(conversation)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    val viewers = (state.viewers[id] ?: 1) - 1
                    if (viewers > 0) state.viewers[id] = viewers else {
                        state.viewers.remove(id)
                        val previous = conversation.value
                        if (previous is Outcome.Success) {
                            val reset = previous.value.messages.map { message ->
                                state.consent.remove(message.id)
                                val raw = state.rawBodies[message.id]
                                if (raw == null) message else message.copy(body = sanitized(message, raw, false)).also {
                                    state.messages[message.id] = it
                                }
                            }
                            conversation.value = Outcome.Success(previous.value.copy(messages = reset))
                        }
                    }
                }
            }
        }
    }
    override suspend fun allowExternalContent(account: AccountId, mailId: MailId): Outcome<Unit> = io {
        val state = this.account(account)
        val message = state.messages[mailId] ?: return@io Outcome.Failed(Failure.Protocol(404))
        val raw = state.rawBodies[mailId] ?: return@io Outcome.Failed(Failure.Decryption)
        state.consent += mailId
        val updated = message.copy(body = sanitized(message, raw, true))
        state.messages[mailId] = updated
        state.conversations.values.forEach { flow ->
            val previous = flow.value
            if (previous is Outcome.Success) flow.value = Outcome.Success(previous.value.copy(
                messages = previous.value.messages.map { if (it.id == mailId) updated else it }))
        }
        Outcome.Success(Unit)
    }
    override suspend fun inlineImage(account: AccountId, id: AttachmentId): Outcome<InlineImage> = io {
        when (val result = source.attachment(account, id)) {
            is Outcome.Failed -> result
            is Outcome.Success -> if (result.value.metadata.id != id) Outcome.Failed(Failure.Protocol(502))
                else validateInlineImage(result.value)
        }
    }
    override suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<File> = io {
        when (val result = source.attachment(account, id)) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                if (result.value.metadata.id != id || result.value.bytes.size.toLong() != result.value.metadata.sizeBytes) return@io Outcome.Failed(Failure.Protocol(502))
                // Never use a sender-controlled filename or ID as a path component.
                if (!cache.isDirectory && !cache.mkdirs()) return@io Outcome.Failed(Failure.Offline)
                val file = File.createTempFile("attachment-", ".bin", cache)
                try { file.outputStream().use { it.write(result.value.bytes) } }
                catch (e: Exception) { file.delete(); throw e }
                Outcome.Success(file)
            }
        }
    }
    private suspend fun <T> io(block: suspend () -> Outcome<T>): Outcome<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: java.io.IOException) { Outcome.Failed(Failure.Offline) }
            catch (_: IllegalArgumentException) { Outcome.Failed(Failure.Protocol(502)) }
        }
    }
    private fun newest(mails: List<MailSummary>) = mails.sortedWith(compareByDescending<MailSummary> { it.receivedAt }.thenByDescending { it.id.value.element.value })
    override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean) = unsupported()
    override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId) = unsupported()
    override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>) = unsupported()
    override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = unsupported()
    override suspend fun sendDraft(account: AccountId, id: DraftId) = unsupported()
    override fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>> = flowOf(unsupported())
    override fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>> = flowOf(emptyList())
    private fun unsupported(): Outcome.Failed = Outcome.Failed(Failure.Unsupported)
}

internal fun folderTree(folders: List<Folder>): List<Folder> {
    val byId = folders.associateBy { it.id }
    return folders.map { folder ->
        val seen = mutableSetOf(folder.id)
        var parent = folder.parent
        var depth = 0
        while (parent != null) {
            if (!seen.add(parent)) throw IllegalArgumentException("Folder cycle")
            val ancestor = byId[parent] ?: break
            depth++
            parent = ancestor.parent
        }
        folder.copy(parent = folder.parent?.takeIf { it in byId }, depth = depth)
    }
}

internal fun validateInlineImage(content: AttachmentContent): Outcome<InlineImage> {
    val bytes = content.bytes
    val mime = content.metadata.mimeType.lowercase()
    fun prefix(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all { bytes[it].toInt() and 255 == expected[it] }
    val valid = when (mime) {
        "image/png" -> prefix(137, 80, 78, 71, 13, 10, 26, 10)
        "image/jpeg" -> prefix(255, 216, 255)
        "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }
    return if (bytes.size in 1..10 * 1024 * 1024 && bytes.size.toLong() == content.metadata.sizeBytes && valid) Outcome.Success(InlineImage(bytes.copyOf(), mime))
        else Outcome.Failed(Failure.Unsupported)
}
