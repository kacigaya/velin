package app.velin.core.sdk

import de.tutao.tutasdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SdkTransportTest {
    @Test fun callbackPreservesSdkHeadersAndStatus() = runBlocking {
        val client = SdkRestClient(BinaryTransport {
            assertEquals(mapOf("v" to "42", "accessToken" to "token"), it.headers)
            assertEquals(RequestMethod.GET, it.method)
            BinaryResponse(474, mapOf("reason" to "old-client"), null)
        })
        val response = client.requestBinary("https://invalid.example", HttpMethod.GET,
            RestClientOptions(mapOf("v" to "42", "accessToken" to "token"), null, null))
        assertEquals(474u, response.status)
        Unit
    }
    @Test fun networkFailureBecomesSdkCallbackFailure() = runBlocking {
        val client = SdkRestClient(BinaryTransport { throw TransportException(TransportFailure.Network) })
        try {
            client.requestBinary("https://invalid.example", HttpMethod.GET, RestClientOptions(emptyMap(), null, null))
            fail()
        } catch (_: RestClientException.NetworkException) { }
        Unit
    }
    @Test fun mapsTypedSdkApiFailures() {
        assertEquals(SdkFailure.SecureExternalUnsupported,
            mapApiFailure(ApiCallException.VelinSecureExternalUnsupported()))
        assertEquals(SdkFailure.InvalidSoftwareVersion,
            mapApiFailure(ApiCallException.ServerResponseException(HttpError.InvalidSoftwareVersionError)))
        assertEquals(SdkFailure.Network,
            mapApiFailure(ApiCallException.RestClient(RestClientException.NetworkException())))
        assertEquals(SdkFailure.Internal,
            mapApiFailure(ApiCallException.InternalSdkException("redacted")))
    }
}
