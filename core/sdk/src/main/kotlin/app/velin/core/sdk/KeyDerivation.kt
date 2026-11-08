package app.velin.core.sdk

import de.tutao.tutasdk.argon2idGenerateKeyFromPassphrase

enum class KdfAlgorithm { Argon2id, Bcrypt128 }
enum class KdfFailure { InvalidSalt, NativeUnavailable, NativeFailure }
class KeyDerivationException(val failure: KdfFailure) : Exception(failure.name)

object KeyDerivation {
    /** Takes ownership of password. JVM/FFI string copies cannot be reliably cleared. */
    fun derive(password: CharArray, salt: ByteArray, algorithm: KdfAlgorithm): ByteArray {
        try {
            if (salt.size != 16) throw KeyDerivationException(KdfFailure.InvalidSalt)
            return when (algorithm) {
                KdfAlgorithm.Bcrypt128 -> LegacyBcrypt.derive(password, salt)
                // Upstream simple_crypto.rs:213-223 validates salt, then uses crypto/argon2_id.rs:5-29.
                KdfAlgorithm.Argon2id -> try {
                    argon2idGenerateKeyFromPassphrase(String(password), salt)
                } catch (_: LinkageError) {
                    throw KeyDerivationException(KdfFailure.NativeUnavailable)
                } catch (_: Exception) {
                    throw KeyDerivationException(KdfFailure.NativeFailure)
                }
            }
        } finally { password.fill('\u0000') }
    }
}
