package app.velin.core.sdk

import de.tutao.tutasdk.*
import java.io.FileNotFoundException
import java.io.IOException

interface SdkContentStore {
    suspend fun write(name: String, content: ByteArray)
    suspend fun read(name: String): ByteArray
}

/** Session material from protected storage. Never include it in logs or UI state. */
class SessionCredentials internal constructor(internal val login: String, internal val userId: String,
    internal val accessToken: String, internal val encryptedPassphraseKey: ByteArray) {
    companion object {
        fun internalAccount(login: String, userId: String, accessToken: String, encryptedPassphraseKey: ByteArray): SessionCredentials =
            SessionCredentials(login, userId, accessToken, encryptedPassphraseKey.copyOf())
    }
}
enum class SdkFailure { InvalidAccessToken, InvalidPassphrase, InvalidKey, Network, Tls,
    InvalidSoftwareVersion, TooManyAttempts, Protocol, Internal, SecureExternalUnsupported, AttachmentsUnsupported, DraftUnsupported }
class SdkException(val failure: SdkFailure, val diagnostic: String? = null) : Exception(
    listOfNotNull(failure.name, diagnostic).joinToString(": "))

/** Only local type names and reviewed static messages can cross the debug boundary. */
internal fun apiException(error: ApiCallException): SdkException = SdkException(mapApiFailure(error),
    if (BuildConfig.DEBUG) apiDiagnostic(error) else null)

internal fun apiDiagnostic(error: ApiCallException): String = when (error) {
    is ApiCallException.RestClient -> "RestClientError." + error.source.javaClass.simpleName
    is ApiCallException.ServerResponseException -> "HttpError." + error.source.javaClass.simpleName
    is ApiCallException.VelinSecureExternalUnsupported -> "SecureExternalUnsupported"
    is ApiCallException.VelinDraftUnsupported -> "DraftUnsupported"
    is ApiCallException.InternalSdkException -> "InternalSdkError:" + when (error.errorMessage) {
        "Missing mail details reference", "Invalid draft decryption", "Invalid received decryption",
        "Missing or duplicate mail details", "Invalid blob response", "Invalid blob server scheme",
        "Velin details read failed", "Velin conversation read failed" -> error.errorMessage
        else -> "redacted"
    }
}

/** Resumes existing internal accounts only. New session orchestration belongs to M2. */
class SdkEngine(baseUrl: String, transport: BinaryTransport, store: SdkContentStore) : AutoCloseable {
    private val sdk = Sdk(baseUrl, SdkRestClient(transport), object : FileClient {
        override suspend fun persistContent(name: String, content: ByteArray) {
            try { store.write(name, content) }
            catch (_: IOException) { throw FileClientException.IoException() }
        }
        override suspend fun readContent(name: String): ByteArray = try { store.read(name) }
        catch (_: FileNotFoundException) { throw FileClientException.NotFound() }
        catch (_: IOException) { throw FileClientException.IoException() }
    })
    // Upstream tuta-sdk/rust/sdk/src/lib.rs:211-249 resumes credentials through LoginFacade.
    suspend fun resume(credentials: SessionCredentials): SdkSession = try {
        SdkSession(sdk.login(Credentials(credentials.login, credentials.userId, credentials.accessToken,
            credentials.encryptedPassphraseKey, CredentialType.INTERNAL)))
    } catch (_: LoginException.InvalidAccessToken) { throw SdkException(SdkFailure.InvalidAccessToken)
    } catch (_: LoginException.InvalidPassphrase) { throw SdkException(SdkFailure.InvalidPassphrase)
    } catch (_: LoginException.InvalidKey) { throw SdkException(SdkFailure.InvalidKey)
    } catch (e: LoginException.ApiCall) { throw apiException(e.source) }
    override fun close() { sdk.close() }
}

class SdkSession internal constructor(private val delegate: LoggedInSdk) : AutoCloseable {
    private val reader = NativeMailReader(delegate.mailFacade())
    val mail: SdkMailReader get() = reader
    override fun close() { try { reader.close() } finally { delegate.close() } }
}

internal fun mapApiFailure(error: ApiCallException): SdkFailure = when (error) {
    is ApiCallException.RestClient -> when (error.source) {
        is RestClientException.NetworkException -> SdkFailure.Network
        is RestClientException.FailedHandshake, is RestClientException.FailedTlsSetup -> SdkFailure.Tls
        else -> SdkFailure.Protocol
    }
    is ApiCallException.ServerResponseException -> when (error.source) {
        HttpError.InvalidSoftwareVersionError -> SdkFailure.InvalidSoftwareVersion
        is HttpError.TooManyRequestsError -> SdkFailure.TooManyAttempts
        HttpError.NotAuthenticatedError, HttpError.SessionExpiredError -> SdkFailure.InvalidAccessToken
        else -> SdkFailure.Protocol
    }
    is ApiCallException.VelinSecureExternalUnsupported -> SdkFailure.SecureExternalUnsupported
    is ApiCallException.VelinDraftUnsupported -> SdkFailure.DraftUnsupported
    is ApiCallException.InternalSdkException -> when (error.errorMessage) {
        "Invalid session transfer encryption", "Draft session key resolution failed", "Missing draft session key" -> SdkFailure.InvalidKey
        else -> SdkFailure.Internal
    }
}
