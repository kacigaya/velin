package app.velin.core.data

import app.velin.core.domain.*
import app.velin.core.sdk.*
import java.net.URLDecoder
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BackendAuthRepositoryTest {
    private val token = "ZB-VPZfACMABhx-jUBZ91wyBWLlaJ6AIzg"
    private class MemoryStore : CredentialStore {
        var saved = emptyList<SavedCredentials>()
        override fun all() = saved
        override fun save(credentials: SavedCredentials) { saved = saved.filter { it.user != credentials.user } + credentials }
        override fun remove(user: String) { saved = saved.filter { it.user != user } }
    }
    private inner class Fixture(val challenges: List<String> = emptyList(), val kdf: String = "1") {
        val requests = mutableListOf<BinaryRequest>()
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val poll = ChannelGate()
        var failure = 0
        var totpFailure = 0
        var offline = false
        var approved = false
        var loaded = 0
        var closed = 0
        var selected: KdfAlgorithm? = null
        var beforeCreate: suspend () -> Unit = {}
        var beforeTotp: suspend () -> Unit = {}
        var beforeLoad: suspend () -> Unit = {}
        var loadError: Exception? = null
        var savedMail = false
        val forgotten = mutableListOf<AccountId>()
        val retry = ChannelGate()
        val transport = BinaryTransport { request ->
            requests += request
            if (request.url.endsWith("sessionservice")) beforeCreate()
            if (request.url.endsWith("secondfactorauthservice") && request.method == RequestMethod.POST) beforeTotp()
            if (offline) throw TransportException(TransportFailure.Network)
            if (failure != 0) BinaryResponse(failure, emptyMap(), null)
            else if (request.method == RequestMethod.POST && request.url.endsWith("secondfactorauthservice"))
                BinaryResponse(if (totpFailure == 0) 200 else totpFailure, emptyMap(), null)
            else {
                val response = when {
                    request.url.contains("saltservice") -> json("422" to b64(ByteArray(16)), "2133" to kdf)
                    request.url.endsWith("sessionservice") -> json("1221" to token, "1223" to JSONArray().put("user"),
                        "1222" to JSONArray(challenges.map { json("1189" to it) }))
                    request.method == RequestMethod.GET -> json("1238" to if (approved) "0" else "1")
                    else -> JSONObject()
                }
                BinaryResponse(200, emptyMap(), response.toString().toByteArray())
            }
        }
        val repo = BackendAuthRepository(AuthService("https://example.invalid", transport), store,
            SessionLoader { beforeLoad(); loadError?.let { throw it }; loaded++; AutoCloseable { closed++ } }, scope,
            derive = { password, _, algorithm -> selected = algorithm; password.fill('\u0000'); ByteArray(if (algorithm == KdfAlgorithm.Bcrypt128) 16 else 32) },
            pollDelay = { poll.waitNext() }, hasSavedMail = { savedMail }, forgetSavedMail = { forgotten += it },
            retryDelay = { retry.waitNext() })
        suspend fun login(): CharArray = "secret".toCharArray().also { repo.signIn("  A@EXAMPLE.INVALID ", it) }
        fun close() { scope.cancel() }
    }
    private class ChannelGate {
        private val channel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        suspend fun waitNext() { channel.receive() }
        fun advance() { channel.trySend(Unit) }
    }
    @Test fun passwordLoginAndRequestContract() = runBlocking {
        for (kdf in listOf("0", "1")) {
            val f = Fixture(kdf = kdf)
            try {
                val password = f.login()
                assertTrue(password.all { it == '\u0000' })
                assertTrue(f.repo.state.value is AuthState.Active)
                assertEquals(if (kdf == "0") KdfAlgorithm.Bcrypt128 else KdfAlgorithm.Argon2id, f.selected)
                val salt = f.requests.first()
                assertNull(salt.body)
                val query = JSONObject(URLDecoder.decode(salt.url.substringAfter("?_body="), "UTF-8"))
                assertEquals("a@example.invalid", query.getString("419"))
                val request = f.requests[1]
                assertEquals(mapOf("v" to "157", "cv" to "366.261105.0", "cp" to "2", "Accept" to "application/json", "Content-Type" to "application/json"), request.headers)
                val body = JSONObject(String(request.body!!))
                assertEquals("0", body.getString("1212"))
                assertEquals("a@example.invalid", body.getString("1213"))
                assertEquals(url64(sha256(ByteArray(if (kdf == "0") 16 else 32))), body.getString("1214"))
                assertEquals(32, java.util.Base64.getDecoder().decode(body.getString("1216")).size)
                assertEquals(0, body.getJSONArray("1218").length())
                assertTrue(body.isNull("1217")); assertTrue(body.isNull("1417"))
                assertEquals(1, f.store.all().size)
            } finally { f.close() }
        }
    }
    @Test fun failedActivationDeletesCreatedSession() = runBlocking {
        val f = Fixture()
        try {
            f.loadError = IllegalStateException("native load failed")
            f.login()
            assertTrue(f.repo.state.value is AuthState.Credentials)
            assertTrue(f.store.all().isEmpty())
            val delete = f.requests.last()
            assertEquals(RequestMethod.DELETE, delete.method)
            assertEquals(token, delete.headers["accessToken"])
            assertTrue(delete.url.contains("/rest/sys/Session/"))
        } finally { f.close() }
    }
    @Test fun totpRequiresPollingApproval() = runBlocking {
        val f = Fixture(listOf("1", "2"))
        try {
            f.login()
            val state = f.repo.state.value as AuthState.SecondFactor
            assertTrue(state.totp && state.securityKey && state.otherSession)
            assertEquals(0, f.loaded)
            f.repo.answerSecondFactor(SecondFactorAnswer.SecurityKey)
            assertEquals(AuthError.SecurityKeyUnsupported, (f.repo.state.value as AuthState.SecondFactor).error)
            f.repo.answerSecondFactor(SecondFactorAnswer.Totp("012345"))
            val request = f.requests.last { it.method == RequestMethod.POST }
            val body = JSONObject(String(request.body!!))
            assertEquals("012345", body.getString("1243"))
            assertEquals("1", body.getString("1230"))
            assertEquals(JSONArray(AuthService.sessionIds(token)).toString(), body.getJSONArray("1232").getJSONArray(0).toString())
            assertEquals(0, f.loaded)
            f.approved = true; f.poll.advance()
            assertTrue(f.repo.state.value is AuthState.Active)
            assertEquals(1, f.loaded)
        } finally { f.close() }
    }
    @Test fun cancelDeletesChallengeAndCannotActivateLater() = runBlocking {
        val f = Fixture(listOf("2"))
        try {
            f.login(); f.repo.cancelSignIn()
            assertTrue(f.repo.state.value is AuthState.Credentials)
            assertTrue(f.requests.any { it.method == RequestMethod.DELETE && JSONObject(String(it.body!!)).has("1757") })
            f.approved = true; f.poll.advance()
            assertEquals(0, f.loaded); assertTrue(f.store.all().isEmpty())
        } finally { f.close() }
    }
    @Test fun httpErrorsAndPasswordCleanup() = runBlocking {
        for ((status, expected) in listOf(401 to AuthError.InvalidCredentials, 429 to AuthError.TooManyAttempts,
            474 to AuthError.InvalidSoftwareVersion, 500 to AuthError.Unknown)) {
            val f = Fixture(); f.failure = status
            try {
                assertTrue(f.login().all { it == '\u0000' })
                assertEquals(expected, (f.repo.state.value as AuthState.Credentials).error)
                assertEquals(0, f.loaded)
            } finally { f.close() }
        }
        val f = Fixture(); f.offline = true
        try { assertTrue(f.login().all { it == '\u0000' }); assertEquals(AuthError.Offline, (f.repo.state.value as AuthState.Credentials).error) }
        finally { f.close() }
    }
    @Test fun codeErrorsStayOnChallenge() = runBlocking {
        val f = Fixture(listOf("1"))
        try {
            f.login()
            for ((status, expected) in listOf(400 to AuthError.InvalidCode, 401 to AuthError.InvalidCode,
                429 to AuthError.TooManyAttempts, 474 to AuthError.InvalidSoftwareVersion)) {
                f.totpFailure = status
                f.repo.answerSecondFactor(SecondFactorAnswer.Totp("123456"))
                assertEquals(expected, (f.repo.state.value as AuthState.SecondFactor).error)
            }
        } finally { f.close() }
    }
    @Test fun unknownKdfFailsClosedAndClearsPassword() = runBlocking {
        val f = Fixture(kdf = "99")
        try {
            assertTrue(f.login().all { it == '\u0000' })
            assertEquals(1, f.requests.size); assertNull(f.selected)
            assertEquals(AuthError.Unknown, (f.repo.state.value as AuthState.Credentials).error)
        } finally { f.close() }
    }
    @Test fun resumeSignOutAndLocalRemoval() = runBlocking {
        val f = Fixture()
        try {
            f.login()
            val account = (f.repo.state.value as AuthState.Active).account.id
            f.repo.resume(account)
            assertEquals(2, f.loaded); assertEquals(1, f.closed)
            f.offline = true
            assertEquals(Outcome.Failed(Failure.Offline), f.repo.signOut(account))
            assertEquals(1, f.store.all().size)
            f.offline = false
            assertEquals(Outcome.Success(Unit), f.repo.signOut(account))
            assertTrue(f.store.all().isEmpty())
            val revoke = f.requests.last()
            assertEquals("$token", revoke.headers["accessToken"])
            assertTrue(revoke.url.contains("/rest/sys/Session/O0yKEOU-1B-0/jlv3AEmnv8rvtZe38u2dk-U1kzxpkMXWNusNz-NhnMI"))
            f.login(); assertEquals(Outcome.Success(Unit), f.repo.removeSavedAccount(account))
            assertTrue(f.store.all().isEmpty())
        } finally { f.close() }
    }
    @Test fun offlineResumeOpensSavedMailUntilSessionLoads() = runBlocking {
        val f = Fixture()
        try {
            f.login()
            val account = (f.repo.state.value as AuthState.Active).account.id
            f.loadError = SdkException(SdkFailure.Network)
            f.repo.resume(account)
            assertEquals(AuthError.Offline, (f.repo.state.value as AuthState.Credentials).error)
            f.savedMail = true
            f.repo.resume(account)
            assertEquals(account, (f.repo.state.value as AuthState.Active).account.id)
            assertEquals(Outcome.Failed(Failure.Offline), f.repo.read(account) { Outcome.Success(Unit) })
            f.retry.advance()
            assertTrue(f.repo.state.value is AuthState.Active)
            assertEquals(1, f.loaded)
            f.loadError = null
            f.retry.advance()
            assertEquals(2, f.loaded)
            assertEquals(account, (f.repo.state.value as AuthState.Active).account.id)
            assertTrue(f.forgotten.isEmpty())
            assertEquals(Outcome.Success(Unit), f.repo.signOut(account))
            assertEquals(listOf(account), f.forgotten)
        } finally { f.close() }
    }
    @Test fun cancelledCreateResponseCannotBecomeActive() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeCreate = { entered.complete(Unit); release.await() }
        val password = "secret".toCharArray()
        try {
            val login = async { f.repo.signIn("a@example.invalid", password) }
            entered.await()
            f.repo.cancelSignIn()
            release.complete(Unit); login.await()
            assertTrue(f.repo.state.value is AuthState.Credentials)
            assertEquals(0, f.loaded); assertTrue(f.store.all().isEmpty())
            assertTrue(password.all { it == '\u0000' })
            assertEquals(RequestMethod.DELETE, f.requests.last().method)
        } finally { release.complete(Unit); f.close() }
    }
    @Test fun expiredResumePreservesSavedAccount() = runBlocking {
        val f = Fixture()
        try {
            f.login()
            val account = (f.repo.state.value as AuthState.Active).account.id
            f.loadError = SdkException(SdkFailure.InvalidAccessToken)
            f.repo.resume(account)
            assertEquals(AuthState.Expired(account), f.repo.state.value)
            assertEquals(1, f.store.all().size)
        } finally { f.close() }
    }
    @Test fun localRemovalWinsOverLateResume() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.login()
            val account = (f.repo.state.value as AuthState.Active).account.id
            f.beforeLoad = { entered.complete(Unit); release.await() }
            val resume = async { f.repo.resume(account) }
            entered.await()
            assertEquals(Outcome.Success(Unit), f.repo.removeSavedAccount(account))
            release.complete(Unit); resume.await()
            assertTrue(f.store.all().isEmpty())
            assertTrue(f.repo.state.value is AuthState.Credentials)
            assertEquals(2, f.closed)
        } finally { release.complete(Unit); f.close() }
    }
    @Test fun cancelledCodeSubmissionCanBeRetried() = runBlocking {
        val f = Fixture(listOf("1"))
        val entered = CompletableDeferred<Unit>()
        try {
            f.login()
            f.beforeTotp = { entered.complete(Unit); awaitCancellation() }
            val submit = launch { f.repo.answerSecondFactor(SecondFactorAnswer.Totp("123456")) }
            entered.await(); submit.cancelAndJoin()
            assertFalse((f.repo.state.value as AuthState.SecondFactor).busy)
            f.beforeTotp = {}
            f.repo.answerSecondFactor(SecondFactorAnswer.Totp("123456"))
            assertNull((f.repo.state.value as AuthState.SecondFactor).error)
        } finally { f.close() }
    }
    @Test fun encryptedStoreRoundTripAndTamperRejection() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val path = java.io.File.createTempFile("credentials", ".bin").apply { delete() }
        try {
            val store = EncryptedCredentialStore(path) { key }
            store.save(SavedCredentials("mail@example.invalid", "user", token, byteArrayOf(1, 2)))
            val reopened = EncryptedCredentialStore(path) { key }
            assertEquals(token, reopened.all().single().token)
            assertArrayEquals(byteArrayOf(1, 2), reopened.all().single().encryptedKey)
            val bytes = path.readBytes()
            assertFalse(String(bytes).contains(token)); assertFalse(String(bytes).contains("mail@example.invalid"))
            store.save(SavedCredentials("mail@example.invalid", "user", token, byteArrayOf(1, 2)))
            assertFalse(bytes.contentEquals(path.readBytes()))
            path.writeBytes(bytes.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertThrows(javax.crypto.AEADBadTagException::class.java) { reopened.all() }
            assertTrue(reopened.all().isEmpty())
            reopened.save(SavedCredentials("new@example.invalid", "new", token, byteArrayOf(3)))
            assertEquals("new", reopened.all().single().user)
            reopened.remove("new")
            assertTrue(reopened.all().isEmpty())
        } finally { path.delete() }
    }
    @Test fun lostKeyDoesNotBlockNewSaveOrRemove() {
        val original = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val replacement = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val path = java.io.File.createTempFile("lost-key", ".bin").apply { delete() }
        try {
            EncryptedCredentialStore(path) { original }.save(SavedCredentials("old@example.invalid", "old", token, byteArrayOf(1)))
            val restored = EncryptedCredentialStore(path) { replacement }
            restored.save(SavedCredentials("new@example.invalid", "new", token, byteArrayOf(2)))
            assertEquals("new", restored.all().single().user)
            restored.remove("new")
            assertTrue(restored.all().isEmpty())
        } finally { path.delete() }
    }
}
