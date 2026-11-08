package app.velin.feature.mail.reader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDocumentTest {
    private fun csp(allowRemote: Boolean): String {
        val doc = mailDocument("<p>hi</p>", Color.Black, Color.Blue, allowRemote)
        return Regex("Content-Security-Policy\" content=\"([^\"]*)\"").find(doc)!!.groupValues[1]
    }

    @Test
    fun blocksRemoteContentByDefault() {
        val policy = csp(allowRemote = false)
        assertTrue(policy.startsWith("default-src 'none'"))
        assertTrue("img-src data:;" in policy)
        assertFalse("https:" in policy)
    }

    @Test
    fun allowsOnlyHttpsImagesWhenUserAllows() {
        val policy = csp(allowRemote = true)
        assertTrue("img-src data: https:;" in policy)
        assertFalse("script-src" in policy)
    }

    @Test
    fun neverAllowsScriptsFramesOrForms() {
        for (allow in listOf(false, true)) {
            val policy = csp(allow)
            assertFalse("unsafe-eval" in policy)
            assertTrue("frame-src 'none'" in policy)
            assertTrue("form-action 'none'" in policy)
        }
    }
}
