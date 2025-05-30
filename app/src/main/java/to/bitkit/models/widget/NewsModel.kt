package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.utils.Logger
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

@Serializable
data class NewsModel(
    val title: String,
    val timeAgo: String,
    val link: String,
    val publisher: String
)

fun ArticleDTO.toNewsModel() = NewsModel(
    title = this.title,
    timeAgo = timeAgo(this.publishedDate),
    link = this.link,
    publisher = this.publisher.title
)

/**
 * Converts a date string to a human-readable time ago format
 * @param dateString Date string in format "EEE, dd MMM yyyy HH:mm:ss Z"
 * @return Human-readable time difference (e.g. "5 hours ago")
 */
private fun timeAgo(dateString: String): String {
    return try {
        val formatters = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME, // Handles "EEE, dd MMM yyyy HH:mm:ss zzz" (like GMT)
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH) // Handles "+0000"
        )

        var parsedDateTime: OffsetDateTime? = null
        for (formatter in formatters) {
            try {
                parsedDateTime = OffsetDateTime.parse(dateString, formatter)
                break // Successfully parsed, stop trying other formatters
            } catch (e: DateTimeParseException) {
                // Continue to the next formatter if this one fails
            }
        }

        if (parsedDateTime == null) {
            Logger.debug("Failed to parse date: Unparseable date: \"$dateString\" [NewsModel.kt:46]")
            return ""
        }

        val now = OffsetDateTime.now()

        val diffMinutes = ChronoUnit.MINUTES.between(parsedDateTime, now)
        val diffHours = ChronoUnit.HOURS.between(parsedDateTime, now)
        val diffDays = ChronoUnit.DAYS.between(parsedDateTime, now)
        val diffMonths = ChronoUnit.MONTHS.between(parsedDateTime, now)

        return when {
            diffMinutes < 1 -> "just now"
            diffMinutes < 60 -> "$diffMinutes minutes ago"
            diffHours < 24 -> "$diffHours hours ago"
            diffDays < 30 -> "$diffDays days ago" // Approximate for months
            else -> "$diffMonths months ago"
        }
    } catch (e: Exception) {
        Logger.debug("An unexpected error occurred while parsing date: ${e.message}")
        ""
    }
}
