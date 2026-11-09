package app.velin.core.data

import app.velin.core.sdk.SdkException
import app.velin.core.sdk.SdkFailure
import app.velin.core.sdk.TrustedIdentity
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class IdentityTrustStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    @Test fun firstIdentitySurvivesRestartAndCannotBeReplacedByLookup() = runBlocking {
        val directory = Files.createTempDirectory("velin-trust").toFile()
        try {
            val first = TrustedIdentity(ByteArray(32) { 11 }, 0, false)
            IdentityTrustStore(directory, "account-a") { key }.trustOnFirstUse("self@tuta.com", first)
            val reopened = IdentityTrustStore(directory, "account-a") { key }
            assertArrayEquals(first.key, reopened.load("self@tuta.com")!!.key)
            val selected = reopened.trustOnFirstUse("self@tuta.com", TrustedIdentity(ByteArray(32) { 22 }, 1, false))
            assertArrayEquals(first.key, selected.key)
            assertNull(IdentityTrustStore(directory, "account-b") { key }.load("self@tuta.com"))
            assertFalse(directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains("self@tuta.com"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun manualTrustPersistsAndLookupCannotDowngradeIt() = runBlocking {
        val directory = Files.createTempDirectory("velin-trust").toFile()
        try {
            val store = IdentityTrustStore(directory, "account-a") { key }
            val identity = TrustedIdentity(ByteArray(32) { 11 }, 0, false)
            store.trustOnFirstUse("self@tuta.com", identity)
            store.verify("self@tuta.com", identity)
            val reopened = IdentityTrustStore(directory, "account-a") { key }
            assertTrue(reopened.load("self@tuta.com")!!.manual)
            assertTrue(reopened.trustOnFirstUse("self@tuta.com", identity).manual)
            reopened.untrust("self@tuta.com")
            assertNull(store.load("self@tuta.com"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun tamperingBlocksTrustWithoutReset() = runBlocking {
        val directory = Files.createTempDirectory("velin-trust").toFile()
        try {
            val store = IdentityTrustStore(directory, "account-a") { key }
            store.trustOnFirstUse("self@tuta.com", TrustedIdentity(ByteArray(32) { 11 }, 0, false))
            val file = directory.listFiles()!!.single()
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try { store.load("self@tuta.com"); fail("Tampered trust was accepted") }
            catch (error: SdkException) { assertEquals(SdkFailure.IdentityTrustStorage, error.failure) }
            assertTrue(file.exists())
            try { store.trustOnFirstUse("other@tuta.com", TrustedIdentity(ByteArray(32), 0, false)); fail("Trust reset after corruption") }
            catch (error: SdkException) { assertEquals(SdkFailure.IdentityTrustStorage, error.failure) }
        } finally { directory.deleteRecursively() }
    }
}
