package app.velin.core.network
import app.velin.core.sdk.*
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class HttpTransportTest {
    @Test fun serviceHeaders() = runBlocking {
        val client = ServiceClient(BinaryTransport { request ->
            assertEquals(mapOf("v" to "123", "cv" to SDK_CLIENT_VERSION, "cp" to "2", "accessToken" to "secret"), request.headers)
            BinaryResponse(200, emptyMap(), null)
        })
        client.request("https://invalid.example/rest", RequestMethod.GET, 123, "secret")
        Unit
    }
    @Test(expected = ProtocolException.InvalidSoftwareVersion::class) fun versionRejection() {
        ServiceClient.checkStatus(474)
    }
    @Test fun rejectForeignOriginBeforeConnecting() = runBlocking {
        val transport = HttpTransport(URI("https://invalid.example"))
        for (url in listOf("http://invalid.example", "https://elsewhere.example", "https://user@invalid.example")) {
            try { transport.request(BinaryRequest(url, RequestMethod.GET, emptyMap(), null)); fail() }
            catch (e: TransportException) { assertEquals(TransportFailure.InvalidUrl, e.failure) }
        }
        Unit
    }
    private class FakeConnection(private val code: Int, private val bytes: ByteArray) : java.net.HttpURLConnection(java.net.URL("https://invalid.example")) {
        var closed = false
        val sent = java.io.ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("X-Mixed-Case" to listOf("value"))
        override fun getInputStream() = bytes.inputStream()
        override fun getErrorStream() = bytes.inputStream()
        override fun getOutputStream() = sent
    }
    @Test fun responseAndRequestHandling() = runBlocking {
        val connection = FakeConnection(474, byteArrayOf(9))
        val transport = HttpTransport(URI("https://invalid.example"), 1024) { connection }
        val response = transport.request(BinaryRequest("https://invalid.example/rest", RequestMethod.POST,
            mapOf("accessToken" to "token", "v" to "42"), byteArrayOf(1, 2)))
        assertEquals(474, response.status)
        assertEquals(mapOf("x-mixed-case" to "value"), response.headers)
        assertArrayEquals(byteArrayOf(9), response.body)
        assertArrayEquals(byteArrayOf(1, 2), connection.sent.toByteArray())
        assertEquals("42", connection.getRequestProperty("v"))
        assertEquals("token", connection.getRequestProperty("accessToken"))
        assertEquals(SDK_CLIENT_VERSION, connection.getRequestProperty("cv"))
        assertEquals("2", connection.getRequestProperty("cp"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        Unit
    }
    @Test fun responseSizeIsBounded() = runBlocking {
        val connection = FakeConnection(200, ByteArray(3))
        val transport = HttpTransport(URI("https://invalid.example"), 2) { connection }
        try { transport.request(BinaryRequest("https://invalid.example", RequestMethod.GET, emptyMap(), null)); fail() }
        catch (e: TransportException) { assertEquals(TransportFailure.InvalidResponse, e.failure) }
        assertTrue(connection.closed)
        Unit
    }
}
