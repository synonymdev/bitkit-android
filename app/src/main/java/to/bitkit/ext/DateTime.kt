@file:Suppress("TooManyFunctions")

package to.bitkit.ext

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.NumberFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaMonth
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant as KInstant

@OptIn(ExperimentalTime::class)
fun nowMillis(clock: Clock = Clock.System): Long = clock.now().toEpochMilliseconds()

fun nowTimestamp(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

fun Instant.formatted(pattern: String = DatePattern.DATE_TIME): String {
    val dateTime = LocalDateTime.ofInstant(this, ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern(pattern)
    return dateTime.format(formatter)
}

fun ULong?.formatToString(pattern: String = DatePattern.DATE_TIME): String? {
    return this?.let { Instant.ofEpochSecond(toLong()).formatted(pattern) }
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

fun Long.toLocalizedTimestamp(): String {
    val uLocale = ULocale.forLocale(Locale.US)
    val formatter = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, uLocale)
        ?: return SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.US).format(Date(this))
    return formatter.format(Date(this))
}

@Suppress("LongMethod")
@OptIn(ExperimentalTime::class)
fun Long.toRelativeTimeString(
    locale: Locale = Locale.getDefault(),
    clock: Clock = Clock.System,
): String {
    val now = nowMillis(clock)
    val diffMillis = now - this

    val uLocale = ULocale.forLocale(locale)
    val numberFormat = NumberFormat.getNumberInstance(uLocale)?.apply { maximumFractionDigits = 0 }

    val formatter = RelativeDateTimeFormatter.getInstance(
        uLocale,
        numberFormat,
        RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    ) ?: return toLocalizedTimestamp()

    val seconds = diffMillis / Factor.MILLIS_TO_SECONDS
    val minutes = seconds / Factor.SECONDS_TO_MINUTES
    val hours = minutes / Factor.MINUTES_TO_HOURS
    val days = hours / Factor.HOURS_TO_DAYS
    val weeks = days / Factor.DAYS_TO_WEEKS
    val months = days / Factor.DAYS_TO_MONTHS
    val years = days / Factor.DAYS_TO_YEARS

    return when {
        seconds < Threshold.SECONDS -> formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
        minutes < Threshold.MINUTES -> formatter.format(minutes, Direction.LAST, RelativeUnit.MINUTES)
        hours < Threshold.HOURS -> formatter.format(hours, Direction.LAST, RelativeUnit.HOURS)
        days < Threshold.YESTERDAY -> formatter.format(Direction.LAST, AbsoluteUnit.DAY)
        days < Threshold.DAYS -> formatter.format(days, Direction.LAST, RelativeUnit.DAYS)
        weeks < Threshold.WEEKS -> formatter.format(weeks, Direction.LAST, RelativeUnit.WEEKS)
        months < Threshold.MONTHS -> formatter.format(months, Direction.LAST, RelativeUnit.MONTHS)
        else -> formatter.format(years, Direction.LAST, RelativeUnit.YEARS)
    }
}

fun getDaysInMonth(month: LocalDate): List<LocalDate?> {
    val firstDayOfMonth = LocalDate(month.year, month.month, Constants.FIRST_DAY_OF_MONTH)
    // FIXME fix month.number
    val daysInMonth = month.month.toJavaMonth().length(isLeapYear(month.year))

    // Get the day of week for the first day (1 = Monday, 7 = Sunday)
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.ordinal + CalendarConstants.CALENDAR_WEEK_OFFSET

    // Calculate offset (days before the first day)
    // We want Sunday to be 0, so adjust accordingly
    val offset = (firstDayOfWeek % CalendarConstants.DAYS_IN_WEEK_MOD)

    val days = mutableListOf<LocalDate?>()

    // Add empty spaces before the first day
    repeat(offset) {
        days.add(null)
    }

    // Add all days of the month
    for (day in Constants.FIRST_DAY_OF_MONTH..daysInMonth) {
        days.add(LocalDate(month.year, month.month, day))
    }

    // Add empty spaces to complete the last week (total should be multiple of 7)
    while (days.size % CalendarConstants.DAYS_IN_WEEK_MOD != 0) {
        days.add(null)
    }

    return days
}

fun isLeapYear(year: Int): Boolean {
    return (year % Constants.LEAP_YEAR_DIVISOR_4 == 0 && year % Constants.LEAP_YEAR_DIVISOR_100 != 0) ||
        (year % Constants.LEAP_YEAR_DIVISOR_400 == 0)
}

@OptIn(ExperimentalTime::class)
fun isDateInRange(
    dateMillis: Long,
    startMillis: Long?,
    endMillis: Long?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    if (startMillis == null) return false
    val end = endMillis ?: startMillis

    val normalizedDate = KInstant.fromEpochMilliseconds(dateMillis).toLocalDateTime(zone).date
    val normalizedStart = KInstant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
    val normalizedEnd = KInstant.fromEpochMilliseconds(end).toLocalDateTime(zone).date

    return normalizedDate in normalizedStart..normalizedEnd
}

fun LocalDate.toMonthYearString(locale: Locale = Locale.getDefault()): String {
    val formatter = SimpleDateFormat(DatePattern.MONTH_YEAR_FORMAT, locale)
    val calendar = Calendar.getInstance()
    calendar.set(year, month.number - CalendarConstants.MONTH_INDEX_OFFSET, Constants.FIRST_DAY_OF_MONTH)
    return formatter.format(calendar.time)
}

fun LocalDate.minusMonths(months: Int): LocalDate =
    toJavaLocalDate().minusMonths(months.toLong()).withDayOfMonth(1) // Always use first day of month for display
        .toKotlinLocalDate()

fun LocalDate.plusMonths(months: Int): LocalDate =
    toJavaLocalDate().plusMonths(months.toLong()).withDayOfMonth(1) // Always use first day of month for display
        .toKotlinLocalDate()

@OptIn(ExperimentalTime::class)
fun LocalDate.endOfDay(zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    atStartOfDayIn(zone).plus(1.days).minus(1.milliseconds).toEpochMilliseconds()

fun utcDateFormatterOf(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}

object DatePattern {
    const val DATE_TIME = "dd/MM/yyyy, HH:mm"
    const val INVOICE_EXPIRY = "MMM dd, h:mm a"
    const val ACTIVITY_DATE = "MMMM d"
    const val ACTIVITY_ROW_DATE = "MMMM d, HH:mm"
    const val ACTIVITY_ROW_DATE_YEAR = "MMMM d yyyy, HH:mm"
    const val ACTIVITY_TIME = "h:mm"
    const val CHANNEL_DETAILS = "MMM d, yyyy, HH:mm"
    const val LOG_FILE = "yyyy-MM-dd_HH-mm-ss"
    const val LOG_LINE = "yyyy-MM-dd HH:mm:ss.SSS"

    const val MONTH_YEAR_FORMAT = "MMMM yyyy"
    const val DATE_FORMAT = "MMM d, yyyy"
    const val WEEKDAY_FORMAT = "EEE"
}

private object Constants {
    // Calendar
    const val FIRST_DAY_OF_MONTH = 1

    // Leap year calculation
    const val LEAP_YEAR_DIVISOR_4 = 4
    const val LEAP_YEAR_DIVISOR_100 = 100
    const val LEAP_YEAR_DIVISOR_400 = 400
}

private object Factor {
    const val MILLIS_TO_SECONDS = 1000.0
    const val SECONDS_TO_MINUTES = 60.0
    const val MINUTES_TO_HOURS = 60.0
    const val HOURS_TO_DAYS = 24.0
    const val DAYS_TO_WEEKS = 7.0
    const val DAYS_TO_MONTHS = 30.0
    const val DAYS_TO_YEARS = 365.0
}

private object Threshold {
    const val SECONDS = 60
    const val MINUTES = 60
    const val HOURS = 24
    const val YESTERDAY = 2
    const val DAYS = 7
    const val WEEKS = 4
    const val MONTHS = 12
}

object CalendarConstants {
    // Calendar grid
    const val DAYS_IN_WEEK = 7

    // Date formatting
    const val WEEKDAY_ABBREVIATION_LENGTH = 3

    // Calendar math
    const val DAYS_IN_WEEK_MOD = 7
    const val CALENDAR_WEEK_OFFSET = 1
    const val MONTH_INDEX_OFFSET = 1
}
