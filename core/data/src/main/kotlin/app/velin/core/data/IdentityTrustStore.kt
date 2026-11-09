package app.velin.core.data

import android.util.AtomicFile
import app.velin.core.sdk.SdkException
import app.velin.core.sdk.SdkFailure
import app.velin.core.sdk.SdkIdentityTrustStore
import app.velin.core.sdk.TrustedIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account-bound authenticated storage. Corrupt data blocks lookup; it never resets trust. */
internal class IdentityTrustStore(directory: File, private val account: String, private val key: () -> SecretKey) : SdkIdentityTrustStore {
    private val file = AtomicFile(File(directory, MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".trust"))
    private val lock = Any()
    private val aad = ("velin.identity-trust.v1\u0000" + account).toByteArray(Charsets.UTF_8)

    override suspend fun load(address: String): TrustedIdentity? = accessTrust { synchronized(lock) { read()[address] } }

    override suspend fun trustOnFirstUse(address: String, identity: TrustedIdentity): TrustedIdentity = accessTrust { synchronized(lock) {
        val entries = read()
        entries[address]?.let { return@synchronized it }
        require(identity.key.size == 32 && identity.version in 0..255 && !identity.manual)
        require(entries.size < 10000 && address.length in 3..254)
        entries[address] = identity
        write(entries)
        identity
    } }

    override suspend fun verify(address: String, expected: TrustedIdentity): Unit = accessTrust { synchronized(lock) {
        val entries = read()
        val current = requireNotNull(entries[address])
        require(current.version == expected.version && MessageDigest.isEqual(current.key, expected.key))
        entries[address] = TrustedIdentity(current.key, current.version, true)
        write(entries)
    } }

    override suspend fun untrust(address: String): Unit = accessTrust { synchronized(lock) {
        val entries = read()
        if (entries.remove(address) != null) write(entries)
    } }

    private fun <T> accessTrust(block: () -> T): T = try { block() }
    catch (_: Exception) { throw SdkException(SdkFailure.IdentityTrustStorage) }

    private fun read(): MutableMap<String, TrustedIdentity> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return linkedMapOf()
        val encrypted = file.openRead().use { require(it.channel.size() in 29..4 * 1024 * 1024); it.readBytes() }
        require(encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted, 13, encrypted.size - 13)
        try {
            return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                val count = input.readInt()
                require(count in 0..10000)
                val entries = linkedMapOf<String, TrustedIdentity>()
                repeat(count) {
                    val address = input.readUTF()
                    val version = input.readLong()
                    val manual = input.readBoolean()
                    val bytes = ByteArray(32).also(input::readFully)
                    require(address.length in 3..254 && version in 0..255 && address !in entries)
                    entries[address] = TrustedIdentity(bytes, version, manual)
                }
                require(input.read() == -1)
                entries
            }
        } finally { plaintext.fill(0) }
    }

    private fun write(entries: Map<String, TrustedIdentity>) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(entries.size)
            entries.forEach { (address, identity) ->
                output.writeUTF(address)
                output.writeLong(identity.version)
                output.writeBoolean(identity.manual)
                output.write(identity.key)
            }
        }
        val plaintext = buffer.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        val encrypted = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
