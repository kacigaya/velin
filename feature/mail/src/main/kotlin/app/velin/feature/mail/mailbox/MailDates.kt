package app.velin.feature.mail.mailbox

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class DateStyle { Time, MonthDay, Date }

/** Same day shows the time, same year the month and day, older mail the full short date. */
internal fun dateStyleFor(instant: Instant, now: Instant, zone: ZoneId): DateStyle {
    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        date == today -> DateStyle.Time
        date.year == today.year -> DateStyle.MonthDay
        else -> DateStyle.Date
    }
}

internal fun formatListDate(
    instant: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val formatter = when (dateStyleFor(instant, now, zone)) {
        DateStyle.Time -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        DateStyle.MonthDay -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        DateStyle.Date -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
    return formatter.withLocale(locale).format(instant.atZone(zone))
}
