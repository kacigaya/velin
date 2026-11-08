package app.velin.core.network

import app.velin.core.sdk.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Upstream tuta-sdk/rust/sdk/src/lib.rs:113,160-169; ClientDetector.ts:389-396.
const val SDK_CLIENT_VERSION = TutaRelease.CLIENT_VERSION
const val ANDROID_MAIL_PLATFORM = "2"

sealed class ProtocolException : Exception() {
    class InvalidSoftwareVersion : ProtocolException()
    class Unauthorized : ProtocolException()
    class TooManyAttempts : ProtocolException()
    class Http(val status: Int) : ProtocolException()
}

/** One instance per allowed service origin. Redirects never forward credentials. */
class HttpTransport internal constructor(private val origin: URI, private val maximumResponseBytes: Int,
    private val openConnection: (URI) -> HttpURLConnection) : BinaryTransport {
    constructor(origin: URI, maximumResponseBytes: Int = 32 * 1024 * 1024) :
        this(origin, maximumResponseBytes, { it.toURL().openConnection() as HttpURLConnection })
    init {
        require(origin.scheme == "https" && origin.host != null && origin.userInfo == null)
        require(maximumResponseBytes > 0)
    }
    override suspend fun request(request: BinaryRequest): BinaryResponse = withContext(Dispatchers.IO) {
        val uri = try { URI(request.url) } catch (_: Exception) {
            throw TransportException(TransportFailure.InvalidUrl)
        }
        if (uri.scheme != "https" || uri.host != origin.host || uri.port != origin.port || uri.userInfo != null || uri.fragment != null) {
            throw TransportException(TransportFailure.InvalidUrl)
        }
        if (request.headers.any { (k, v) -> !k.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) || v.any { it == '\r' || it == '\n' } }) {
            throw TransportException(TransportFailure.InvalidRequest)
        }
        val connection = try { openConnection(uri) }
        catch (_: SSLException) { throw TransportException(TransportFailure.Tls) }
        catch (_: IOException) { throw TransportException(TransportFailure.Network) }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.method.name
            connection.useCaches = false
            for ((key, value) in request.headers) connection.setRequestProperty(key, value)
            connection.setRequestProperty("cv", SDK_CLIENT_VERSION)
            connection.setRequestProperty("cp", ANDROID_MAIL_PLATFORM)
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 100..599) throw TransportException(TransportFailure.InvalidResponse)
            val headers = connection.headerFields.entries.filter { it.key != null }
                .associate { it.key.lowercase(Locale.ROOT) to it.value.joinToString(",") }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (count > maximumResponseBytes - output.size()) throw TransportException(TransportFailure.InvalidResponse)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            BinaryResponse(status, headers, body)
        } catch (_: SSLException) { throw TransportException(TransportFailure.Tls)
        } catch (_: IOException) { throw TransportException(TransportFailure.Network)
        } finally { connection.disconnect() }
    }
}

class ServiceClient(private val transport: BinaryTransport) {
    suspend fun request(url: String, method: RequestMethod, modelVersion: Long,
        accessToken: String? = null, body: ByteArray? = null): BinaryResponse {
        require(modelVersion >= 0)
        val headers = mutableMapOf("v" to modelVersion.toString(), "cv" to SDK_CLIENT_VERSION, "cp" to ANDROID_MAIL_PLATFORM)
        accessToken?.let { headers["accessToken"] = it }
        val response = transport.request(BinaryRequest(url, method, headers, body))
        checkStatus(response.status)
        return response
    }
    companion object {
        // Upstream tuta-sdk/rust/sdk/src/rest_error.rs:373 maps 474 to InvalidSoftwareVersionError.
        fun checkStatus(status: Int) {
            if (status in 200..299) return
            throw when (status) {
                474 -> ProtocolException.InvalidSoftwareVersion()
                401 -> ProtocolException.Unauthorized()
                429 -> ProtocolException.TooManyAttempts()
                else -> ProtocolException.Http(status)
            }
        }
    }
}
