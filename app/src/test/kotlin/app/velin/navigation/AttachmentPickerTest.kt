package app.velin.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPickerTest {
    @Test
    fun fileNamesFitWhatTheBackendAccepts() {
        assertEquals("report.pdf", fileName("  report.pdf "))
        assertEquals("ab.txt", fileName("a\nb\u0000.txt"))
        assertEquals("attachment", fileName(null))
        assertEquals("attachment", fileName(" \t "))
        val long = "😀".repeat(300)
        assertEquals(255, fileName(long).codePointCount(0, fileName(long).length))
        assertEquals("😀".repeat(255), fileName(long))
    }
}
