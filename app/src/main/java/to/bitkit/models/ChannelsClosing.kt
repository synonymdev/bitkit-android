package to.bitkit.models

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Date on which Blocktank closes the remaining Lightning channels; set before release. */
val CHANNELS_CLOSE_DATE: LocalDate = LocalDate.of(2026, 12, 15)

fun formatChannelsCloseDate(locale: Locale = Locale.getDefault()): String =
    CHANNELS_CLOSE_DATE.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
