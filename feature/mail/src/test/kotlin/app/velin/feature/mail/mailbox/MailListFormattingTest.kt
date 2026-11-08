package app.velin.feature.mail.mailbox

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailListFormattingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun sameLocalDayUsesTime() {
        assertEquals(DateStyle.Time, dateStyleFor(Instant.parse("2026-11-07T23:30:00Z"), now, zone))
    }

    @Test
    fun previousLocalDayUsesMonthDay() {
        // 22:30 UTC on Nov 7 is still Nov 7 in Berlin (UTC+1).
        assertEquals(DateStyle.MonthDay, dateStyleFor(Instant.parse("2026-11-07T22:30:00Z"), now, zone))
    }

    @Test
    fun previousYearUsesFullDate() {
        assertEquals(DateStyle.Date, dateStyleFor(Instant.parse("2025-12-31T12:00:00Z"), now, zone))
    }

    @Test
    fun parsesValidHexColors() {
        assertEquals(Color(0xFF2196F3), parseHexColor("#2196f3"))
        assertEquals(Color(0xFF2196F3), parseHexColor("2196F3"))
    }

    @Test
    fun rejectsInvalidHexColors() {
        assertNull(parseHexColor("#fff"))
        assertNull(parseHexColor("#12345g"))
        assertNull(parseHexColor(""))
    }

    @Test
    fun picksReadableLabelText() {
        assertEquals(Color.Black, contentColorFor(Color(0xFFFFEB3B)))
        assertEquals(Color.White, contentColorFor(Color(0xFF1B3466)))
    }

    @Test
    fun capsUnreadBadge() {
        assertEquals("7", unreadBadge(7))
        assertEquals("999", unreadBadge(999))
        assertEquals("999+", unreadBadge(1000))
    }
}
