package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.utils.Logger
import java.text.SimpleDateFormat
import java.util.Date
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
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.getDefault())
        val date = formatter.parse(dateString) ?: return ""

        val now = Date()
        val diffInMillis = now.time - date.time

        when {
            diffInMillis < 60_000L -> "just now"
            diffInMillis < 3600_000L -> "${diffInMillis / 60_000L} minutes ago"
            diffInMillis < 86400_000L -> "${diffInMillis / 3600_000L} hours ago"
            diffInMillis < 2592000_000L -> "${diffInMillis / 86400_000L} days ago"
            else -> "${diffInMillis / 2592000_000L} months ago"
        }
    } catch (e: Exception) {
        Logger.debug("Failed to parse date: ${e.message}")
        ""
    }
}
