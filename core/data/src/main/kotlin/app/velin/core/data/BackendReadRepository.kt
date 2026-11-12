package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.html.MailHtmlSanitizer
import app.velin.core.network.EntityOperation
import app.velin.core.network.SyncEvent
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Read cache in memory, saved to [store] when one is given. All source and filesystem work runs on IO.
 * Event batches change the cache, and each applied batch moves its group cursor in the same save.
 */
class BackendReadRepository internal constructor(private val source: MailReadSource, private val cache: File,
    private val store: OfflineStore?, private val now: () -> Instant) : MailboxRepository, MailRepository {
    constructor(source: MailReadSource, cache: File) : this(source, cache, null, Instant::now)
    private val mutex = Mutex()
    private val accounts = mutableMapOf<AccountId, AccountCache>()
    // Only the signed-in account saves. A late save after sign-out must not write mail again.
    private var openAccount: AccountId? = null
    private class AccountCache {
        val mailbox = MutableStateFlow(MailboxSnapshot(emptyList(), emptyList(), emptyList()))
        val sync = MutableStateFlow<SyncState>(SyncState.Idle)
        val pages = mutableMapOf<FolderId, MutableStateFlow<MailPage>>()
        val conversations = mutableMapOf<ConversationId, MutableStateFlow<Outcome<Conversation>>>()
        val viewers = mutableMapOf<ConversationId, Int>()
        val messages = mutableMapOf<MailId, MailMessage>()
        val summaries = mutableMapOf<MailId, MailSummary>()
        val rawBodies = mutableMapOf<MailId, String>()
        val consent = mutableSetOf<MailId>()
        val draftRejections = mutableMapOf<MailId, Set<String>>()
        var restored = false
        val cursors = mutableMapOf<String, String>()
        var syncedAt: Instant? = null
        // Work from event batches that is not done yet. A failed batch leaves it for the next one.
        val changedMails = mutableSetOf<MailId>()
        val changedFolders = mutableSetOf<FolderId>()
        val changedConversations = mutableSetOf<ConversationId>()
        var mailboxChanged = false
        fun clearChanges() { changedMails.clear(); changedFolders.clear(); changedConversations.clear(); mailboxChanged = false }
    }
    // Flow creation can occur on the main thread. Mutations below hold mutex as well.
    private fun account(id: AccountId): AccountCache = synchronized(accounts) { accounts.getOrPut(id) { AccountCache() } }
    /** Also removes old downloads, so a file opened before an app restart does not stay. */
    suspend fun open(account: AccountId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            openAccount = account
            deleteDownloads(olderThan = now().minus(DOWNLOAD_LIFETIME))
        }
    }
    /** Deletes saved mail of an account removed from this device. */
    suspend fun forget(account: AccountId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (openAccount == account) openAccount = null
            try { store?.delete(account) } catch (_: Exception) {}
            deleteDownloads()
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            openAccount = null
            deleteDownloads()
            synchronized(accounts) {
                accounts.values.forEach { state ->
                    state.mailbox.value = MailboxSnapshot(emptyList(), emptyList(), emptyList())
                    state.pages.values.forEach { it.value = MailPage(emptyList(), null) }
                    state.conversations.values.forEach { it.value = Outcome.Failed(Failure.Unauthorized) }
                    state.messages.clear()
                    state.summaries.clear()
                    state.rawBodies.clear()
                    state.consent.clear()
                    state.draftRejections.clear()
                    state.restored = false
                    state.cursors.clear()
                    state.syncedAt = null
                    state.clearChanges()
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
    override suspend fun sync(account: AccountId): Outcome<Unit> = io(account, save = true) {
        val state = this.account(account)
        state.sync.value = SyncState.Syncing
        try {
            when (val result = source.mailbox(account)) {
                is Outcome.Failed -> { fail(state, result.failure); result }
                is Outcome.Success -> {
                    setMailbox(state, result.value)
                    // Bodies of sent and received mail never change. Keep them for offline reading.
                    state.messages.values.filter { it.isDraft }.forEach { state.messages.remove(it.id); state.rawBodies.remove(it.id) }
                    for ((folder, flow) in state.pages) {
                        when (val page = source.page(account, folder, null)) {
                            is Outcome.Failed -> { fail(state, page.failure); return@io page }
                            is Outcome.Success -> {
                                page.value.mails.forEach { state.summaries[it.id] = it }
                                flow.value = page.value.copy(mails = newest(page.value.mails))
                            }
                        }
                    }
                    state.messages.replaceAll { id, mail -> state.summaries[id]?.let { mail.copy(unread = it.unread) } ?: mail }
                    for ((id, flow) in state.conversations) {
                        if ((state.viewers[id] ?: 0) > 0) {
                            val refreshed = refreshConversation(account, id)
                            flow.value = refreshed
                            if (refreshed is Outcome.Failed) {
                                fail(state, refreshed.failure)
                                return@io refreshed
                            }
                        }
                    }
                    // A full sync reloads folders, pages and open conversations. Mails beyond the first page still need locate.
                    state.changedFolders.clear(); state.changedConversations.clear(); state.mailboxChanged = false
                    state.syncedAt = now()
                    state.sync.value = SyncState.Idle
                    Outcome.Success(Unit)
                }
            }
        } finally {
            if (state.sync.value == SyncState.Syncing) state.sync.value = SyncState.Idle
        }
    }.also { result ->
        if (result is Outcome.Failed) fail(this.account(account), result.failure)
    }
    override fun messages(account: AccountId, folder: FolderId): Flow<MailPage> = flow {
        val state = this@BackendReadRepository.account(account)
        lateinit var page: MutableStateFlow<MailPage>
        io(account) { page = state.pages.getOrPut(folder) { MutableStateFlow(MailPage(emptyList(), null)) }; Outcome.Success(Unit) }
        coroutineScope {
            val fetch = launch {
                io(account, save = true) {
                    when (val result = source.page(account, folder, null)) {
                        is Outcome.Success -> {
                            result.value.mails.forEach { state.summaries[it.id] = it }
                            page.value = result.value.copy(mails = newest(result.value.mails))
                        }
                        is Outcome.Failed -> fail(state, result.failure)
                    }
                    Outcome.Success(Unit)
                }
            }
            // A saved page shows at once and stays offline. An empty one waits, so the list shows loading.
            if (page.value.mails.isEmpty()) fetch.join()
            emitAll(page)
        }
    }
    override suspend fun loadMore(account: AccountId, folder: FolderId, cursor: PageCursor): Outcome<Unit> = io(account, save = true) {
        val page = this.account(account).pages[folder] ?: return@io Outcome.Failed(Failure.Protocol(409))
        if (page.value.next != cursor) return@io Outcome.Failed(Failure.Protocol(409))
        when (val result = source.page(account, folder, cursor)) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                if (result.value.next == cursor) return@io Outcome.Failed(Failure.Protocol(502))
                result.value.mails.forEach { this.account(account).summaries[it.id] = it }
                page.value = MailPage(newest((page.value.mails + result.value.mails).distinctBy { it.id }), result.value.next)
                Outcome.Success(Unit)
            }
        }
    }
    override suspend fun load(account: AccountId, id: MailId): Outcome<MailMessage> = io(account, save = true) { loadMessage(account, id) }
    private suspend fun loadMessage(account: AccountId, id: MailId): Outcome<MailMessage> {
        val state = this.account(account)
        state.messages[id]?.takeIf { it.body !is BodyState.Failed }?.let { return Outcome.Success(it) }
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
            io(account, save = true) {
                conversation.value = refreshConversation(account, id)
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
    private suspend fun refreshConversation(account: AccountId, id: ConversationId): Outcome<Conversation> {
        val state = this.account(account)
        return when (val result = source.conversation(account, id)) {
            is Outcome.Failed -> if (result.failure == Failure.Offline) savedConversation(state, id) ?: result else result
            is Outcome.Success -> {
                val summaries = result.value.filter { it.conversationId == id }.distinctBy { it.id }.sortedBy { it.receivedAt }
                summaries.forEach { state.summaries[it.id] = it }
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
    }
    /** Offline only: a conversation shows when every known mail in it was opened before. */
    private fun savedConversation(state: AccountCache, id: ConversationId): Outcome<Conversation>? {
        val summaries = state.summaries.values.filter { it.conversationId == id }
        val messages = summaries.map { state.messages[it.id] ?: return null }.sortedBy { it.sentAt }
        if (messages.isEmpty()) return null
        return Outcome.Success(Conversation(id, messages.last().subject, summaries.flatMap { it.labels }.distinctBy { it.id },
            null, messages))
    }
    override suspend fun allowExternalContent(account: AccountId, mailId: MailId): Outcome<Unit> = io(account) {
        val state = this.account(account)
        val message = state.messages[mailId] ?: return@io Outcome.Failed(Failure.Protocol(404))
        val raw = state.rawBodies[mailId] ?: return@io Outcome.Failed(Failure.Decryption)
        val viewed = state.conversations.any { (id, flow) ->
            val current = flow.value
            (state.viewers[id] ?: 0) > 0 && current is Outcome.Success && current.value.messages.any { it.id == mailId }
        }
        if (!viewed) return@io Outcome.Failed(Failure.Protocol(409))
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
    override suspend fun inlineImage(account: AccountId, id: AttachmentId): Outcome<InlineImage> = io(account) {
        when (val result = source.attachment(account, id, parentOf(account, id))) {
            is Outcome.Failed -> result
            is Outcome.Success -> if (result.value.metadata.id != id) Outcome.Failed(Failure.Protocol(502))
                else validateInlineImage(result.value)
        }
    }
    override suspend fun downloadAttachment(account: AccountId, id: AttachmentId): Outcome<File> = io(account) {
        when (val result = source.attachment(account, id, parentOf(account, id))) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                if (result.value.metadata.id != id || result.value.bytes.size.toLong() > result.value.metadata.sizeBytes) return@io Outcome.Failed(Failure.Protocol(502))
                if (!cache.isDirectory && !cache.mkdirs()) return@io Outcome.Failed(Failure.Offline)
                // Decrypted files stay only while a viewer app may still read them.
                deleteDownloads(olderThan = now().minus(DOWNLOAD_LIFETIME))
                // A random folder keeps the cleaned sender name from reaching any other path.
                val folder = java.nio.file.Files.createTempDirectory(cache.toPath(), "attachment-").toFile()
                val file = File(folder, downloadName(result.value.metadata.name))
                try { file.outputStream().use { it.write(result.value.bytes) } }
                catch (e: Exception) { folder.deleteRecursively(); throw e }
                Outcome.Success(file)
            }
        }
    }
    /** The cached mail that lists [id]. The SDK needs it after a restart restored the mail. */
    private fun parentOf(account: AccountId, id: AttachmentId): MailId? =
        this.account(account).messages.values.firstOrNull { message -> message.attachments.any { it.id == id } }?.id
    private fun deleteDownloads(olderThan: Instant? = null) {
        cache.listFiles()?.filter { olderThan == null || it.lastModified() < olderThan.toEpochMilli() }
            ?.forEach { it.deleteRecursively() }
    }
    /** With [account], saved data loads first. With [save], the cache is saved after [block]. */
    private suspend fun <T> io(account: AccountId? = null, save: Boolean = false, block: suspend () -> Outcome<T>): Outcome<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            account?.let { restore(it) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: java.io.IOException) { Outcome.Failed(Failure.Offline) }
            catch (_: IllegalArgumentException) { Outcome.Failed(Failure.Protocol(502)) }
            finally { if (save && account != null) save(account) }
        }
    }
    private fun fail(state: AccountCache, failure: Failure) {
        state.sync.value = if (failure == Failure.Offline) SyncState.Offline(state.syncedAt) else SyncState.Failed(failure)
    }
    private fun restore(account: AccountId): AccountCache {
        val state = this.account(account)
        if (state.restored) return state
        state.restored = true
        val saved = try { store?.read(account) } catch (_: Exception) { null } ?: return state
        try {
            state.mailbox.value = saved.mailbox.copy(folders = folderTree(saved.mailbox.folders))
            saved.pages.forEach { (folder, page) ->
                page.mails.forEach { state.summaries[it.id] = it }
                state.pages.getOrPut(folder) { MutableStateFlow(MailPage(emptyList(), null)) }.value = page
            }
            saved.messages.forEach { (message, body, summary) ->
                summary?.let { state.summaries.putIfAbsent(message.id, it) }
                state.rawBodies[message.id] = body
                state.messages[message.id] = message.copy(body = sanitized(message, body, false))
            }
            state.draftRejections.putAll(saved.draftRejections)
            state.cursors.putAll(saved.cursors)
            state.syncedAt = saved.syncedAt
        } catch (_: IllegalArgumentException) {
            // A folder cycle or other invalid data. Start again from the server.
            state.mailbox.value = MailboxSnapshot(emptyList(), emptyList(), emptyList())
            state.pages.values.forEach { it.value = MailPage(emptyList(), null) }
            state.summaries.clear(); state.messages.clear(); state.rawBodies.clear()
        }
        return state
    }
    /** Saving is best effort. Only [apply] needs a durable write, because it moves a cursor. */
    private fun save(account: AccountId) {
        try { write(account) } catch (_: Exception) {}
    }
    private fun write(account: AccountId) {
        val store = store ?: return
        val state = this.account(account)
        // Never replace saved data with a cache that did not load it first.
        check(state.restored && account == openAccount)
        var budget = MAX_SAVED_BODY_CHARS
        val messages = state.messages.values.reversed().mapNotNull { message ->
            val body = state.rawBodies[message.id]?.takeIf { it.length <= budget } ?: return@mapNotNull null
            budget -= body.length
            SavedMessage(message.copy(body = BodyState.Loading), body, state.summaries[message.id])
        }.take(MAX_SAVED_MESSAGES).reversed()
        store.write(account, OfflineState(state.cursors.toMap(), state.syncedAt, state.mailbox.value,
            state.pages.mapValues { (_, flow) -> flow.value.let { page ->
                // A cut page has no valid next cursor. The next online load sets it again.
                if (page.mails.size <= MAX_SAVED_PAGE) page else MailPage(page.mails.take(MAX_SAVED_PAGE), null)
            } }, messages, state.draftRejections.toMap()))
    }

    /** Cursors for the next event connection. After the server's batch lifetime, start again with a full sync. */
    suspend fun cursors(account: AccountId): Map<String, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = restore(account)
            // One expired group cannot replay, so start again for all groups, like upstream's purge.
            val oldest = now().minus(EVENT_BATCH_LIFETIME).toEpochMilli()
            if (state.cursors.values.any { (generatedIdTime(it) ?: 0) < oldest }) state.cursors.clear()
            state.cursors.toMap()
        }
    }
    /** Without a replay, nothing reports what changed while Velin was not connected. */
    suspend fun connected(account: AccountId, replay: Boolean): Outcome<Unit> =
        if (!replay) sync(account) else { account(account).sync.value = SyncState.Syncing; Outcome.Success(Unit) }
    fun caughtUp(account: AccountId) {
        account(account).sync.compareAndSet(SyncState.Syncing, SyncState.Idle)
    }
    /** Unread counts changed. Only folders reload. */
    suspend fun refreshMailbox(account: AccountId): Outcome<Unit> = io(account, save = true) {
        val state = this.account(account)
        when (val result = source.mailbox(account)) {
            is Outcome.Failed -> { fail(state, result.failure); result }
            is Outcome.Success -> {
                setMailbox(state, result.value)
                Outcome.Success(Unit)
            }
        }
    }
    /**
     * Applies one event batch, then saves the cache with the new cursor in one write. On failure the
     * cursor stays, so a reconnect replays the batch. Work that failed offline stays for the next batch.
     */
    suspend fun apply(account: AccountId, batch: SyncEvent.Batch): Outcome<Unit> = io(account) {
        val state = this.account(account)
        for (change in batch.changes) {
            if (change.application != "tutanota") continue
            when (change.type) {
                MAIL_TYPE -> {
                    val id = MailId(EntityId(ListId(change.list ?: continue), ElementId(change.element)))
                    if (change.operation == EntityOperation.Delete) { forget(state, id); state.changedMails.remove(id) }
                    // A new mail can belong to an open conversation even when its folder is not loaded.
                    else if (id in state.summaries || id in state.messages ||
                        change.operation == EntityOperation.Create && state.viewers.values.any { it > 0 }) state.changedMails += id
                }
                MAIL_SET_ENTRY_TYPE -> {
                    val set = change.list?.let { state.mailbox.value.entryLists[it] }
                    if (set == null) state.mailboxChanged = true
                    else if (FolderId(set) in state.pages) state.changedFolders += FolderId(set)
                }
                MAIL_SET_TYPE, MAIL_BOX_TYPE -> state.mailboxChanged = true
            }
        }
        val result = applyChanges(account, state)
        if (result is Outcome.Failed) {
            // Pending work stays until a later batch or a full sync completes it, so no change is skipped.
            fail(state, result.failure)
            return@io result
        }
        val previous = state.cursors[batch.group]
        val previousSync = state.syncedAt
        if (previous == null && state.cursors.size < MAX_CURSORS || previous != null && newerId(batch.id, previous)) {
            state.cursors[batch.group] = batch.id
        }
        state.syncedAt = now()
        try { write(account) } catch (e: Exception) {
            if (previous == null) state.cursors.remove(batch.group) else state.cursors[batch.group] = previous
            state.syncedAt = previousSync
            return@io Outcome.Failed(Failure.Protocol(0, "Offline store write failed"))
        }
        Outcome.Success(Unit)
    }
    private suspend fun applyChanges(account: AccountId, state: AccountCache): Outcome<Unit> {
        if (state.mailboxChanged) when (val result = source.mailbox(account)) {
            is Outcome.Failed -> return result
            is Outcome.Success -> {
                setMailbox(state, result.value)
                state.mailboxChanged = false
            }
        }
        if (state.changedMails.isNotEmpty()) when (val result = source.locate(account, state.changedMails.toList())) {
            is Outcome.Failed -> {
                if (result.failure !is Failure.Unsupported) return result
                // Without locate, reload each loaded folder that shows a changed mail.
                state.pages.filter { (_, flow) -> flow.value.mails.any { it.id in state.changedMails } }.keys.forEach { state.changedFolders += it }
                state.changedMails.clear()
            }
            is Outcome.Success -> {
                for ((id, located) in result.value) {
                    if (located == null) { forget(state, id); continue }
                    val conversation = located.summary.conversationId
                    val shown = (state.conversations[conversation]?.value as? Outcome.Success)?.value?.messages?.any { it.id == id } == true
                    if ((state.viewers[conversation] ?: 0) > 0 && (!shown || state.messages[id]?.isDraft == true)) {
                        state.changedConversations += conversation
                    }
                    state.summaries[id] = located.summary
                    val labels = state.mailbox.value.labels.map { it.id.value }.toSet()
                    state.pages.forEach { (folder, flow) ->
                        val page = flow.value
                        val member = if (folder.value in labels) located.summary.labels.any { it.id.value == folder.value }
                            else folder == located.folder
                        flow.value = when {
                            !member -> page.copy(mails = page.mails.filterNot { it.id == id })
                            page.mails.any { it.id == id } -> page.copy(mails = page.mails.map { if (it.id == id) located.summary else it })
                            // A mail moved in shows when it is inside the loaded range.
                            page.next == null || page.mails.any { it.receivedAt <= located.summary.receivedAt } ->
                                page.copy(mails = newest(page.mails + located.summary))
                            else -> page
                        }
                    }
                    state.messages[id]?.let { message ->
                        if (message.isDraft) { state.messages.remove(id); state.rawBodies.remove(id) }
                        else state.messages[id] = message.copy(unread = located.summary.unread)
                    }
                }
                state.changedMails.clear()
            }
        }
        for (folder in state.changedFolders.toList()) {
            val flow = state.pages[folder]
            if (flow != null) when (val result = source.page(account, folder, null)) {
                is Outcome.Failed -> if (result.failure == Failure.Protocol(404)) state.pages.remove(folder)?.value = MailPage(emptyList(), null)
                    else return result
                is Outcome.Success -> {
                    val fresh = result.value
                    fresh.mails.forEach { mail ->
                        if (mail.id !in state.summaries && (state.viewers[mail.conversationId] ?: 0) > 0) state.changedConversations += mail.conversationId
                        state.summaries[mail.id] = mail
                    }
                    val ids = fresh.mails.map { it.id }.toSet()
                    val oldest = fresh.mails.minOfOrNull { it.receivedAt }
                    // Keep mails loaded beyond the first page. Moves and deletes reach them as mail events.
                    val older = if (fresh.next == null || oldest == null) emptyList()
                        else flow.value.mails.filter { it.id !in ids && it.receivedAt < oldest }
                    flow.value = MailPage(newest(fresh.mails + older), if (older.isEmpty()) fresh.next else flow.value.next)
                }
            }
            state.changedFolders.remove(folder)
        }
        // A new mail in an open conversation, such as a reply, needs the whole conversation again.
        for (id in state.changedConversations.toList()) {
            val flow = state.conversations[id]
            if (flow != null && (state.viewers[id] ?: 0) > 0) {
                val refreshed = refreshConversation(account, id)
                if (refreshed is Outcome.Failed) return refreshed
                flow.value = refreshed
            }
            state.changedConversations.remove(id)
        }
        publish(state)
        return Outcome.Success(Unit)
    }
    /** Pages of deleted folders go too. Their reload would fail with 404 on each sync. */
    private fun setMailbox(state: AccountCache, snapshot: MailboxSnapshot) {
        state.mailbox.value = snapshot.copy(folders = folderTree(snapshot.folders))
        // Labels open as mail lists too, so their pages stay.
        val existing = snapshot.folders.map { it.id.value }.toSet() + snapshot.labels.map { it.id.value }
        state.pages.keys.filter { it.value !in existing }.forEach { state.pages.remove(it)?.value = MailPage(emptyList(), null) }
    }
    private fun forget(state: AccountCache, id: MailId) {
        state.messages.remove(id)
        state.summaries.remove(id)
        state.rawBodies.remove(id)
        state.consent.remove(id)
        state.draftRejections.remove(id)
        state.pages.values.forEach { flow -> flow.value = flow.value.copy(mails = flow.value.mails.filterNot { it.id == id }) }
        state.conversations.values.forEach { flow ->
            val previous = flow.value
            if (previous is Outcome.Success) flow.value = Outcome.Success(previous.value.copy(messages = previous.value.messages.filterNot { it.id == id }))
        }
    }
    private fun newest(mails: List<MailSummary>) = mails.sortedWith(compareByDescending<MailSummary> { it.receivedAt }.thenByDescending { it.id.value.element.value })
    override suspend fun currentFolders(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, FolderId>> = io {
        source.currentFolders(account, ids.distinct())
    }
    override suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit> = io(account, save = true) {
        if (ids.isEmpty()) return@io Outcome.Success(Unit)
        when (val result = source.setUnread(account, ids.distinct(), unread)) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                val state = this.account(account)
                val selected = ids.toSet()
                state.summaries.replaceAll { id, mail -> if (id in selected) mail.copy(unread = unread) else mail }
                state.messages.replaceAll { id, mail -> if (id in selected) mail.copy(unread = unread) else mail }
                publish(state)
                result
            }
        }
    }
    override suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit> = io(account, save = true) {
        if (ids.isEmpty()) return@io Outcome.Success(Unit)
        when (val result = source.move(account, ids.distinct(), target)) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                val state = this.account(account)
                val selected = ids.toSet()
                state.pages.forEach { (folder, flow) ->
                    if (folder != target) flow.value = flow.value.copy(mails = flow.value.mails.filterNot { it.id in selected })
                }
                state.conversations.values.forEach { flow ->
                    val previous = flow.value
                    if (previous is Outcome.Success && previous.value.messages.any { it.id in selected }) {
                        val kind = if (previous.value.messages.all { it.id in selected }) {
                            state.mailbox.value.folders.singleOrNull { it.id == target }?.kind
                        } else null
                        flow.value = Outcome.Success(previous.value.copy(folderKind = kind))
                    }
                }
                // A cached destination can show known messages immediately, including an undo.
                state.pages[target]?.let { flow ->
                    flow.value = flow.value.copy(mails = newest((flow.value.mails + selected.mapNotNull { state.summaries[it] }).distinctBy { it.id }))
                }
                result
            }
        }
    }
    override suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit> = io(account, save = true) {
        if (ids.isEmpty()) return@io Outcome.Success(Unit)
        val mailbox = when (val result = source.mailbox(account)) {
            is Outcome.Failed -> return@io result
            is Outcome.Success -> result.value
        }
        if (labels.distinct().size > 5) return@io Outcome.Failed(Failure.Protocol(400))
        val desired = labels.distinct().map { id -> mailbox.labels.singleOrNull { it.id == id }
            ?: return@io Outcome.Failed(Failure.Protocol(400)) }
        when (val result = source.setLabels(account, ids.distinct(), labels.distinct())) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                val state = this.account(account)
                val selected = ids.toSet()
                state.summaries.replaceAll { id, mail -> if (id in selected) mail.copy(labels = desired) else mail }
                publish(state)
                result
            }
        }
    }
    override suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit> = io(account, save = true) {
        if (ids.isEmpty()) return@io Outcome.Success(Unit)
        val locations = when (val result = source.currentFolders(account, ids.distinct())) {
            is Outcome.Failed -> return@io result
            is Outcome.Success -> result.value
        }
        val mailbox = when (val result = source.mailbox(account)) {
            is Outcome.Failed -> return@io result
            is Outcome.Success -> result.value
        }
        if (ids.any { id -> mailbox.folders.none { it.id == locations[id] && it.kind in setOf(FolderKind.Trash, FolderKind.Spam) } }) {
            return@io Outcome.Failed(Failure.Protocol(409))
        }
        when (val result = source.delete(account, ids.distinct())) {
            is Outcome.Failed -> result
            is Outcome.Success -> {
                val state = this.account(account)
                ids.forEach { forget(state, it) }
                publish(state)
                result
            }
        }
    }
    private fun publish(state: AccountCache) {
        state.pages.values.forEach { flow ->
            flow.value = flow.value.copy(mails = flow.value.mails.map { state.summaries[it.id] ?: it })
        }
        state.conversations.values.forEach { flow ->
            val previous = flow.value
            if (previous is Outcome.Success) flow.value = Outcome.Success(previous.value.copy(
                messages = previous.value.messages.map { state.messages[it.id] ?: it },
                labels = previous.value.messages.flatMap { state.summaries[it.id]?.labels.orEmpty() }.distinctBy { it.id }))
        }
    }
    override suspend fun loadDraft(account: AccountId, id: MailId): Outcome<Draft> = io(account) {
        when (val loaded = source.loadDraft(account, id)) {
            is Outcome.Failed -> loaded
            is Outcome.Success -> {
                val draft = loaded.value
                val addresses = (draft.to + draft.cc + draft.bcc).map { it.address.address.lowercase() }.toSet()
                Outcome.Success(draft.copy(rejectedRecipients = this.account(account).draftRejections[id].orEmpty().intersect(addresses)))
            }
        }
    }
    override suspend fun prepareResponse(account: AccountId, previous: MailId, kind: ResponseKind): Outcome<PreparedResponse> =
        io(account, save = true) {
            // Saved mail from v0.3 has no state, so read the direction fresh. A response needs the server anyway.
            val fresh = when (val result = source.message(account, previous)) {
                is Outcome.Failed -> return@io result
                is Outcome.Success -> result.value
            }
            when (val message = loadMessage(account, previous)) {
                is Outcome.Failed -> message
                // A response without the quoted body would look complete but lose the original text.
                is Outcome.Success -> (message.value.body as? BodyState.Failed)?.let { Outcome.Failed(it.failure) }
                    ?: responseDraft(message.value.copy(received = fresh.received), kind, this.account(account).mailbox.value.senders)
                        ?.let { Outcome.Success(it) } ?: Outcome.Failed(Failure.Unsupported)
            }
        }
    override suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<DraftId> = io(account, save = true) {
        val state = this.account(account)
        fun remember(id: MailId) {
            if (draft.rejectedRecipients.isEmpty()) state.draftRejections.remove(id)
            else state.draftRejections[id] = draft.rejectedRecipients.map { it.lowercase() }.toSet()
        }
        draft.id?.let {
            remember(it.value)
            try { write(account) } catch (_: Exception) { return@io Outcome.Failed(Failure.DraftPersistence(it)) }
        }
        when (val saved = source.saveDraft(account, draft)) {
            is Outcome.Failed -> saved
            is Outcome.Success -> {
                val id = saved.value.value
                remember(id)
                // Rejection is a send block, so a failed durable write must reach the composer.
                try { write(account) } catch (_: Exception) { return@io Outcome.Failed(Failure.DraftPersistence(saved.value)) }
                state.messages.remove(id)
                state.rawBodies.remove(id)
                state.summaries.remove(id)
                // Reload known pages and open conversations on the next sync.
                state.pages.values.forEach { flow -> flow.value = flow.value.copy(mails = flow.value.mails.filterNot { it.id == id }) }
                saved
            }
        }
    }
    override suspend fun recipientFingerprint(account: AccountId, address: String): Outcome<String> = io { source.recipientFingerprint(account, address) }
    override suspend fun verifyRecipient(account: AccountId, address: String, fingerprint: String): Outcome<Unit> = io { source.verifyRecipient(account, address, fingerprint) }
    override suspend fun untrustRecipient(account: AccountId, address: String): Outcome<Unit> = io { source.untrustRecipient(account, address) }
    override suspend fun resolveRecipient(account: AccountId, address: String): Outcome<Recipient> = io { source.resolveRecipient(account, address) }
    override suspend fun sendDraft(account: AccountId, id: DraftId): Outcome<Unit> = io(account, save = true) {
        if (this.account(account).draftRejections[id.value].orEmpty().isNotEmpty()) {
            return@io Outcome.Failed(Failure.Protocol(409, "Rejected recipient fingerprint"))
        }
        val result = source.sendDraft(account, id)
        if (result is Outcome.Success) {
            val state = this.account(account)
            state.messages.remove(id.value)
            state.rawBodies.remove(id.value)
            state.summaries.remove(id.value)
            state.consent.remove(id.value)
            state.pages.values.forEach { flow -> flow.value = flow.value.copy(mails = flow.value.mails.filterNot { it.id == id.value }) }
            state.conversations.values.forEach { flow ->
                val previous = flow.value
                if (previous is Outcome.Success) flow.value = Outcome.Success(previous.value.copy(
                    messages = previous.value.messages.filterNot { it.id == id.value }))
            }
        }
        result
    }
    override suspend fun markResponded(account: AccountId, sent: MailId): Outcome<Unit> = io(account, save = true) {
        when (val result = source.markResponded(account, sent)) {
            is Outcome.Failed -> Outcome.Failed(result.failure)
            is Outcome.Success -> {
                result.value?.let { marked ->
                    val state = this.account(account)
                    state.summaries[marked.previous]?.let { state.summaries[marked.previous] = it.copy(replyType = marked.replyType) }
                    publish(state)
                }
                Outcome.Success(Unit)
            }
        }
    }
    override fun search(account: AccountId, query: String): Flow<Outcome<List<MailSummary>>> = flowOf(unsupported())
    override fun suggestRecipients(account: AccountId, query: String): Flow<List<Recipient>> = flowOf(emptyList())
    private fun unsupported(): Outcome.Failed = Outcome.Failed(Failure.Unsupported)
}

/** A file name without path parts or hidden-file dots, so a viewer app shows a readable name. */
internal fun downloadName(name: String): String {
    val clean = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trimStart('.').ifBlank { "attachment" }
    // File systems limit names to 255 bytes, not characters. Keep a short extension for the viewer app.
    val dot = clean.lastIndexOf('.')
    val extension = if (dot > 0 && clean.length - dot <= MAX_EXTENSION_CHARS) clean.substring(dot) else ""
    val base = clean.dropLast(extension.length)
    var bytes = extension.toByteArray().size
    val kept = StringBuilder()
    var index = 0
    while (index < base.length) {
        val codePoint = base.codePointAt(index)
        val size = String(Character.toChars(codePoint)).toByteArray().size
        if (bytes + size > MAX_NAME_BYTES) break
        kept.appendCodePoint(codePoint)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return kept.toString().ifBlank { "attachment" } + extension
}
private const val MAX_NAME_BYTES = 200
private const val MAX_EXTENSION_CHARS = 16
private val DOWNLOAD_LIFETIME = Duration.ofHours(1)
// tutanota.json type IDs: Mail 97, MailBox 125, MailSet 429, MailSetEntry 1450.
private const val MAIL_TYPE = 97L
private const val MAIL_BOX_TYPE = 125L
private const val MAIL_SET_TYPE = 429L
private const val MAIL_SET_ENTRY_TYPE = 1450L
// EventBusClient.ts:70 ENTITY_EVENT_BATCH_EXPIRE_MS. Older cursors cannot replay.
private val EVENT_BATCH_LIFETIME = Duration.ofDays(44)
private const val MAX_CURSORS = 64
private const val MAX_SAVED_PAGE = 200
private const val MAX_SAVED_MESSAGES = 100
private const val MAX_SAVED_BODY_CHARS = 4 * 1024 * 1024

/**
 * Milliseconds in the first 42 bits of a generated ID (EntityUtils.ts generatedIdToTimestamp), or null.
 * Generated IDs are base64ext. Its alphabet is in ASCII order, so equal-length IDs sort by time.
 */
internal fun generatedIdTime(id: String): Long? {
    if (id.length != 12) return null
    var bits = 0L
    for (ch in id.take(8)) {
        val digit = BASE64_EXT.indexOf(ch)
        if (digit < 0) return null
        bits = (bits shl 6) or digit.toLong()
    }
    return bits ushr 6
}
private const val BASE64_EXT = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

internal fun newerId(candidate: String, current: String): Boolean =
    candidate.length > current.length || candidate.length == current.length && candidate > current

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
    return if (bytes.size in 1..10 * 1024 * 1024 && bytes.size.toLong() <= content.metadata.sizeBytes && valid) Outcome.Success(InlineImage(bytes.copyOf(), mime))
        else Outcome.Failed(Failure.Unsupported)
}
