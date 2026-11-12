package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.sdk.*
import app.velin.core.network.ProtocolException
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface SessionLoader {
    suspend fun load(credentials: SavedCredentials): AutoCloseable
}

internal class BackendAuthRepository(
    private val service: AuthService,
    private val store: CredentialStore,
    private val loader: SessionLoader,
    private val scope: CoroutineScope,
    private val derive: (CharArray, ByteArray, KdfAlgorithm) -> ByteArray = KeyDerivation::derive,
    private val pollDelay: suspend () -> Unit = { delay(1_000) },
    /** True when saved mail for the account can open without a session. */
    private val hasSavedMail: (AccountId) -> Boolean = { false },
    /** Deletes saved mail after the account is removed from this device. */
    private val forgetSavedMail: suspend (AccountId) -> Unit = {},
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) : AuthRepository, SessionReadAccess {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Credentials(emptyList(), busy = true))
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L
    private var closed = false
    private var pending: SavedCredentials? = null
    private var resuming: AccountId? = null
    private var polling: Job? = null
    private var session: AutoCloseable? = null

    override suspend fun <T> read(account: AccountId, operation: suspend (SdkMailReader) -> Outcome<T>): Outcome<T> = mutex.withLock {
        val active = mutableState.value as? AuthState.Active
        val loaded = session as? ActiveSdkSession
        if (active?.account?.id != account) Outcome.Failed(Failure.Unauthorized)
        // Active without a session: saved mail opened offline, and the session still loads.
        else if (loaded == null) Outcome.Failed(Failure.Offline)
        else operation(loaded.mail)
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!closed && generation == 0L) try { credentials() } catch (_: Exception) { credentialsError(AuthError.Unknown) }
        }
    }
    private fun accounts() = store.all().map { account(it) }
    private fun account(c: SavedCredentials) = Account(AccountId(ElementId(c.user)), Address(c.login))
    private fun credentials() {
        try { mutableState.value = AuthState.Credentials(accounts()) }
        catch (_: Exception) { credentialsError(AuthError.Unknown) }
    }
    private fun credentialsError(error: AuthError) {
        val known = try { accounts() } catch (_: Exception) { emptyList() }
        mutableState.value = AuthState.Credentials(known, error = error)
    }
    private fun reset(): SavedCredentials? {
        generation++
        polling?.cancel(); polling = null
        val previous = pending; pending = null; resuming = null
        session?.close(); session = null
        return previous
    }
    private suspend fun begin(): Long {
        val (ticket, previous) = mutex.withLock {
            check(!closed) { "Backend is closed" }
            val previous = reset()
            mutableState.value = AuthState.Credentials(try { accounts() } catch (_: Exception) { emptyList() }, busy = true)
            generation to previous
        }
        previous?.let { runCatching { service.cancel(it.token) } }
        return ticket
    }
    override suspend fun signIn(address: String, password: CharArray) {
        try { withContext(Dispatchers.IO) {
            val ticket = begin()
            var created: NewSession? = null
            try {
                val login = address.trim().lowercase(java.util.Locale.ROOT)
                val (salt, algorithm) = service.salt(login)
                val key = try { derive(password, salt, algorithm) } finally { password.fill('\u0000') }
                val accessKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val encryptedKey: ByteArray
                try {
                    created = service.create(login, key, accessKey)
                    encryptedKey = SessionKeyWrapping.encrypt(accessKey, key)
                } finally { key.fill(0); accessKey.fill(0) }
                val result = created
                val c = SavedCredentials(login, result.user, result.token, encryptedKey)
                val current = mutex.withLock {
                    if (generation != ticket) false else {
                        pending = c
                        if (result.types.isNotEmpty()) {
                            mutableState.value = AuthState.SecondFactor(login, "1" in result.types,
                                result.types.any { it == "0" || it == "2" }, otherSession = true)
                            startPolling(ticket, c)
                        }
                        true
                    }
                }
                if (!current) discard(c.token)
                else if (result.types.isEmpty()) activate(ticket, c)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { abandon(ticket, created?.token) }; throw e
            } catch (e: Exception) {
                mutex.withLock { if (generation == ticket) { pending = null; credentialsError(authError(e)) } }
                created?.let { discard(it.token) }
            }
        } } finally { password.fill('\u0000') }
    }
    private fun startPolling(ticket: Long, c: SavedCredentials) {
        polling = scope.launch {
            var networkRetries = 0
            while (isActive) {
                try {
                    if (!service.pending(c.token)) { activate(ticket, c); return@launch }
                    networkRetries = 0
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    mutex.withLock {
                        if (generation == ticket) {
                            val current = mutableState.value as? AuthState.SecondFactor
                            if (current != null) mutableState.value = current.copy(busy = false, error = authError(e))
                        }
                    }
                    if (authError(e) != AuthError.Offline || ++networkRetries > 10) return@launch
                }
                pollDelay()
            }
        }
    }
    private suspend fun activate(ticket: Long, c: SavedCredentials) {
        val loaded = loader.load(c)
        var keep = false
        try {
            mutex.withLock {
                if (generation == ticket) {
                    store.save(c)
                    session = loaded; keep = true; pending = null; resuming = null
                    mutableState.value = AuthState.Active(account(c))
                }
            }
        } finally { if (!keep) loaded.close() }
    }
    override suspend fun answerSecondFactor(answer: SecondFactorAnswer) = withContext(Dispatchers.IO) {
        val operation = mutex.withLock {
            val c = pending ?: return@withContext
            val current = mutableState.value as? AuthState.SecondFactor ?: return@withContext
            if (current.busy) return@withContext
            if (answer == SecondFactorAnswer.SecurityKey) {
                mutableState.value = current.copy(error = AuthError.SecurityKeyUnsupported)
                return@withContext
            }
            val code = (answer as SecondFactorAnswer.Totp).code
            if (!current.totp || !code.matches(Regex("[0-9]{6}"))) {
                mutableState.value = current.copy(error = AuthError.InvalidCode); return@withContext
            }
            mutableState.value = current.copy(busy = true, error = null)
            Triple(generation, c, code)
        }
        try {
            service.totp(operation.second.token, operation.third)
            mutex.withLock {
                if (generation == operation.first && pending === operation.second) {
                    val current = mutableState.value as? AuthState.SecondFactor
                    if (current != null) mutableState.value = current.copy(busy = false, error = null)
                    if (polling?.isActive != true) startPolling(operation.first, operation.second)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            mutex.withLock {
                if (generation == operation.first) {
                    val current = mutableState.value as? AuthState.SecondFactor
                    if (current != null) mutableState.value = current.copy(busy = false, error = authError(e, code = true))
                }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                if (generation == operation.first) {
                    val current = mutableState.value as? AuthState.SecondFactor
                    if (current != null) mutableState.value = current.copy(busy = false)
                }
            } }
        }
    }
    private suspend fun abandon(ticket: Long, token: String?) {
        mutex.withLock { if (generation == ticket) { reset(); credentialsError(AuthError.Unknown) } }
        token?.let { discard(it) }
    }
    /**
     * Ends a session that was created but will not be used. Cancelling the second factor only
     * removes a session that still waits for it; a session without 2FA, or one that failed
     * after activation, must be deleted explicitly or it stays open on the server.
     */
    private suspend fun discard(token: String) {
        runCatching { service.cancel(token) }
        runCatching { service.revoke(token) }
    }
    override suspend fun cancelSignIn() = withContext(Dispatchers.IO) {
        val (ticket, c) = mutex.withLock { val c = reset(); credentials(); generation to c }
        c?.let {
            try { service.cancel(it.token) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutex.withLock { if (generation == ticket) credentialsError(authError(e)) } }
        }
        Unit
    }
    suspend fun close() = withContext(Dispatchers.IO) {
        val c = mutex.withLock { closed = true; reset() }
        c?.let { runCatching { service.cancel(it.token) } }
        Unit
    }
    override suspend fun resume(account: AccountId) = withContext(Dispatchers.IO) {
        val ticket = begin()
        var saved: SavedCredentials? = null
        try {
            val c = mutex.withLock {
                if (generation != ticket) return@withContext
                resuming = account
                store.all().first { it.user == account.value.value }
            }
            saved = c
            activate(ticket, c)
        } catch (e: CancellationException) { withContext(NonCancellable) { abandon(ticket, null) }; throw e
        } catch (e: Exception) {
            mutex.withLock { if (generation == ticket) {
                val c = saved
                if (e is SdkException && e.failure == SdkFailure.InvalidAccessToken) mutableState.value = AuthState.Expired(account)
                else if (c != null && authError(e) == AuthError.Offline && hasSavedMail(account)) {
                    mutableState.value = AuthState.Active(account(c))
                    retryResume(ticket, c)
                }
                else credentialsError(authError(e))
            } }
        }
    }
    /** Keeps the offline account active and loads its session when the network returns. */
    private fun retryResume(ticket: Long, c: SavedCredentials) {
        polling = scope.launch {
            var wait = 2_000L
            while (isActive) {
                retryDelay(wait)
                try { activate(ticket, c); return@launch }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (authError(e) == AuthError.Offline) { wait = (wait * 2).coerceAtMost(60_000); continue }
                    mutex.withLock { if (generation == ticket) {
                        polling = null
                        reset()
                        if (e is SdkException && e.failure == SdkFailure.InvalidAccessToken) mutableState.value = AuthState.Expired(account(c).id)
                        else credentialsError(authError(e))
                    } }
                    return@launch
                }
            }
        }
    }
    // Saved mail is deleted outside the lock: reads hold their own lock while they call read().
    override suspend fun removeSavedAccount(account: AccountId): Outcome<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                store.remove(account.value.value)
                if ((mutableState.value as? AuthState.Active)?.account?.id == account || resuming == account) reset()
                if (mutableState.value !is AuthState.Active && pending == null) credentials()
                Outcome.Success(Unit)
            } catch (_: Exception) { Outcome.Failed(Failure.Decryption) }
        }.also { if (it is Outcome.Success) forgetSavedMail(account) }
    }
    override suspend fun signOut(account: AccountId): Outcome<Unit> = withContext(Dispatchers.IO) {
        // Keep credentials on network failure so the user can retry server revocation.
        mutex.withLock {
            try {
                val c = store.all().firstOrNull { it.user == account.value.value }
                if (c != null) service.revoke(c.token)
                store.remove(account.value.value)
                if ((mutableState.value as? AuthState.Active)?.account?.id == account || resuming == account) reset()
                if (mutableState.value !is AuthState.Active && pending == null) credentials()
                Outcome.Success(Unit)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Outcome.Failed(when (authError(e)) {
                AuthError.Offline -> Failure.Offline
                AuthError.InvalidSoftwareVersion -> Failure.InvalidSoftwareVersion
                else -> Failure.Protocol(0)
            }) }
        }.also { if (it is Outcome.Success) forgetSavedMail(account) }
    }
}
internal fun authError(e: Exception, code: Boolean = false): AuthError = when (e) {
    is ProtocolException.InvalidSoftwareVersion -> AuthError.InvalidSoftwareVersion
    is ProtocolException.TooManyAttempts -> AuthError.TooManyAttempts
    is ProtocolException.Unauthorized -> if (code) AuthError.InvalidCode else AuthError.InvalidCredentials
    is ProtocolException.Http -> if (code && e.status in setOf(400, 403)) AuthError.InvalidCode else AuthError.Unknown
    is TransportException -> if (e.failure == TransportFailure.Network) AuthError.Offline else AuthError.Unknown
    is SdkException -> when (e.failure) {
        SdkFailure.Network -> AuthError.Offline
        SdkFailure.InvalidAccessToken, SdkFailure.InvalidPassphrase -> AuthError.InvalidCredentials
        SdkFailure.TooManyAttempts -> AuthError.TooManyAttempts
        SdkFailure.InvalidSoftwareVersion -> AuthError.InvalidSoftwareVersion
        else -> AuthError.Unknown
    }
    else -> AuthError.Unknown
}

internal class ActiveSdkSession(val session: SdkSession, private val engine: SdkEngine,
    private val events: app.velin.core.network.EventBus? = null,
    private val eventJob: Job? = null) : AutoCloseable {
    val mail: SdkMailReader = object : SdkMailReader by session.mail {
        override suspend fun unread(folder: ReadFolder): Long = events?.unread(folder.ownerGroup, folder.id.element) ?: 0
    }
    override fun close() {
        eventJob?.cancel()
        events?.close()
        try { session.close() } finally { engine.close() }
    }
}
