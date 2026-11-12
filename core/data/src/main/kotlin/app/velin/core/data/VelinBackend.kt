package app.velin.core.data

import android.content.Context
import app.velin.core.domain.AccountId
import app.velin.core.domain.AuthRepository
import app.velin.core.domain.AuthState
import app.velin.core.domain.ElementId
import app.velin.core.network.EventBus
import app.velin.core.network.HttpTransport
import app.velin.core.network.SyncEvent
import app.velin.core.sdk.*
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Create once per application process. Call close only when the backend is no longer used. */
class VelinBackend(context: Context, readSource: MailReadSource? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseUrl = "https://app.tuta.com"
    private val transport = HttpTransport(URI(baseUrl))
    private val store = EncryptedCredentialStore(
        File(context.applicationContext.noBackupFilesDir, "credentials.v1"), AndroidCredentialKey::get)
    private val offline = EncryptedOfflineStore(File(context.applicationContext.noBackupFilesDir, "offline"), AndroidCredentialKey::offline)
    private val repository: BackendAuthRepository = BackendAuthRepository(AuthService(baseUrl, transport), store, SessionLoader { c ->
        val engine = SdkEngine(baseUrl, transport, PrivateSdkContentStore(), IdentityTrustStore(
            File(context.applicationContext.noBackupFilesDir, "identity-trust"), c.user, AndroidCredentialKey::get))
        try {
            val session = engine.resume(SessionCredentials.internalAccount(c.login, c.user, c.token, c.encryptedKey))
            val account = AccountId(ElementId(c.user))
            val events = EventBus(URI(baseUrl), c.user, c.token) { reads.cursors(account) }
            val eventJob = watchEvents(account, events)
            events.start()
            ActiveSdkSession(session, engine, events, eventJob)
        } catch (e: Throwable) { engine.close(); throw e }
    }, scope, hasSavedMail = offline::exists, forgetSavedMail = { reads.forget(it) })
    private val reads: BackendReadRepository = BackendReadRepository(readSource ?: SdkMailReadSource(repository),
        File(context.applicationContext.cacheDir, "mail-attachments"), offline, java.time.Instant::now)
    val mailbox: app.velin.core.domain.MailboxRepository = reads
    val mail: app.velin.core.domain.MailRepository = reads
    val auth: AuthRepository = repository
    init {
        scope.launch { repository.initialize() }
        scope.launch {
            var active: AccountId? = null
            repository.state.collect { state ->
                val next = (state as? AuthState.Active)?.account?.id
                if (next != active) {
                    reads.clear()
                    next?.let { reads.open(it) }
                }
                active = next
            }
        }
    }
    /** Events wait for activation, because reads need the active session. Batches apply in order. */
    private fun watchEvents(account: AccountId, events: EventBus): Job = scope.launch {
        repository.state.first { (it as? AuthState.Active)?.account?.id == account }
        launch { events.changes.collect { reads.refreshMailbox(account) } }
        events.events.collect { event ->
            when (event) {
                is SyncEvent.Connected -> reads.connected(account, event.replay)
                is SyncEvent.Batch -> reads.apply(account, event)
                SyncEvent.CaughtUp -> reads.caughtUp(account)
            }
        }
    }
    suspend fun close() {
        scope.cancel()
        repository.close()
        reads.clear()
    }
}

/** M2 has no persistent SDK cache. Content stays private to each SDK instance. */
internal class PrivateSdkContentStore : SdkContentStore {
    private val content = mutableMapOf<String, ByteArray>()
    override suspend fun write(name: String, content: ByteArray) {
        validate(name)
        synchronized(this) { this.content.put(name, content.copyOf())?.fill(0) }
    }
    override suspend fun read(name: String): ByteArray {
        validate(name)
        return synchronized(this) { content[name]?.copyOf() ?: throw FileNotFoundException() }
    }
    private fun validate(name: String) {
        require(name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' })
    }
}
