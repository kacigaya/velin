package app.velin.core.domain
import org.junit.Assert.*
import org.junit.Test
class MailFindingsTest {
    @Test fun preservesPriorityAndUnknownFailures() {
        assertEquals(MailFinding.Phishing, mailFinding(true, true, MailAuthentication.HardFail, true))
        assertNull(mailFinding(false, true, MailAuthentication.HardFail, true))
        assertEquals(MailFinding.AuthenticationFailed, mailFinding(false, false, MailAuthentication.Unknown, true))
        assertEquals(MailFinding.DeprecatedSenderKey, mailFinding(false, false, MailAuthentication.SoftFail, true))
        assertEquals(MailFinding.AuthenticationUncertain, mailFinding(false, false, MailAuthentication.SoftFail, false))
        assertNull(mailFinding(false, false, null, false))
    }
    @Test fun decodesWireStatuses() {
        assertEquals(listOf(MailAuthentication.Authenticated, MailAuthentication.HardFail,
            MailAuthentication.SoftFail, MailAuthentication.InvalidMailFrom, MailAuthentication.MissingMailFrom),
            (0..4).map { mailAuthenticationFromWire(it.toString()) })
        assertEquals(MailAuthentication.Unknown, mailAuthenticationFromWire("future"))
    }
}
