package to.bitkit.ext

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun nowTimestamp(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

fun Instant.formatted(pattern: String = DatePattern.DATE_TIME): String {
    val dateTime = LocalDateTime.ofInstant(this, ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern(pattern)
    return dateTime.format(formatter)
}

fun Long.toTimeUTC(): String {
    val instant = Instant.ofEpochMilli(this)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"))
    return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

fun Long.toDateUTC(): String {
    val instant = Instant.ofEpochMilli(this)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"))
    return dateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}

object DatePattern {
    const val DATE_TIME = "dd/MM/yyyy, HH:mm"
    const val INVOICE_EXPIRY = "MMM dd, h:mm a"
    const val ACTIVITY_DATE = "MMMM d"
    const val ACTIVITY_ROW_DATE = "MMMM d, HH:mm"
    const val ACTIVITY_ROW_DATE_YEAR = "MMMM d yyyy, HH:mm"
    const val ACTIVITY_TIME = "h:mm"
    const val LOG_FILE = "yyyy-MM-dd_HH-mm-ss"
}
