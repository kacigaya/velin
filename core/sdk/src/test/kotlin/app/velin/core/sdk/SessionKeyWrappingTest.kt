package app.velin.core.sdk

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SessionKeyWrappingTest {
    // GPLv3 vectors: tutashared/src/androidTest/assets/CompatibilityTestData.json:50 onwards.
    @Test fun upstreamKeyWrappingVectors() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEE9sDHfpVvpRUy8KntkHF1C/2v15gdvma8hnlAiCiCvOoYAyrsJvKVQSHu7Pe55BYw="),
            SessionKeyWrapping.encryptWithIv(hex("719fa13882080758812eb99306456b4dc0f71366a9bbe0d4e32e282917200b74"), hex("5773327a412e4e4916089cc97d13fffd"), hex("4b6fdf570323292a220d23dbbdabc441")))
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEEIblmmGh3nlYLotqrI3Z7fOAQOzeRDenysmk5OAyZRVZxygxt5PH62UOzh1ZPHfjL/u2/1R+1iwCcX2w5JKwAi"),
            SessionKeyWrapping.encryptWithIv(hex("719fa13882080758812eb99306456b4dc0f71366a9bbe0d4e32e282917200b74"), hex("a58a1b28d831d6474fa2513089f58b7234732b1d7be779b84a29f53a65120ad1"), hex("4b6fdf570323292a220d23dbbdabc441")))
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEH4nhZwaPTk4HAGd37lNgLkUe5s7FK6QktGo9alXmQGFFtcTEbX/Yr5nuKh55NXEBQ="),
            SessionKeyWrapping.encryptWithIv(hex("bd9df131c0de4dff2e2905d5f827e38a20a69dfa09543abc29a7b3ad75553617"), hex("6dfe6cb7d9d6366d01024dace2e1acec"), hex("4b6fdf570323292a220d23dbbdabc441")))
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEEBwVPylEgIqgwzv5VxQBZAyzsit5MZtYj4kt0levZSv7I9vujWJ7IdqSE6v+QIGPxlZoDEkjHNALWRB3yhwA3k"),
            SessionKeyWrapping.encryptWithIv(hex("bd9df131c0de4dff2e2905d5f827e38a20a69dfa09543abc29a7b3ad75553617"), hex("5e5df5db842296ef874d8eb628df52e63567a65f6737419458a247cd24168f88"), hex("4b6fdf570323292a220d23dbbdabc441")))
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEGgLcZw7Wf75hos7MZDH7NubPkdsaxTisN14OgE9OoVxgs0pCNcX62dwfAGz1nsNnY="),
            SessionKeyWrapping.encryptWithIv(hex("8058e030da16c50f0d25cfe09d594a948bf228e1a85cf8d3495815341808ac49"), hex("65a056832d1196fed18378c765b103e9"), hex("4b6fdf570323292a220d23dbbdabc441")))
        assertArrayEquals(Base64.getDecoder().decode("AUtv31cDIykqIg0j272rxEHX8vtw22faZAdTfdimtB8bfF9jNvw00D4QP9iu2wC4SfrVMBjnesRG0fZPqKCZeAR5F07LqpQs5mPKFm9QZUXU"),
            SessionKeyWrapping.encryptWithIv(hex("8058e030da16c50f0d25cfe09d594a948bf228e1a85cf8d3495815341808ac49"), hex("3db1eb213d06c73a1ce5bfac26603d91b70803b44a09683ba857cf8b91be190b"), hex("4b6fdf570323292a220d23dbbdabc441")))
    }
}
