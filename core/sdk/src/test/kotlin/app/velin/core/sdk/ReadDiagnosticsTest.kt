package app.velin.core.sdk

import de.tutao.tutasdk.*
import org.junit.Assert.*
import org.junit.Test

class ReadDiagnosticsTest {
    @Test fun draftCryptoFailuresAreTypedAndRedacted() {
        for (message in listOf("Invalid session transfer encryption", "Draft session key resolution failed", "Missing draft session key")) {
            val error = ApiCallException.InternalSdkException(message)
            assertEquals(SdkFailure.InvalidKey, mapApiFailure(error))
            assertEquals("InternalSdkError:redacted", apiDiagnostic(error))
        }
        assertEquals(SdkFailure.DraftUnsupported, mapApiFailure(ApiCallException.VelinDraftUnsupported()))
    }

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
