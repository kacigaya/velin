package app.velin.core.sdk

import de.tutao.tutasdk.*

enum class RequestMethod { GET, POST, PUT, DELETE }
data class BinaryRequest(val url: String, val method: RequestMethod,
    val headers: Map<String, String>, val body: ByteArray?, val trustedBlobServer: Boolean = false)
data class BinaryResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray?)
enum class TransportFailure { Network, InvalidUrl, Tls, InvalidRequest, InvalidResponse }
class TransportException(val failure: TransportFailure) : Exception(failure.name)
fun interface BinaryTransport {
    suspend fun request(request: BinaryRequest): BinaryResponse
}

internal class SdkRestClient(private val transport: BinaryTransport) : RestClient {
    override suspend fun velinBlobBinary(url: String, method: HttpMethod, options: RestClientOptions): RestResponse =
        request(url, method, options, true)
    override suspend fun requestBinary(url: String, method: HttpMethod, options: RestClientOptions): RestResponse =
        request(url, method, options, false)
    private suspend fun request(url: String, method: HttpMethod, options: RestClientOptions, blob: Boolean): RestResponse {
        val response = try {
            transport.request(BinaryRequest(url, RequestMethod.valueOf(method.name), options.headers, options.body, blob))
        } catch (e: TransportException) {
            throw when (e.failure) {
                TransportFailure.Network -> RestClientException.NetworkException()
                TransportFailure.InvalidUrl -> RestClientException.InvalidUrl()
                TransportFailure.Tls -> RestClientException.FailedHandshake()
                TransportFailure.InvalidRequest -> RestClientException.InvalidRequest()
                TransportFailure.InvalidResponse -> RestClientException.InvalidResponse()
            }
        }
        return RestResponse(response.status.toUInt(), response.headers, response.body)
    }
}
