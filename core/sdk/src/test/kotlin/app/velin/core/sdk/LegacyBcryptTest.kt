package app.velin.core.sdk
import org.junit.Assert.*
import org.junit.Test
class LegacyBcryptTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Upstream app-android/tutashared/src/androidTest/assets/CompatibilityTestData.json:3630-3693.
    @Test fun upstreamVectors() {
        assertArrayEquals(hex("8ed5da5044a50d0c86ec9debcdc9d38e"), LegacyBcrypt.derive("?".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("5681e09b2d1c6522befb9adf08a5b767"), LegacyBcrypt.derive("%".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("edb2de75c6c2d2bbd703b700e7e9a134"), LegacyBcrypt.derive("€uropa".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("93ea2e86219865c48f16fc5cee97521f"), LegacyBcrypt.derive("?uropa".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("b97b50546dc89785fb12e02b96b02fc7"), LegacyBcrypt.derive("This passphrase is relatively long, I hope I won't forget it".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("dd64d2c8b8fb8d1601826298073c0d4d"), LegacyBcrypt.derive("This passphrase is relatively long, I hope I won't forget it!".toCharArray(), hex("c86e9ed454acede09237b9f4fbe4581f"), 128))
        assertArrayEquals(hex("0e5cb75f57bba9a8bb7a04cea69d5b993503895e10e59df033ced4ca2046cf6d"), LegacyBcrypt.derive("?".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
        assertArrayEquals(hex("9cb1838343269e9d0c7e8033407c7f6e9d5036f629d755915bff98690482f2c2"), LegacyBcrypt.derive("%".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
        assertArrayEquals(hex("459c1aa4becf6de26848de0b727656995d6d62626c9b5c1dae46d17ac17d5f7e"), LegacyBcrypt.derive("€uropa".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
        assertArrayEquals(hex("5269f27dc26c95177709af31a4b753c38d229eb652498188db19684c4e4c29fb"), LegacyBcrypt.derive("?uropa".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
        assertArrayEquals(hex("caa55fd44f3522c567d7066f2ce0a5ac773e6711f132759a317e8747cdc04dea"), LegacyBcrypt.derive("This passphrase is relatively long, I hope I won't forget it".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
        assertArrayEquals(hex("e1ceb19ebc4bba10a41da0a34b6b8e886fdf9ce377e09f162266deea14f58012"), LegacyBcrypt.derive("This passphrase is relatively long, I hope I won't forget it!".toCharArray(), hex("8df90d0c6f2e686329e03db9528eee70"), 256))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBadSalt() {
        LegacyBcrypt.derive(charArrayOf('x'), ByteArray(15))
    }
}
