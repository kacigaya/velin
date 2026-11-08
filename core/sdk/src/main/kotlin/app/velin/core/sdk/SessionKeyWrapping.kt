package app.velin.core.sdk

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** GPLv3 port: crypto-primitives/src/key.rs:62-73 and aes.rs:108-122,410-459,482-538.
 * Persistent sessions always use a fresh AES-256 access key, including legacy bcrypt accounts. */
object SessionKeyWrapping {
    fun encrypt(accessKey: ByteArray, passphraseKey: ByteArray): ByteArray =
        encryptWithIv(accessKey, passphraseKey, ByteArray(16).also { SecureRandom().nextBytes(it) })

    internal fun encryptWithIv(accessKey: ByteArray, passphraseKey: ByteArray, iv: ByteArray): ByteArray {
        require(accessKey.size == 32 && passphraseKey.size in setOf(16, 32) && iv.size == 16)
        val subkeys = MessageDigest.getInstance("SHA-512").digest(accessKey)
        val encryptionKey = subkeys.copyOfRange(0, 32)
        val macKey = subkeys.copyOfRange(32, 64)
        try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            val ciphertext = iv + cipher.doFinal(passphraseKey)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(macKey, "HmacSHA256"))
            return byteArrayOf(1) + ciphertext + mac.doFinal(ciphertext)
        } finally { subkeys.fill(0); encryptionKey.fill(0); macKey.fill(0) }
    }
}
