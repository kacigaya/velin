package app.velin.core.sdk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TutaReleaseTest {
    @Test fun modelVersionsMatchPinnedRelease() {
        val source = File(System.getProperty("velin.upstream.source"))
        for ((app, expected) in listOf("sys" to TutaRelease.SYS_MODEL_VERSION,
            "tutanota" to TutaRelease.TUTANOTA_MODEL_VERSION)) {
            val text = source.resolve("src/entities/$app/ModelInfo.ts").readText()
            assertEquals(expected, Regex("version:\\s*(\\d+)").find(text)!!.groupValues[1].toInt())
        }
    }
}
