package app.velin.core.sdk

import de.tutao.tutasdk.*
import org.junit.Assert.*
import org.junit.Test

class ReadDiagnosticsTest {
    @Test fun diagnosticsKeepKindsAndDiscardRemoteText() {
        assertEquals("HttpError.InvalidSoftwareVersionError", apiDiagnostic(
            ApiCallException.ServerResponseException(HttpError.InvalidSoftwareVersionError)))
        assertEquals("RestClientError.NetworkException", apiDiagnostic(
            ApiCallException.RestClient(RestClientException.NetworkException())))
        assertEquals("InternalSdkError:redacted", apiDiagnostic(
            ApiCallException.InternalSdkException("secret token and mail body")))
        assertEquals("InternalSdkError:Invalid received decryption", apiDiagnostic(
            ApiCallException.InternalSdkException("Invalid received decryption")))
    }
}
