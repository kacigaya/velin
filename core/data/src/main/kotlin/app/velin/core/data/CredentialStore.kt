package app.velin.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SavedCredentials(val login: String, val user: String, val token: String, val encryptedKey: ByteArray)
internal interface CredentialStore {
    fun all(): List<SavedCredentials>
    fun save(credentials: SavedCredentials)
    fun remove(user: String)
}

/** Keeps the whole account index encrypted, including login addresses and user IDs. */
internal class EncryptedCredentialStore(file: File, private val key: () -> SecretKey) : CredentialStore {
    private val file = AtomicFile(file)
    @Synchronized override fun all(): List<SavedCredentials> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val encrypted = file.openRead().use {
            require(it.channel.size() <= 1024 * 1024)
            it.readBytes()
        }
        val plaintext = CredentialCipher.decrypt(key(), encrypted)
        try {
            return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                val count = input.readInt()
                require(count in 0..100)
                List(count) {
                    val login = input.readUTF()
                    val user = input.readUTF()
                    val token = input.readUTF()
                    val size = input.readInt()
                    require(size in 1..1024)
                    SavedCredentials(login, user, token, ByteArray(size).also { input.readFully(it) })
                }.also { require(input.read() == -1) }
            }
        } finally { plaintext.fill(0) }
    }
    @Synchronized override fun save(credentials: SavedCredentials) = write(all().filter { it.user != credentials.user } + credentials)
    @Synchronized override fun remove(user: String) = write(all().filter { it.user != user })
    private fun write(accounts: List<SavedCredentials>) {
        require(accounts.size <= 100)
        val buffer = object : ByteArrayOutputStream() {
            fun clear() { buf.fill(0); reset() }
        }
        DataOutputStream(buffer).use { output ->
            output.writeInt(accounts.size)
            accounts.forEach {
                output.writeUTF(it.login); output.writeUTF(it.user); output.writeUTF(it.token)
                output.writeInt(it.encryptedKey.size); output.write(it.encryptedKey)
            }
        }
        val plaintext = buffer.toByteArray().also { buffer.clear() }
        val encrypted = try { CredentialCipher.encrypt(key(), plaintext) } finally { plaintext.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

internal object CredentialCipher {
    private val aad = "velin.credentials.v1".toByteArray()
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }
    fun decrypt(key: SecretKey, encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted, 13, encrypted.size - 13)
    }
}

// Same boundary as tutashared AndroidKeyStoreFacade.kt: a non-exportable key encrypts local credentials.
// GPLv3 upstream idea reused; AES-GCM here replaces its CBC wrapper and requires no user authentication.
internal object AndroidCredentialKey {
    @Synchronized fun get(): SecretKey {
        val alias = "app.velin.credentials.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
