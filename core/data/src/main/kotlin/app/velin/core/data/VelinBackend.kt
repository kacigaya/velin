package app.velin.core.data

import android.content.Context
import app.velin.core.domain.AuthRepository
import app.velin.core.network.HttpTransport
import app.velin.core.sdk.*
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Create once per application process. Call close only when the backend is no longer used. */
class VelinBackend(context: Context, readSource: MailReadSource = UnavailableMailReadSource) {
    private val reads = BackendReadRepository(readSource, File(context.applicationContext.cacheDir, "mail-attachments"))
    val mailbox: app.velin.core.domain.MailboxRepository = reads
    val mail: app.velin.core.domain.MailRepository = reads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseUrl = "https://app.tuta.com"
    private val transport = HttpTransport(URI(baseUrl))
    private val store = EncryptedCredentialStore(
        File(context.applicationContext.noBackupFilesDir, "credentials.v1"), AndroidCredentialKey::get)
    private val repository = BackendAuthRepository(AuthService(baseUrl, transport), store, SessionLoader { c ->
        val engine = SdkEngine(baseUrl, transport, PrivateSdkContentStore())
        try {
            val session = engine.resume(SessionCredentials.internalAccount(c.login, c.user, c.token, c.encryptedKey))
            AutoCloseable { session.close(); engine.close() }
        } catch (e: Throwable) { engine.close(); throw e }
    }, scope)
    val auth: AuthRepository = repository
    init {
        scope.launch { repository.initialize() }
        scope.launch {
            var active: app.velin.core.domain.AccountId? = null
            repository.state.collect { state ->
                val next = (state as? app.velin.core.domain.AuthState.Active)?.account?.id
                if (next != active) reads.clear()
                active = next
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
