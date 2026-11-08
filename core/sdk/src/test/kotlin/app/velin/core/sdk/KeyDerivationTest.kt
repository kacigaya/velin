package app.velin.core.sdk
import org.junit.Assert.*
import org.junit.Test
class KeyDerivationTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Upstream CompatibilityTestData.json:3694 onward, through the actual UniFFI export.
    @Test fun argon2idUpstreamVectors() {
        assertArrayEquals(hex("308121d790f9db5b5da74f3cf08563ae7dde1037894d7d5c1046c954140092aa"), KeyDerivation.derive("?".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
        assertArrayEquals(hex("ed6d3e4f797e58126d8a029abd9f952ca0cbc62784e7879f0d586f7439fc0cf2"), KeyDerivation.derive("%".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
        assertArrayEquals(hex("7d4f413a727caffccfc54258635009ee3bc8e0f81e7f926bf8a7fe3653dea108"), KeyDerivation.derive("€uropa".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
        assertArrayEquals(hex("f59ac45a834ba2eac57ccb9593b98318f42603522cae308510174acb419f30ba"), KeyDerivation.derive("?uropa".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
        assertArrayEquals(hex("84a262bf28ef130fad2a8e311bb36f8b88cc87c6f993162f0f152958b4fbcbd1"), KeyDerivation.derive("This passphrase is relatively long, I hope I won't forget it".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
        assertArrayEquals(hex("6a95c8e79674bde8c050185a121eb5bcf626430eda6eb70b1bbe7252b93307be"), KeyDerivation.derive("This passphrase is relatively long, I hope I won't forget it!".toCharArray(), hex("aca009b0d305008d17d83c6ca08f5ae4"), KdfAlgorithm.Argon2id))
    }
    @Test fun clearsPasswordOnSuccessAndFailure() {
        val password = charArrayOf('x')
        KeyDerivation.derive(password, ByteArray(16), KdfAlgorithm.Bcrypt128)
        assertArrayEquals(charArrayOf('\u0000'), password)
        val invalid = charArrayOf('x')
        try { KeyDerivation.derive(invalid, ByteArray(15), KdfAlgorithm.Argon2id); fail() }
        catch (e: KeyDerivationException) { assertEquals(KdfFailure.InvalidSalt, e.failure) }
        assertArrayEquals(charArrayOf('\u0000'), invalid)
    }
}
