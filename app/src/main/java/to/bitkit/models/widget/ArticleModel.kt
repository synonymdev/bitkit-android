package to.bitkit.models.widget

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.ext.toRelativeTimeString
import to.bitkit.utils.Logger
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.time.ExperimentalTime

private const val TAG = "ArticleModel"

@Immutable
@Serializable
data class ArticleModel(
    val title: String,
    val timeAgo: String,
    val link: String,
    val publisher: String
)

fun ArticleModel.safeBrowserUri(): Uri? = safeBrowserUri(link)

fun safeBrowserUri(link: String): Uri? {
    if (link.isEmpty()) return null
    val uri = runCatching { link.toUri() }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") {
        Logger.warn("Rejected article link with unsafe scheme '$scheme'", context = TAG)
        return null
    }
    return uri
}

fun ArticleDTO.toArticleModel() = ArticleModel(
    title = this.title,
    timeAgo = timeAgo(this.publishedDate),
    link = this.link,
    publisher = this.publisher.title
)

@OptIn(ExperimentalTime::class)
private fun timeAgo(dateString: String): String {
    return runCatching {
        val formatters = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME, // Handles "EEE, dd MMM yyyy HH:mm:ss zzz" (like GMT)
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH) // Handles "+0000"
        )

        var parsedDateTime: OffsetDateTime? = null
        for (formatter in formatters) {
            try {
                parsedDateTime = OffsetDateTime.parse(dateString, formatter)
                break // Successfully parsed, stop trying other formatters
            } catch (_: DateTimeParseException) {
                // Continue to the next formatter
            }
        }

        requireNotNull(parsedDateTime) { "Unparseable date: '$dateString'" }

        parsedDateTime.toInstant().toEpochMilli().toRelativeTimeString()
    }.onFailure {
        Logger.warn("Failed to parse date: ${it.message}", it, context = TAG)
    }.getOrDefault("")
}
